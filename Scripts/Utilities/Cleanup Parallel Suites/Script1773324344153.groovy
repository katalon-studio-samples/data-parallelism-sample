
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

def projectDir = RunConfiguration.getProjectDir()

// --- Validate inputs ---
if (!generatedFolder) {
	KeywordUtil.markFailedAndStop('generatedFolder is required (e.g., "Test Suites/Generated")')
}

def folder = new File(projectDir, generatedFolder)
if (!folder.exists() || !folder.isDirectory()) {
	KeywordUtil.markFailedAndStop("Folder not found: ${folder.absolutePath}")
}

// --- Delete all generated .ts and .groovy files ---
def deletedFiles = []
folder.eachFile { file ->
	if (file.name.endsWith('.ts') || file.name.endsWith('.groovy')) {
		println("Deleting: ${generatedFolder}/${file.name}")
		file.delete()
		deletedFiles << file.name
	}
}

// Remove folder if now empty
if (folder.exists() && folder.list().length == 0) {
	folder.delete()
	println("Removed empty folder: ${generatedFolder}")
}

println("\nCleanup complete. Deleted ${deletedFiles.size()} files.")
