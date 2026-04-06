
import static com.kms.katalon.core.testdata.TestDataFactory.findTestData
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import groovy.xml.XmlUtil

def projectDir = RunConfiguration.getProjectDir()

// Allow OS env vars to override test case parameters. This lets CI systems
// (e.g., HyperExecute) drive the utility without Katalon GlobalVariable plumbing.
def sourceTestSuiteIdResolved = System.getenv('SOURCE_SUITE') ?: sourceTestSuiteId
def numberOfPartitionsResolved = System.getenv('NUM_PARTITIONS') ?: numberOfPartitions

// --- Validate inputs ---
if (!sourceTestSuiteIdResolved) {
	KeywordUtil.markFailedAndStop('sourceTestSuiteId is required (e.g., "Test Suites/Print Names 1")')
}
def sourceTestSuiteId = sourceTestSuiteIdResolved
int numPartitions = (numberOfPartitionsResolved ?: 2) as int
if (numPartitions < 1) {
	KeywordUtil.markFailedAndStop('numberOfPartitions must be >= 1')
}
def browserVal = browser ?: 'Chrome'
def profileVal = profileName ?: 'default'

// Derive output folder: default to a "Generated" subfolder next to the source suite
def sourceFolderPath = sourceTestSuiteId.contains('/') ? sourceTestSuiteId.substring(0, sourceTestSuiteId.lastIndexOf('/')) : 'Test Suites'
def outFolder = outputFolder ?: "${sourceFolderPath}/Generated"

// --- 1. Read source test suite XML as text ---
def sourceFile = new File(projectDir, "${sourceTestSuiteId}.ts")
if (!sourceFile.exists()) {
	KeywordUtil.markFailedAndStop("Source test suite file not found: ${sourceFile.absolutePath}")
}

def sourceXml = sourceFile.text

// Parse with XmlSlurper to extract info (non-destructive)
def slurper = new XmlSlurper().parseText(sourceXml)
def sourceBaseName = slurper.name.text()
println("Source suite: ${sourceBaseName}")
println("Partitions: ${numPartitions}")

