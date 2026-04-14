# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Katalon Studio** demo/test project for the `katalon-data-parallelism` plugin. It demonstrates how to use the plugin's keywords to partition a data-bound test suite into N slices and run them concurrently via a parallel test suite collection.

The plugin itself lives in a sibling project: `../katalon-data-parallelism/`.

## Architecture

This project **consumes** the data-parallelism plugin via a JAR in `Plugins/`. It contains no custom keywords of its own — all partitioning logic comes from the plugin.

### Scripts

- **`Scripts/Utilities/Create Parallel Suites/`** — Calls `ParallelSuiteKeywords.createParallelSuites()` from the plugin.
- **`Scripts/Utilities/Cleanup Parallel Suites/`** — Calls `ParallelSuiteKeywords.cleanupParallelSuites()` from the plugin.
- **`Scripts/Utilities/Generate Sample Users/`** — Generates CSV test data using DataFaker.
- **`Scripts/Utilities/Materialize SQLite Data File/`** — Materializes a machine-specific `.dat` file from a template.
- **`Scripts/Print Names/`** — Sample test case that prints data-bound variables (`firstName`, `lastName`, `email`).

### Plugin Dependency

The plugin JAR is at `Plugins/katalon-data-parallelism-0.1.0.jar`. To update it, rebuild from the plugin project:

```bash
cd "../katalon-data-parallelism"
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew katalonPluginPackage
cp build/libs/katalon-data-parallelism-*-all.jar "../data-parallelism/Plugins/katalon-data-parallelism-0.1.0.jar"
```

Key file types:
- `.ts` — XML files defining test suites (`TestSuiteEntity`) and test suite collections (`TestSuiteCollectionEntity`). These contain data bindings, iteration ranges, and execution configuration.
- `.tc` — XML files defining test cases with variable declarations.
- `.groovy` — Companion scripts for both test cases (in `Scripts/`) and test suites (lifecycle hooks).
- `.dat` — Katalon internal test data definitions pointing to source files (e.g., CSV).

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
