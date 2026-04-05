# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Katalon Studio** project (v11.0.0 Enterprise, WEBUI type) that provides a utility for parallelizing data-driven test suites. It partitions a single data-bound test suite into N slices and generates a parallel test suite collection, enabling concurrent execution across multiple browser instances.

## Architecture

The core logic lives in two Groovy scripts under `Scripts/`:

- **`Scripts/Utilities/Create Parallel Suites/`** — The main utility. Reads a source `.ts` (test suite XML), counts data rows via `findTestData()`, calculates balanced row ranges, and generates N partitioned `.ts` files plus a `TestSuiteCollectionEntity` XML for parallel execution. All output goes to a `Generated/` subfolder.
- **`Scripts/Utilities/Cleanup Parallel Suites/`** — Deletes generated `.ts` and `.groovy` files from the output folder.
- **`Scripts/Print Names/`** — Sample test case that prints data-bound variables (`firstName`, `lastName`, `email`).

Key file types:
- `.ts` — XML files defining test suites (`TestSuiteEntity`) and test suite collections (`TestSuiteCollectionEntity`). These contain data bindings, iteration ranges, and execution configuration.
- `.tc` — XML files defining test cases with variable declarations.
- `.groovy` — Companion scripts for both test cases (in `Scripts/`) and test suites (lifecycle hooks).
- `.dat` — Katalon internal test data definitions pointing to source files (e.g., CSV).

## How Partitioning Works

The Create Parallel Suites script:
1. Parses source suite XML with `XmlSlurper` to extract data binding info
2. Uses `findTestData()` API to get actual row counts (works with any data source type)
3. Distributes rows evenly — earlier partitions get +1 row when there's a remainder
4. Performs text-based XML manipulation (regex replacements) on the source XML to set `RANGE` iteration type and row ranges per partition
5. Generates new GUIDs for each partitioned suite and data link
6. Builds a collection XML with `PARALLEL` execution mode

## Running the Utilities

These are Katalon test cases, run from Katalon Studio UI or via `katalonc` CLI:

```bash
# Generate parallel suites
katalonc -noSplash -runMode=console \
  -projectPath="$(pwd)/data-parallelism.prj" \
  -testSuitePath="Test Suites/Utilities/Create Parallel Suites" \
  -apiKey="$KRE_API_KEY" \
  -g_sourceTestSuiteId="Test Suites/Print Names" \
  -g_numberOfPartitions="3"

# Cleanup generated files
katalonc -noSplash -runMode=console \
  -projectPath="$(pwd)/data-parallelism.prj" \
  -testSuitePath="Test Suites/Utilities/Cleanup Parallel Suites" \
  -apiKey="$KRE_API_KEY" \
  -g_generatedFolder="Test Suites/Generated"
```

Variables are passed via `-g_<variableName>` flags in CLI mode.

## Cloud Scaling

See `HYPEREXECUTE_INTEGRATION.md` for integration with LambdaTest HyperExecute to distribute partitions across cloud VMs. The recommended approach (Option B) uses `globalPre` to generate partitions in the cloud, then auto-split to distribute them.

## Conventions

- Generated files go to `Test Suites/Generated/` — never modify source suites
- Test suite XML uses Katalon's `TestSuiteEntity` schema with `iterationEntity` blocks controlling row ranges (`ALL` vs `RANGE` type)
- The `Libs/` folder contains auto-generated temporary test case classes — these are Katalon build artifacts, not hand-written code
- Execution profiles live in `Profiles/` as `.glbl` XML files
