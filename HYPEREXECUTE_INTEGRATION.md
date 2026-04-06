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

### Option B: Generate Partitions in `pre`, Then Auto-Split (Recommended)

Eliminate the local step entirely. HyperExecute's `pre` phase runs on each worker VM before test discovery, so partition generation happens in the cloud — on the same VM that will execute the tests.

**Why `pre` and not `globalPre`?** With a pinned Katalon runtime (`runtime.version`), worker VMs receive a fresh extraction of the original project upload. Files written during `globalPre` are NOT propagated to workers, even with `cache: true` — the custom-runtime workspace provisioning step blows them away. `pre` runs on the same VM as `testDiscovery` and `testRunnerCommand`, so generated files are guaranteed to be visible. See `hyperexecute-evaluation-report.md` for empirical verification.

**Tradeoff:** every worker independently runs `katalonc` to generate partitions, costing ~30–60s of duplicated JVM startup per worker. Acceptable at low-to-moderate concurrency. At very high concurrency, see "Future optimization" below.

**hyperexecute.yml:**

```yaml
version: 0.1
runson: win
shell: bash
autosplit: true
concurrency: 4

# Local headless Chrome on the worker never creates a LambdaTest session,
# so scenarios get marked "skipped" by default. This flag makes HyperExecute
# derive scenario status from katalonc's exit code instead.
scenarioCommandStatusOnly: true

globalTimeout: 150
testSuiteTimeout: 60

runtime:
  language: katalon
  version: 11.0.1

env:
  SOURCE_SUITE: "Test Suites/Print Names"
  NUM_PARTITIONS: "4"
  KRE_API_KEY: ${{ .secrets.KRE_API_KEY }}

# Generation runs per-worker. The Create Parallel Suites Groovy script reads
# SOURCE_SUITE / NUM_PARTITIONS from the OS environment via System.getenv(),
# so no Katalon -g_ plumbing is needed.
pre:
  - >-
    katalonc -noSplash -runMode=console
    -projectPath="$(pwd)/data-parallelism.prj"
    -testSuitePath="Test Suites/Utilities/Create Parallel Suites"
    -browserType="Chrome (headless)"
    -apiKey="$(printenv KRE_API_KEY)"

testDiscovery:
  type: raw
  mode: dynamic
  command: |
    ls "Test Suites/Generated/"*.ts \
      | grep "Partition" \
      | sed 's|\.ts$||'

# IMPORTANT: testRunnerCommand runs in PowerShell on Windows, not bash —
# even with `shell: bash` set above. Use $env:VAR for OS env vars.
# `$test` is a HyperExecute template injection (substituted before PowerShell
# sees the command), so it works as a literal `$test` here.
testRunnerCommand: >-
  katalonc -noSplash -runMode=console
  -projectPath="$PWD\data-parallelism.prj"
  -testSuitePath="$test"
  -browserType="Chrome (headless)"
  -apiKey="$env:KRE_API_KEY"
  -executionProfile="default"

alwaysRunPostSteps: true

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
  --config hyperexecute.yml
```

**Pros:** Fully self-contained -- one command triggers the entire pipeline. Files generated where they're consumed, no cross-VM propagation problems.
**Cons:** Partition generation runs N times (once per worker) instead of once. At high concurrency this becomes wasteful.

**Future optimization for high concurrency:** when the duplicated `katalonc` startup becomes meaningful (e.g., 50+ workers), rewrite the partitioning logic as a standalone Python or bash script that doesn't require booting a JVM. Run it in `globalPre` (where its outputs would propagate cleanly because it doesn't depend on the Katalon runtime workspace) or directly in `pre` (where the lighter script makes duplication cheap).

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
- Each worker generates its own partitions in `pre`, then auto-split distributes the results
- Easy to adjust -- change `NUM_PARTITIONS` and `concurrency` as needed

## Platform Gotchas (learned the hard way)

These quirks bit us during integration. The full reasoning is in `hyperexecute-evaluation-report.md`; this is the cheat sheet.

1. **Custom runtime breaks `globalPre` file propagation.** With `runtime.version` pinned, files written during `globalPre` do not reach worker VMs even with `cache: true`. Workers receive a fresh extraction of the original upload. Generate files in `pre` instead.

2. **`testRunnerCommand` is PowerShell on Windows**, regardless of `shell: bash`. Use `$env:VAR` for OS env vars, Windows-style paths, and `$PWD` (PowerShell) instead of `$(pwd)` (bash). All other phases (including `pre`, `globalPre`, `testDiscovery`, `post`) use bash via MSYS2 even on Windows.

3. **HyperExecute's `$test` template variable is substituted before the shell sees it.** It looks like a bash/PowerShell variable in the YAML but isn't — write `$test` as a literal in `testRunnerCommand` and it just works on both platforms.

4. **User-defined `env:` variables don't template-substitute in `testRunnerCommand`.** Only `$test` (the platform injection) does. For your own env vars, use `$env:VAR` (PowerShell) or `$(printenv VAR)` (bash).

5. **Top-level `runson: win` does NOT cascade to `globalPre`.** Set `runson: win` explicitly inside `globalPre` (and likely `globalPost`) when using a Windows-only custom runtime, or you'll get `Runtime Setup Failed: custom katalon is not supported for os linux`.

6. **"PARTIALLY COMPLETED" / scenarios marked "skipped" on success.** Happens when tests don't create LambdaTest sessions (e.g., local headless Chrome on the worker). Set `scenarioCommandStatusOnly: true` at the top level of the YAML to derive scenario status from the command's exit code instead.

7. **Katalon `-g_VAR` flags only override existing `GlobalVariable`s defined in a profile.** If the variable isn't pre-declared in `Profiles/*.glbl`, the flag is silently ignored. To pass values into a Test Case from the CLI without GlobalVariable plumbing, have the script read `System.getenv("VAR")` directly. (This project's `Create Parallel Suites` script already does this for `SOURCE_SUITE` and `NUM_PARTITIONS`.)

8. **Katalon `Data File` `dataSourceUrl` must be relative with `isInternalPath: true`** for the project to be portable across machines. Absolute paths like `/Users/...` or `C:\...` will fail on the worker VMs.

9. **Use `-projectPath="$(pwd)/..."` (bash) or `-projectPath="$PWD\..."` (PowerShell)**, not just a relative path. KRE's project resolution behaves more reliably with an absolute path on HyperExecute workers.

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
