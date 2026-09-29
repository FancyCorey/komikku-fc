package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OcrReaderEntrySourceTest {

    @Test
    fun `ocr results open the existing reader with manga chapter and page inputs`() {
        val text = source("app/src/main/java/exh/ocr/OcrSearchScreen.kt")
        val cardBlock = text.substringAfter("OcrResultCard(").substringBefore("onClearChapter =")
        val readerCall = cardBlock.substringAfter("ReaderActivity.newIntent(").substringBefore(")")

        assertTrue(text.countOccurrences("ReaderActivity.newIntent(") == 1)
        assertTrue(readerCall.contains("result.mangaId"))
        assertTrue(readerCall.contains("result.chapterId"))
        assertTrue(readerCall.contains("result.pageIndex"))
        assertTrue(cardBlock.contains("context.startActivity(intent)"))
    }

    @Test
    fun `ocr search screen does not introduce a second reader entry point`() {
        val text = source("app/src/main/java/exh/ocr/OcrSearchScreen.kt")

        assertTrue(text.countOccurrences("ReaderActivity.newIntent(") == 1)
        assertTrue(!text.contains("OcrReaderActivity"))
        assertTrue(!text.contains("OcrReaderScreen"))
    }

    @Test
    fun `ocr reader handoff preserves the search activity for ordinary back`() {
        val text = source("app/src/main/java/exh/ocr/OcrSearchScreen.kt")
        val cardBlock = text.substringAfter("OcrResultCard(").substringBefore("onClearChapter =")

        assertTrue(cardBlock.contains("context.startActivity(intent)"))
        assertTrue(!cardBlock.contains("FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(!cardBlock.contains("FLAG_ACTIVITY_NEW_TASK"))
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

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