// --- 2. Determine total row count for each bound test data source ---
def testDataRowCounts = [:] as LinkedHashMap
slurper.testCaseLink.each { tcLink ->
	tcLink.testDataLink.each { tdLink ->
		def testDataId = tdLink.testDataId.text()
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
}

if (testDataRowCounts.isEmpty()) {
	KeywordUtil.markFailedAndStop('No data bindings found in source test suite')
}

// --- 3. Calculate partition ranges ---
def calculateRanges = { int totalRows, int partitions ->
	def ranges = []
	int base = totalRows.intdiv(partitions)
	int remainder = totalRows % partitions
	int start = 1
	for (int i = 0; i < partitions; i++) {
		int size = base + (i < remainder ? 1 : 0)
		int end = start + size - 1
		ranges << "${start}-${end}"
		start = end + 1
	}
	return ranges
}

def rangesPerData = [:]
testDataRowCounts.each { testDataId, rowCount ->
	rangesPerData[testDataId] = calculateRanges(rowCount, numPartitions)
	println("  Ranges for '${testDataId}': ${rangesPerData[testDataId]}")
}

// --- 4. Collect data link info from source for text replacement ---
// Build a map of old testDataLink IDs to their test data IDs
def dataLinkInfo = [] // list of [oldLinkId, testDataId]
slurper.testCaseLink.each { tcLink ->
	tcLink.testDataLink.each { tdLink ->
		dataLinkInfo << [oldLinkId: tdLink.id.text(), testDataId: tdLink.testDataId.text()]
	}
}

// --- 5. Create N partitioned test suites via text replacement ---
def generatedSuiteIds = []
def outDir = new File(projectDir, outFolder)
outDir.mkdirs()

for (int i = 0; i < numPartitions; i++) {
	def partNum = i + 1
	def newSuiteName = "${sourceBaseName} - Partition ${partNum}"
	def newSuiteId = "${outFolder}/${newSuiteName}"

	// Start from a fresh copy of the source XML text
	def newXml = sourceXml

	// Replace suite name
	newXml = newXml.replaceFirst(
		"<name>${java.util.regex.Pattern.quote(sourceBaseName)}</name>",
		"<name>${newSuiteName}</name>"
	)

	// Replace suite GUID
	def oldGuid = slurper.testSuiteGuid.text()
	newXml = newXml.replace(
		"<testSuiteGuid>${oldGuid}</testSuiteGuid>",
		"<testSuiteGuid>${UUID.randomUUID().toString()}</testSuiteGuid>"
	)

	// For each data link, replace the ID and set the iteration range
	dataLinkInfo.each { info ->
		def newLinkId = UUID.randomUUID().toString()
		def rangeStr = rangesPerData[info.testDataId][i]

		// Replace the data link ID everywhere it appears (in <id> and <testDataLinkId> elements)
		newXml = newXml.replace(info.oldLinkId, newLinkId)

		// Replace the iteration range value
		// Find the iterationEntity block for this data link and set the range
		// Since we may have RANGE or ALL, replace the entire iterationEntity content
		newXml = newXml.replaceAll(
			"(?s)<iterationEntity>\\s*<iterationType>[^<]*</iterationType>\\s*<value>[^<]*</value>\\s*</iterationEntity>",
			"<iterationEntity>\n            <iterationType>RANGE</iterationType>\n            <value>${rangeStr}</value>\n         </iterationEntity>"
		)
	}

	// Write .ts file
	def tsFile = new File(projectDir, "${newSuiteId}.ts")
	tsFile.parentFile.mkdirs()
	tsFile.text = newXml

	// Write companion .groovy file
	def groovyFile = new File(projectDir, "${newSuiteId}.groovy")
	groovyFile.text = """import com.kms.katalon.core.annotation.SetUp
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
"""

	generatedSuiteIds << newSuiteId
	println("Created: ${newSuiteId} (rows ${rangesPerData[testDataRowCounts.keySet()[0]][i]})")
}

// --- 6. Generate test suite collection ---
def collName = collectionName ?: "${sourceBaseName} - Parallel Collection"
def collectionId = "${outFolder}/${collName}"
def collectionFile = new File(projectDir, "${collectionId}.ts")
collectionFile.parentFile.mkdirs()

def xml = new StringBuilder()
xml.append('<?xml version="1.0" encoding="UTF-8"?>\n')
xml.append('<TestSuiteCollectionEntity>\n')
xml.append('   <description></description>\n')
xml.append("   <name>${collName}</name>\n")
xml.append('   <tag></tag>\n')
xml.append('   <delayBetweenInstances>0</delayBetweenInstances>\n')
xml.append('   <executionMode>PARALLEL</executionMode>\n')
xml.append("   <maxConcurrentInstances>${numPartitions}</maxConcurrentInstances>\n")
xml.append('   <testSuiteRunConfigurations>\n')

generatedSuiteIds.each { suiteId ->
	xml.append('      <TestSuiteRunConfiguration>\n')
	xml.append('         <configuration>\n')
	xml.append('            <groupName>Web Desktop</groupName>\n')
	xml.append("            <profileName>${profileVal}</profileName>\n")
	xml.append('            <requireConfigurationData>false</requireConfigurationData>\n')
	xml.append("            <runConfigurationId>${browserVal}</runConfigurationId>\n")
	xml.append('         </configuration>\n')
	xml.append('         <runEnabled>true</runEnabled>\n')
	xml.append("         <testSuiteEntity>${suiteId}</testSuiteEntity>\n")
	xml.append('      </TestSuiteRunConfiguration>\n')
}

xml.append('   </testSuiteRunConfigurations>\n')
xml.append('</TestSuiteCollectionEntity>\n')

collectionFile.text = xml.toString()

println("\n--- Summary ---")
println("Generated ${numPartitions} partitioned test suites in: ${outFolder}/")
generatedSuiteIds.eachWithIndex { id, idx ->
	println("  ${idx + 1}. ${id}")
}
println("Collection: ${collectionId}")
println("Execution mode: PARALLEL (max ${numPartitions} concurrent)")
println("Browser: ${browserVal}, Profile: ${profileVal}")
