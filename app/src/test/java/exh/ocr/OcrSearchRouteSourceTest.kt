package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OcrSearchRouteSourceTest {

    @Test
    fun `the production OCR action opens the search screen through the main navigator`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt")
        assertTrue(source.contains("Constants.OPEN_OCR_SEARCH ->"))
        val route = source.substringAfter("Constants.OPEN_OCR_SEARCH ->")
            .substringBefore("Intent.ACTION_SEARCH")

        assertTrue(route.contains("navigator.popUntilRoot()"))
        assertTrue(route.contains("navigator.push(exh.ocr.OcrSearchScreen())"))
        assertTrue(route.contains("\n                null\n"))
    }

    @Test
    fun `the production OCR action does not introduce a second screen implementation`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt")
        assertTrue(source.contains("Constants.OPEN_OCR_SEARCH ->"))
        val route = source.substringAfter("Constants.OPEN_OCR_SEARCH ->")
            .substringBefore("Intent.ACTION_SEARCH")

        assertTrue(!route.contains("OcrReaderActivity"))
        assertTrue(!route.contains("OcrReaderScreen"))
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
