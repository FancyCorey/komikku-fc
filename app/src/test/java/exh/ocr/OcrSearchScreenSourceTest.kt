package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OcrSearchScreenSourceTest {

    @Test
    fun `ocr error snackbar keeps retry and dismissal actions typed`() {
        val source = source("app/src/main/java/exh/ocr/OcrSearchScreen.kt")
        val errorBlock = source.substringAfter("state.errorKey?.let { key ->")
            .substringBefore("@Composable\nprivate fun MaxPagesRow")

        assertTrue(errorBlock.contains("OcrSearchScreenModel.ErrorAction.RETRY_SEARCH"))
        assertTrue(errorBlock.contains("screenModel::retrySearch"))
        assertTrue(errorBlock.contains("screenModel::clearError"))
        assertTrue(errorBlock.contains("ocrErrorMessageRes(key)"))
        assertTrue(!errorBlock.contains("key.message"))
        assertTrue(!errorBlock.contains("exception.message"))
    }

    private fun source(relativePath: String): String {
        val direct = Path.of(relativePath)
        val fromParent = Path.of("..").resolve(relativePath)
        val path = listOf(direct, fromParent)
            .map(Path::toAbsolutePath)
            .firstOrNull(Files::isRegularFile)
            ?: error("Unable to resolve source file: $relativePath")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
