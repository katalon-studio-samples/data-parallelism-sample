import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import net.datafaker.Faker

def projectDir = RunConfiguration.getProjectDir()

// Allow OS env vars to override test case parameters (mirrors Create Parallel
// Suites — lets CI drive this without Katalon GlobalVariable plumbing).
def numRowsResolved = System.getenv('NUM_ROWS') ?: numRows
def outputPathResolved = System.getenv('OUTPUT_PATH') ?: outputPath

int rows = (numRowsResolved ?: 10) as int
if (rows < 1) {
	KeywordUtil.markFailedAndStop('numRows must be >= 1')
}
def outPath = outputPathResolved ?: 'Data Files/sample_users.csv'

def outFile = new File(projectDir, outPath)
outFile.parentFile.mkdirs()

def faker = new Faker()

outFile.withWriter('UTF-8') { writer ->
	writer.writeLine('firstName,lastName,email')
	rows.times {
		def first = faker.name().firstName()
		def last = faker.name().lastName()
		def email = "${first}.${last}@example.com".toLowerCase()
		// Naive CSV escaping: wrap in quotes if a comma or quote appears
		def cells = [first, last, email].collect { v ->
			(v.contains(',') || v.contains('"')) ? "\"${v.replace('"', '""')}\"" : v
		}
		writer.writeLine(cells.join(','))
	}
}

println("Generated ${rows} rows to ${outPath}")
