package com.katalon.dataparallelism

import static com.kms.katalon.core.testdata.TestDataFactory.findTestData
import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

/**
 * Katalon-facing keywords for the Data Parallelism utility.
 *
 * Delegates all non-Katalon logic to {@link ParallelSuiteBuilder} so the
 * core splitting code stays unit-testable and free of runtime coupling.
 */
class ParallelSuiteKeywords {

	/**
	 * Partitions a data-bound test suite into N slices and writes a parallel
	 * TestSuiteCollectionEntity into outputFolder.
	 *
	 * @return the generated collection's test suite ID (path relative to project)
	 */
	@Keyword
	String createParallelSuites(String sourceTestSuiteId, int numberOfPartitions,
	                            String outputFolder = null, String collectionName = null,
	                            String browser = 'Chrome', String profileName = 'default') {

		if (!sourceTestSuiteId) {
			KeywordUtil.markFailedAndStop('sourceTestSuiteId is required (e.g., "Test Suites/Print Names")')
		}
		if (numberOfPartitions < 1) {
			KeywordUtil.markFailedAndStop('numberOfPartitions must be >= 1')
		}

		def projectDir = RunConfiguration.getProjectDir()
		def sourceFolderPath = sourceTestSuiteId.contains('/') ?
			sourceTestSuiteId.substring(0, sourceTestSuiteId.lastIndexOf('/')) : 'Test Suites'
		def outFolder = outputFolder ?: "${sourceFolderPath}/Generated"

		def sourceFile = new File(projectDir, "${sourceTestSuiteId}.ts")
		if (!sourceFile.exists()) {
			KeywordUtil.markFailedAndStop("Source test suite file not found: ${sourceFile.absolutePath}")
		}

		def sourceXml = sourceFile.text
		def parsed = ParallelSuiteBuilder.parseSuite(sourceXml)
		def sourceBaseName = parsed.baseName
		def sourceGuid = parsed.suiteGuid
		def dataLinks = parsed.dataLinks

		println("Source suite: ${sourceBaseName}")
		println("Partitions: ${numberOfPartitions}")

		if (dataLinks.isEmpty()) {
			KeywordUtil.markFailedAndStop('No data bindings found in source test suite')
		}

		// Resolve row counts via Katalon's test data factory
		def testDataRowCounts = [:] as LinkedHashMap
		dataLinks.each { info ->
			def testDataId = info.testDataId
			if (!testDataRowCounts.containsKey(testDataId)) {
				try {
					def testData = findTestData(testDataId)
					testDataRowCounts[testDataId] = testData.getRowNumbers()
					println("  Data '${testDataId}': ${testDataRowCounts[testDataId]} rows")
				} catch (Exception e) {
					KeywordUtil.markFailedAndStop("Cannot load test data '${testDataId}': ${e.message}")
				}
			}
		}

		def rangesPerData = [:]
		testDataRowCounts.each { testDataId, rowCount ->
			rangesPerData[testDataId] = ParallelSuiteBuilder.calculateRanges(rowCount as int, numberOfPartitions)
			println("  Ranges for '${testDataId}': ${rangesPerData[testDataId]}")
		}

		def outDir = new File(projectDir, outFolder)
		outDir.mkdirs()

		def generatedSuiteIds = []
		for (int i = 0; i < numberOfPartitions; i++) {
			def partNum = i + 1
			def newSuiteName = "${sourceBaseName} - Partition ${partNum}"
			def newSuiteId = "${outFolder}/${newSuiteName}"

			def newXml = ParallelSuiteBuilder.buildPartitionXml(
				sourceXml, sourceBaseName, sourceGuid, newSuiteName,
				dataLinks, rangesPerData, i)

			def tsFile = new File(projectDir, "${newSuiteId}.ts")
			tsFile.parentFile.mkdirs()
			tsFile.text = newXml

			def groovyFile = new File(projectDir, "${newSuiteId}.groovy")
			groovyFile.text = ParallelSuiteBuilder.COMPANION_GROOVY

			generatedSuiteIds << newSuiteId
			println("Created: ${newSuiteId} (rows ${rangesPerData[testDataRowCounts.keySet()[0]][i]})")
		}

		def collName = collectionName ?: "${sourceBaseName} - Parallel Collection"
		def collectionId = "${outFolder}/${collName}"
		def collectionFile = new File(projectDir, "${collectionId}.ts")
		collectionFile.parentFile.mkdirs()
		collectionFile.text = ParallelSuiteBuilder.buildCollectionXml(
			collName, generatedSuiteIds, browser ?: 'Chrome', profileName ?: 'default')

		println("\n--- Summary ---")
		println("Generated ${numberOfPartitions} partitioned test suites in: ${outFolder}/")
		generatedSuiteIds.eachWithIndex { id, idx ->
			println("  ${idx + 1}. ${id}")
		}
		println("Collection: ${collectionId}")
		println("Execution mode: PARALLEL (max ${numberOfPartitions} concurrent)")
		println("Browser: ${browser}, Profile: ${profileName}")

		return collectionId
	}

	/**
	 * Deletes all .ts and .groovy files directly inside generatedFolder and
	 * removes the folder if it becomes empty.
	 */
	@Keyword
	int cleanupParallelSuites(String generatedFolder) {
		if (!generatedFolder) {
			KeywordUtil.markFailedAndStop('generatedFolder is required (e.g., "Test Suites/Generated")')
		}
		def projectDir = RunConfiguration.getProjectDir()
		def folder = new File(projectDir, generatedFolder)
		if (!folder.exists() || !folder.isDirectory()) {
			KeywordUtil.markFailedAndStop("Folder not found: ${folder.absolutePath}")
		}

		def deleted = ParallelSuiteBuilder.cleanupFolder(folder)
		deleted.each { name -> println("Deleted: ${generatedFolder}/${name}") }
		if (!folder.exists()) {
			println("Removed empty folder: ${generatedFolder}")
		}
		println("\nCleanup complete. Deleted ${deleted.size()} files.")
		return deleted.size()
	}
}
