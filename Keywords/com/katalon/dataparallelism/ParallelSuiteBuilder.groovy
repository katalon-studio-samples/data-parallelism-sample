package com.katalon.dataparallelism

import java.util.regex.Pattern

/**
 * Pure logic for partitioning a data-bound Katalon test suite into N slices
 * and generating a parallel TestSuiteCollectionEntity.
 *
 * Deliberately free of any com.kms.katalon.* dependencies so it can be
 * unit-tested in isolation.
 */
class ParallelSuiteBuilder {

	static final String COMPANION_GROOVY = '''import com.kms.katalon.core.annotation.SetUp
import com.kms.katalon.core.annotation.SetupTestCase
import com.kms.katalon.core.annotation.TearDown
import com.kms.katalon.core.annotation.TearDownTestCase

@SetUp(skipped = true)
def setUp() {
}

@TearDown(skipped = true)
def tearDown() {
}

@SetupTestCase(skipped = true)
def setupTestCase() {
}

@TearDownTestCase(skipped = true)
def tearDownTestCase() {
}
'''

	/**
	 * Distributes totalRows across partitions, giving earlier partitions
	 * the extra row when there is a remainder. Returns a list of "start-end"
	 * strings using 1-based inclusive indexing.
	 */
	static List<String> calculateRanges(int totalRows, int partitions) {
		def ranges = []
		int base = totalRows.intdiv(partitions)
		int remainder = totalRows % partitions
		int start = 1
		for (int i = 0; i < partitions; i++) {
			int size = base + (i < remainder ? 1 : 0)
			int end = start + size - 1
			ranges << "${start}-${end}".toString()
			start = end + 1
		}
		return ranges
	}

	/**
	 * Parses a test suite XML and returns:
	 *   [ baseName: String,
	 *     suiteGuid: String,
	 *     dataLinks: [ [oldLinkId, testDataId], ... ] ]
	 */
	static Map parseSuite(String sourceXml) {
		def slurper = new XmlSlurper().parseText(sourceXml)
		def dataLinks = []
		slurper.testCaseLink.each { tcLink ->
			tcLink.testDataLink.each { tdLink ->
				dataLinks << [oldLinkId: tdLink.id.text(), testDataId: tdLink.testDataId.text()]
			}
		}
		return [
			baseName : slurper.name.text(),
			suiteGuid: slurper.testSuiteGuid.text(),
			dataLinks: dataLinks
		]
	}

	/**
	 * Produces the XML text for a single partition suite.
	 *
	 * @param sourceXml       the original suite XML
	 * @param sourceBaseName  the source suite's <name>
	 * @param sourceGuid      the source suite's <testSuiteGuid>
	 * @param newSuiteName    target suite name
	 * @param dataLinks       list of [oldLinkId, testDataId] maps
	 * @param rangesPerData   map of testDataId -> List<String> of ranges
	 * @param partitionIndex  0-based partition index
	 */
	static String buildPartitionXml(String sourceXml, String sourceBaseName, String sourceGuid,
	                                String newSuiteName, List dataLinks,
	                                Map<String, List<String>> rangesPerData, int partitionIndex) {
		def newXml = sourceXml

		newXml = newXml.replaceFirst(
			"<name>${Pattern.quote(sourceBaseName)}</name>",
			"<name>${newSuiteName}</name>"
		)

		newXml = newXml.replace(
			"<testSuiteGuid>${sourceGuid}</testSuiteGuid>",
			"<testSuiteGuid>${UUID.randomUUID().toString()}</testSuiteGuid>"
		)

		dataLinks.each { info ->
			def newLinkId = UUID.randomUUID().toString()
			def rangeStr = rangesPerData[info.testDataId][partitionIndex]
			newXml = newXml.replace(info.oldLinkId, newLinkId)
			newXml = newXml.replaceAll(
				"(?s)<iterationEntity>\\s*<iterationType>[^<]*</iterationType>\\s*<value>[^<]*</value>\\s*</iterationEntity>",
				"<iterationEntity>\n            <iterationType>RANGE</iterationType>\n            <value>${rangeStr}</value>\n         </iterationEntity>"
			)
		}

		return newXml
	}

	/**
	 * Builds a TestSuiteCollectionEntity XML that runs the given suite IDs in PARALLEL.
	 */
	static String buildCollectionXml(String collName, List<String> suiteIds, String browser, String profileName) {
		def xml = new StringBuilder()
		xml.append('<?xml version="1.0" encoding="UTF-8"?>\n')
		xml.append('<TestSuiteCollectionEntity>\n')
		xml.append('   <description></description>\n')
		xml.append("   <name>${collName}</name>\n")
		xml.append('   <tag></tag>\n')
		xml.append('   <delayBetweenInstances>0</delayBetweenInstances>\n')
		xml.append('   <executionMode>PARALLEL</executionMode>\n')
		xml.append("   <maxConcurrentInstances>${suiteIds.size()}</maxConcurrentInstances>\n")
		xml.append('   <testSuiteRunConfigurations>\n')
		suiteIds.each { suiteId ->
			xml.append('      <TestSuiteRunConfiguration>\n')
			xml.append('         <configuration>\n')
			xml.append('            <groupName>Web Desktop</groupName>\n')
			xml.append("            <profileName>${profileName}</profileName>\n")
			xml.append('            <requireConfigurationData>false</requireConfigurationData>\n')
			xml.append("            <runConfigurationId>${browser}</runConfigurationId>\n")
			xml.append('         </configuration>\n')
			xml.append('         <runEnabled>true</runEnabled>\n')
			xml.append("         <testSuiteEntity>${suiteId}</testSuiteEntity>\n")
			xml.append('      </TestSuiteRunConfiguration>\n')
		}
		xml.append('   </testSuiteRunConfigurations>\n')
		xml.append('</TestSuiteCollectionEntity>\n')
		return xml.toString()
	}

	/**
	 * Deletes all .ts and .groovy files directly inside folder and removes the
	 * folder if it becomes empty. Returns the list of deleted file names.
	 */
	static List<String> cleanupFolder(File folder) {
		def deleted = []
		folder.eachFile { file ->
			if (file.name.endsWith('.ts') || file.name.endsWith('.groovy')) {
				file.delete()
				deleted << file.name
			}
		}
		if (folder.exists() && folder.list().length == 0) {
			folder.delete()
		}
		return deleted
	}
}
