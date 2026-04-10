import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlUtil

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

/**
 * Rewrites `Data Files/User Data - SQLite.dat` from the committed
 * `User Data Template - SQLite.dat` by substituting __PROJECT_DIR__ with
 * the current project's absolute path.
 *
 * The .dat file is .gitignored — each machine materializes its own copy. Run this
 * once after cloning, or wire it into a suite @SetUp on HyperExecute workers.
 *
 * Forward slashes are used even on Windows: SQLite JDBC accepts them and it avoids
 * backslash escaping in the XML.
 */

String projectDir = RunConfiguration.getProjectDir().replace('\\', '/')
Path template = Paths.get(projectDir, 'Data Files', 'User Data Template - SQLite.dat')
Path target   = Paths.get(projectDir, 'Data Files', 'User Data - SQLite.dat')

if (!Files.exists(template)) {
	KeywordUtil.markFailedAndStop("Template not found: ${template}")
}

def dat = new XmlSlurper().parse(template.toFile())
dat.name[0].replaceBody('User Data - SQLite')
dat.dataSourceUrl[0].replaceBody(
	dat.dataSourceUrl.text().replace('__PROJECT_DIR__', projectDir)
)

def rendered = XmlUtil.serialize(new StreamingMarkupBuilder().bind { mkp.yield dat })
Files.write(target, rendered.getBytes('UTF-8'))
KeywordUtil.logInfo("Wrote ${target} with projectDir=${projectDir}")
