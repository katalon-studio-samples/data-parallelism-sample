# Data Parallelism for Katalon Studio — Demo Project

Demo project for the [Data Parallelism](https://store.katalon.com/product/486/Data-Parallelism) plugin. It shows how to use the plugin to partition a data-driven test suite into N slices and run them in parallel, reducing execution time proportionally.

The plugin was inspired by [kmohit3021/katalon-databinding-parallelexecution](https://github.com/kmohit3021/katalon-databinding-parallelexecution), which explored the same idea of splitting a data-bound Katalon test suite for parallel execution.

## The Problem

Katalon's test suite data binding runs rows sequentially. A suite with 100 rows of login data takes 100× the time of a single iteration — even when you have capacity to run multiple browser instances.

## How It Works

```
        Source Test Suite (10 rows)
                  │
                  ▼
     "Create Parallel Suites" (N=3)
        ┌─────────┼─────────┐
        ▼         ▼         ▼
   Partition 1  Partition 2  Partition 3
    rows 1–4    rows 5–7    rows 8–10
        │         │         │
        └─────────┼─────────┘
                  ▼
         Parallel Collection
       (3 concurrent instances)
```

The plugin reads your source test suite, counts the bound data rows (using Katalon's `findTestData()` API — works with any data source type), divides them into balanced ranges, and generates:

- **N partitioned `.ts` files** — each identical to the source but scoped to a row range
- **A test suite collection** — configured for `PARALLEL` execution with N concurrent instances

All generated files go into a separate `Generated/` folder. The originals are never modified.

## Setup

Install the **Data Parallelism** plugin via one of:

- **Katalon Store** (recommended) — install from [store.katalon.com/product/486/Data-Parallelism](https://store.katalon.com/product/486/Data-Parallelism), then reload plugins in Katalon Studio.
- **Manual JAR** — place the plugin JAR in your project's `Plugins/` directory. To build it from source:

  ```bash
  cd "../katalon-data-parallelism"
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew katalonPluginPackage
  cp build/libs/katalon-data-parallelism-*-all.jar "../data-parallelism/Plugins/"
  ```

## Parameters

Run **Test Cases/Utilities/Create Parallel Suites** with these variables:

| Parameter | Default | Description |
|---|---|---|
| `sourceTestSuiteId` | `Test Suites/Print Names` | Path to the source test suite (Katalon format, no `.ts` extension) |
| `numberOfPartitions` | `3` | Number of parallel slices to create |
| `outputFolder` | `Test Suites/Generated` | Where to write generated files |
| `collectionName` | *(auto)* | Name for the collection; defaults to `<SuiteName> - Parallel Collection` |
| `browser` | `Chrome` | Browser for all partitions |
| `profileName` | `default` | Execution profile for all partitions |

## Usage

### 1. Prerequisites

You need a test suite that already has data binding configured:
- A test case with variables (e.g., `username`, `password`)
- A data file (CSV, Excel, internal — any type Katalon supports)
- A test suite that binds the data file to the test case variables

### 2. Generate parallel suites

1. Open **Test Cases/Utilities/Create Parallel Suites**
2. Set the variables (at minimum, set `sourceTestSuiteId` to your suite's path)
3. Run the test case

Console output:

```
Source suite: Print Names
Partitions: 3
  Data 'Data Files/User Data': 10 rows
  Ranges for 'Data Files/User Data': [1-4, 5-7, 8-10]
Created: Test Suites/Generated/Print Names - Partition 1 (rows 1-4)
Created: Test Suites/Generated/Print Names - Partition 2 (rows 5-7)
Created: Test Suites/Generated/Print Names - Partition 3 (rows 8-10)

--- Summary ---
Generated 3 partitioned test suites in: Test Suites/Generated/
  1. Test Suites/Generated/Print Names - Partition 1
  2. Test Suites/Generated/Print Names - Partition 2
  3. Test Suites/Generated/Print Names - Partition 3
Collection: Test Suites/Generated/Print Names - Parallel Collection
Execution mode: PARALLEL (max 3 concurrent)
Browser: Chrome, Profile: default
```

### 3. Run the collection

1. Refresh the project in Katalon Studio (right-click project → Refresh)
2. Open **Test Suites/Generated/Print Names - Parallel Collection**
3. Click **Execute**

Katalon launches 3 browser instances simultaneously, each running its partition.

### 4. Cleanup

Run **Test Cases/Utilities/Cleanup Parallel Suites** to delete all generated files:

| Parameter | Default | Description |
|---|---|---|
| `generatedFolder` | `Test Suites/Generated` | Folder to clean |

The folder itself is removed if empty after cleanup.

## Generated File Structure

```
Test Suites/
├── Print Names.ts                          ← original (unchanged)
├── Print Names.groovy                      ← original (unchanged)
└── Generated/
    ├── Print Names - Partition 1.ts        ← rows 1-4
    ├── Print Names - Partition 1.groovy
    ├── Print Names - Partition 2.ts        ← rows 5-7
    ├── Print Names - Partition 2.groovy
    ├── Print Names - Partition 3.ts        ← rows 8-10
    ├── Print Names - Partition 3.groovy
    └── Print Names - Parallel Collection.ts
```

## How Partitioning Works

Rows are distributed as evenly as possible. When rows don't divide evenly, earlier partitions get one extra row:

| Total Rows | Partitions | Distribution |
|---|---|---|
| 10 | 3 | 4, 3, 3 |
| 10 | 4 | 3, 3, 2, 2 |
| 7 | 3 | 3, 2, 2 |
| 100 | 5 | 20, 20, 20, 20, 20 |

If `numberOfPartitions` exceeds the row count, the partition count is clamped to the number of rows so every partition gets at least one row. A warning is logged and fewer suites are generated than requested:

| Total Rows | Requested Partitions | Actual Partitions | Distribution |
|---|---|---|---|
| 3 | 10 | 3 | 1, 1, 1 |
| 1 | 5 | 1 | 1 |

## Limitations

- **Single source suite**: Currently partitions one test suite at a time. To parallelize multiple suites, run the utility once per suite and build the collection manually, or combine the generated partitions.
- **Uniform config**: All partitions use the same browser and profile. Per-partition configuration (e.g., different browsers) requires manual editing of the generated collection.
- **Refresh required**: Katalon Studio needs a project refresh to see the generated files in the UI.
