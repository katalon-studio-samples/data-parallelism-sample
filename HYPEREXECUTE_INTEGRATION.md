# Scaling Data-Driven Katalon Tests with HyperExecute

How to combine this project's data-partitioning pattern with TestMu/LambdaTest HyperExecute to execute thousands of data-driven tests across tens or hundreds of cloud VMs.

## Context

### What This Project Does (Single Machine)

The **Create Parallel Suites** utility partitions a data-bound test suite into N slices and runs them concurrently via a Katalon test suite collection:

```
Source Suite (1,000 rows)
        │
        ▼  Create Parallel Suites (N=5)
┌───┬───┬───┬───┬───┐
│P1 │P2 │P3 │P4 │P5 │    ← 5 partitioned .ts files
│1- │201│401│601│801│
│200│-  │-  │-  │-  │
│   │400│600│800│1000│
└───┴───┴───┴───┴───┘
        │
   Parallel Collection
  (5 concurrent JVMs on one machine)
```

This is bottlenecked by local CPU and memory. A single machine can only support ~3-8 concurrent Katalon JVM instances.

### What HyperExecute Adds (Cloud Scale)

HyperExecute is a cloud test orchestration platform that provisions dedicated VMs and distributes test execution across them. Key features:

- **Auto-split mode** -- discovers test entities and distributes them across N VMs with smart scheduling
- **Matrix mode** -- runs a command across every combination of defined variables
- **Native Katalon support** -- `runtime: katalon` installs KRE on each VM automatically
- **Co-located execution** -- test code and browser run on the same VM (no hub-and-node network hops)
- **Scales to hundreds of concurrent VMs** (bounded by license count)

## Integration Options

### Option A: Pre-Partition Locally, Distribute via Auto-Split

Use the existing partitioning logic to generate N suite files, then let HyperExecute discover and distribute them -- one partition per VM.

**Workflow:**

```
Local Machine                    HyperExecute Cloud
─────────────                    ──────────────────
1. Run Create Parallel           3. testDiscovery finds
   Suites (N=100)                   100 partition .ts files
        │                                │
2. Upload project          ───►  4. Auto-split assigns
   via HyperExecute CLI             each to a VM
                                        │
                                 5. 100 VMs each run
                                    katalonc on their
                                    assigned partition
```

**hyperexecute.yaml:**

```yaml
version: 0.2
runson: win
autosplit: true
concurrency: 100

globalTimeout: 150
testSuiteTimeout: 60

runtime:
  - language: katalon

testDiscovery:
  type: raw
  mode: dynamic
  command: |
    ls "Test Suites/Generated/"*.ts \
      | grep "Partition" \
      | sed 's|\.ts$||'

testRunnerCommand: |
  katalonc -noSplash -runMode=console \
    -projectPath="${PWD}/data-parallelism.prj" \
    -testSuitePath="$test" \
    -browserType="Chrome (headless)" \
    -apiKey="$KRE_API_KEY" \
    -executionProfile="default"

mergeArtifacts: true
uploadArtefacts:
  - name: Reports
    path:
      - Reports/**

retryOnFailure: true
maxRetries: 2
```

**Execution:**

```bash
# 1. Generate partitions locally (inside Katalon or via katalonc)
katalonc -noSplash -runMode=console \
  -projectPath="$(pwd)/data-parallelism.prj" \
  -testSuitePath="Test Suites/Utilities/Create Parallel Suites" \
  -apiKey="$KRE_API_KEY" \
  -g_sourceTestSuiteId="Test Suites/Print Names" \
  -g_numberOfPartitions="100"

# 2. Trigger HyperExecute
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yaml
```

**Pros:** Simple -- reuses existing partitioning code without changes.
**Cons:** Requires a local pre-generation step before each run.

---

### Option B: Generate Partitions in globalPre, Then Auto-Split (Recommended)

Eliminate the local step entirely. HyperExecute's `globalPre` runs once before distributing work, so partition generation happens in the cloud.

