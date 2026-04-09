
import com.kms.katalon.core.util.KeywordUtil
import internal.GlobalVariable as GlobalVariable

// Allow OS env vars to override test case parameters. This lets CI systems
// (e.g., HyperExecute) drive the utility without Katalon GlobalVariable plumbing.
def sourceTestSuiteIdResolved = System.getenv('SOURCE_SUITE') ?: sourceTestSuiteId
def numberOfPartitionsResolved = System.getenv('NUM_PARTITIONS') ?: numberOfPartitions

if (!sourceTestSuiteIdResolved) {
	KeywordUtil.markFailedAndStop('sourceTestSuiteId is required (e.g., "Test Suites/Print Names")')
}
int numPartitions = (numberOfPartitionsResolved ?: 2) as int

CustomKeywords.'com.katalon.dataparallelism.ParallelSuiteKeywords.createParallelSuites'(
	sourceTestSuiteIdResolved as String,
	numPartitions,
	outputFolder as String,
	collectionName as String,
	(browser ?: 'Chrome') as String,
	(profileName ?: 'default') as String
)
