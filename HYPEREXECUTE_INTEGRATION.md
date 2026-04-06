# Scaling Data-Driven Katalon Tests with HyperExecute

How this project's data-partitioning utility runs on TestMu/LambdaTest HyperExecute to execute data-driven tests across cloud VMs.

## What This Project Does (Single Machine)

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

This is bottlenecked by local CPU and memory. A single machine can only support ~3–8 concurrent Katalon JVM instances.

## What HyperExecute Adds

HyperExecute is a cloud test orchestration platform that provisions dedicated VMs and distributes test execution across them. For this project we use:

- **Auto-split mode** — discovers the partitioned `.ts` files and distributes them across N VMs
- **Native Katalon runtime** — `runtime: katalon` installs KRE on each VM automatically
- **Co-located execution** — test code and headless Chrome run on the same VM

## How It Works

Each worker VM independently generates the partitioned suites in `pre`, then HyperExecute's auto-split discovers and distributes them across the workers.

```
Upload project ──► HyperExecute provisions N worker VMs
                            │
                ┌───────────┼───────────┐
                ▼           ▼           ▼
            Worker 1    Worker 2    Worker N
                │           │           │
              pre:        pre:        pre:
              katalonc    katalonc    katalonc
              Create      Create      Create
              Parallel    Parallel    Parallel
              Suites      Suites      Suites
                │           │           │
              discovery   discovery   discovery
              lists all   lists all   lists all
              partitions  partitions  partitions
                │           │           │
            auto-split assigns subset to each worker
                │           │           │
              katalonc    katalonc    katalonc
              runs        runs        runs
              partition X partition Y partition Z
```

**Why per-worker generation in `pre` rather than once in `globalPre`?** With a pinned Katalon runtime (`runtime.version`), worker VMs receive a fresh extraction of the original project upload — files written during `globalPre` do not propagate, even with `cache: true`. `pre` runs on the same VM as `testDiscovery` and `testRunnerCommand`, so generated files are guaranteed to be visible. The tradeoff is that every worker independently spends ~30–60s on `katalonc` JVM startup to generate partitions.

## Configuration

See `hyperexecute.yml` in the project root. Key settings:

```yaml
runson: win
runtime:
  language: katalon
  version: 11.0.1

env:
  SOURCE_SUITE: "Test Suites/Print Names"
  NUM_PARTITIONS: "10"
  KRE_API_KEY: ${{ .secrets.KRE_API_KEY }}

# Local headless Chrome on the worker never creates a LambdaTest session,
# so scenarios get marked "skipped" by default. This flag derives scenario
# status from katalonc's exit code instead.
scenarioCommandStatusOnly: true

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

# testRunnerCommand runs in PowerShell on Windows even with shell: bash.
testRunnerCommand: >-
  katalonc -noSplash -runMode=console
  -projectPath="$PWD\data-parallelism.prj"
  -testSuitePath="$test"
  -browserType="Chrome (headless)"
  -apiKey="$env:KRE_API_KEY"
  -executionProfile="default"
```

The `Create Parallel Suites` Groovy script reads `SOURCE_SUITE` and `NUM_PARTITIONS` from the OS environment via `System.getenv()`, so no Katalon `-g_` plumbing is needed.

## Platform Gotchas

These quirks bit us during integration. Keeping them here so they don't have to be rediscovered.

1. **Custom runtime breaks `globalPre` file propagation.** With `runtime.version` pinned, files written during `globalPre` do not reach worker VMs even with `cache: true`. Workers receive a fresh extraction of the original upload. Generate files in `pre` instead.

2. **`testRunnerCommand` is PowerShell on Windows**, regardless of `shell: bash`. Use `$env:VAR` for OS env vars, Windows-style paths, and `$PWD` (PowerShell) instead of `$(pwd)` (bash). All other phases (including `pre`, `globalPre`, `testDiscovery`, `post`) use bash via MSYS2 even on Windows.

3. **HyperExecute's `$test` template variable is substituted before the shell sees it.** It looks like a bash/PowerShell variable in the YAML but isn't — write `$test` as a literal in `testRunnerCommand` and it just works on both platforms.

4. **User-defined `env:` variables don't template-substitute in `testRunnerCommand`.** Only `$test` (the platform injection) does. For your own env vars, use `$env:VAR` (PowerShell) or `$(printenv VAR)` (bash).

5. **Top-level `runson: win` does NOT cascade to `globalPre`.** Set `runson: win` explicitly inside `globalPre` (and likely `globalPost`) when using a Windows-only custom runtime, or you'll get `Runtime Setup Failed: custom katalon is not supported for os linux`.

6. **"PARTIALLY COMPLETED" / scenarios marked "skipped" on success.** Happens when tests don't create LambdaTest sessions (e.g., local headless Chrome on the worker). Set `scenarioCommandStatusOnly: true` at the top level of the YAML to derive scenario status from the command's exit code instead.

7. **Katalon `-g_VAR` flags only override existing `GlobalVariable`s defined in a profile.** If the variable isn't pre-declared in `Profiles/*.glbl`, the flag is silently ignored. To pass values into a Test Case from the CLI without GlobalVariable plumbing, have the script read `System.getenv("VAR")` directly.

8. **Katalon `Data File` `dataSourceUrl` must be relative with `isInternalPath: true`** for the project to be portable across machines. Absolute paths like `/Users/...` or `C:\...` will fail on the worker VMs.

9. **Use `-projectPath="$(pwd)/..."` (bash) or `-projectPath="$PWD\..."` (PowerShell)**, not just a relative path. KRE's project resolution behaves more reliably with an absolute path on HyperExecute workers.

## Prerequisites

1. **LambdaTest account** with HyperExecute access
2. **Katalon Runtime Engine license** (one per concurrent VM)
3. **HyperExecute CLI** downloaded from LambdaTest
4. **Environment variables** set:
   - `LT_USERNAME` — LambdaTest username
   - `LT_ACCESS_KEY` — LambdaTest access key
   - `KRE_API_KEY` — Katalon API key for runtime engine activation

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
  --config hyperexecute.yml --validate

# 4. Run
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yml

# 5. Download results
./hyperexecute --user $LT_USERNAME --key $LT_ACCESS_KEY \
  --config hyperexecute.yml \
  --download-artifacts --download-artifacts-path ./results
```