**hyperexecute.yaml:**

```yaml
version: 0.2
runson: win
autosplit: true
concurrency: 100

globalTimeout: 150
testSuiteTimeout: 60

runtime:
  - language: katalon

env:
  SOURCE_SUITE: "Test Suites/Print Names"
  NUM_PARTITIONS: "100"

globalPre:
  mode: remote
  commands:
    # Generate 100 partitioned suites using the existing Katalon utility
    - |
      katalonc -noSplash -runMode=console \
        -projectPath="${PWD}/data-parallelism.prj" \
        -testSuitePath="Test Suites/Utilities/Create Parallel Suites" \
        -apiKey="$KRE_API_KEY" \
        -g_sourceTestSuiteId="$SOURCE_SUITE" \
        -g_numberOfPartitions="$NUM_PARTITIONS"

testDiscovery:
  type: raw
  mode: dynamic
  command: |
    ls "Test Suites/Generated/"*.ts \
      | grep "Partition" \
      | sed 's|\.ts$||'

testRunnerCommand: |
  katalonc -noSplash -runMode=console \
    -projectPath="${PWD}/data-parallelism.prj" \
    -testSuitePath="$test" \
    -browserType="Chrome (headless)" \
    -apiKey="$KRE_API_KEY" \
    -executionProfile="default"

mergeArtifacts: true
uploadArtefacts:
  - name: Reports
    path:
      - Reports/**

retryOnFailure: true
maxRetries: 2

failFast:
  maxNumberOfTests: 5
  level: scenario
```

**Execution:**

```bash
# Single command -- everything happens in the cloud
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yaml
```

**Pros:** Fully self-contained -- one command triggers the entire pipeline.
**Cons:** `globalPre` adds ~1-2 min for the partition generation step.

---

### Option C: Matrix Mode with Per-VM Range Calculation

Skip Katalon-level partitioning entirely. Use HyperExecute's matrix mode to assign partition indices, then patch the test suite XML on each VM to set the correct row range.

```
HyperExecute provisions 100 VMs
        │
   matrix.partition = [1, 2, 3, ... 100]
        │
  ┌─────┼─────┬─────┬─── ... ──┐
  ▼     ▼     ▼     ▼          ▼
 VM 1  VM 2  VM 3  VM 4      VM 100
  │     │     │     │          │
  pre:  pre:  pre:  pre:      pre:
  calc  calc  calc  calc      calc
  range range range range     range
  1-10  11-20 21-30 31-40     991-1000
  │     │     │     │          │
  patch patch patch patch     patch
  .ts   .ts   .ts   .ts       .ts
  │     │     │     │          │
  run   run   run   run       run
  katalonc    katalonc        katalonc
```

**hyperexecute.yaml:**

```yaml
version: 0.2
runson: win

env:
  TOTAL_ROWS: "1000"
  TOTAL_PARTITIONS: "100"

matrix:
  partition:
    - 1
    - 2
    - 3
    # ... through 100
    # Or generate dynamically via testDiscovery in hybrid mode

concurrency: 100

runtime:
  - language: katalon

pre:
  # Calculate this VM's row range and patch the suite XML
  - |
    python3 -c "
    import os, re
    p = int(os.environ['partition'])
    total = int(os.environ['TOTAL_ROWS'])
    n = int(os.environ['TOTAL_PARTITIONS'])
    base = total // n
    remainder = total % n
    start = sum(base + (1 if i < remainder else 0) for i in range(p - 1)) + 1
    size = base + (1 if (p - 1) < remainder else 0)
    end = start + size - 1
    range_str = f'{start}-{end}'
    print(f'Partition {p}: rows {range_str}')
    # Patch the test suite XML
    ts_path = 'Test Suites/Print Names.ts'
    xml = open(ts_path).read()
    xml = re.sub(
        r'<iterationType>[^<]*</iterationType>',
        '<iterationType>RANGE</iterationType>',
        xml
    )
    xml = re.sub(
        r'(<iterationType>RANGE</iterationType>\s*<value>)[^<]*(</value>)',
        rf'\g<1>{range_str}\g<2>',
        xml
    )
    open(ts_path, 'w').write(xml)
    "

testSuites:
  - |
    katalonc -noSplash -runMode=console \
      -projectPath="${PWD}/data-parallelism.prj" \
      -testSuitePath="Test Suites/Print Names" \
      -browserType="Chrome (headless)" \
      -apiKey="$KRE_API_KEY" \
      -executionProfile="default"

mergeArtifacts: true
uploadArtefacts:
  - name: Reports
    path:
      - Reports/**
```

**Pros:** No Katalon pre-step, no generated files, minimal payload size.
**Cons:** Requires maintaining a Python patching script; bypasses the project's Groovy-based partitioning.

## Recommendation

**Option B** is the best balance of simplicity and power:

- Reuses the existing `Create Parallel Suites` utility unchanged
- Fully self-contained (single `./hyperexecute` command)
- `globalPre` runs partition generation once, then auto-split distributes the results
- Easy to adjust -- change `NUM_PARTITIONS` and `concurrency` as needed

## Scaling Guidelines

### Right-Sizing Partitions

The goal is to make each partition run long enough to amortize `katalonc` JVM startup (~30-60s) but short enough to finish within a reasonable window.

| Scenario | Total Rows | Rows/Partition | Partitions (VMs) | Est. Time per VM |
|---|---|---|---|---|
| Quick smoke test | 100 | 10 | 10 | ~5 min |
| Medium regression | 1,000 | 20 | 50 | ~10 min |
| Full regression | 5,000 | 50 | 100 | ~25 min |
| Large-scale | 10,000 | 100 | 100 | ~50 min |

*Estimates assume ~30s per data row for a typical web UI test.*

**Formula:**

```
partitions = ceil(total_rows / target_rows_per_partition)

Target: each VM runs 5-15 minutes
  → target_rows_per_partition = (target_minutes * 60) / seconds_per_row
```

### Licensing Costs

Each concurrent VM requires two things:

| License | Purpose | Notes |
|---|---|---|
| **Katalon Runtime Engine (KRE)** | Execute Katalon tests in console mode | 1 license per concurrent VM |
| **HyperExecute concurrency** | Provision cloud VMs | Bounded by your LambdaTest plan |

For 100 concurrent VMs, you need 100 KRE licenses and a HyperExecute plan supporting 100+ concurrency.

### Performance Optimizations

```yaml
# Cache Katalon/Maven dependencies across runs
cacheKey: '{{ checksum "build.gradle" }}'
cacheDirectories:
  - .gradle
  - Libs

# Only upload changed files (faster upload for large projects)
differentialUpload:
  enabled: true
  ttlHours: 48

# Stop early if tests are catastrophically failing
failFast:
  maxNumberOfTests: 5
  level: scenario

# Retry flaky tests without re-running the whole partition
retryOnFailure: true
maxRetries: 2
```

## Prerequisites

1. **LambdaTest account** with HyperExecute access
2. **Katalon Runtime Engine license** (one per concurrent VM)
3. **HyperExecute CLI** downloaded from LambdaTest
4. **Environment variables** set:
   - `LT_USERNAME` -- LambdaTest username
   - `LT_ACCESS_KEY` -- LambdaTest access key
   - `KRE_API_KEY` -- Katalon API key for runtime engine activation

## Quick Start

```bash
# 1. Download the HyperExecute CLI
curl -O https://downloads.lambdatest.com/hyperexecute/darwin/hyperexecute
chmod +x hyperexecute

# 2. Set credentials
export LT_USERNAME="your_username"
export LT_ACCESS_KEY="your_access_key"
export KRE_API_KEY="your_katalon_api_key"

# 3. Validate the config
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yaml --validate

# 4. Run
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yaml

# 5. Download results
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yaml \
  --download-artifacts --download-artifacts-path ./results
```
