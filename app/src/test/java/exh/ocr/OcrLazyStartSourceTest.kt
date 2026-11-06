package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.25: ML Kit is only for OCR search, so it starts when OCR is first used and not at every app launch. */
class OcrLazyStartSourceTest {

    @Test
    fun `the manifest removes ML Kit's start-up provider`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("com.google.mlkit.common.internal.MlKitInitProvider")
            .substringBefore("/>")
        assertTrue(provider.contains("tools:node=\"remove\""), "MlKitInitProvider must be removed from the merged manifest")
    }

    @Test
    fun `the recognizer starts ML Kit before it creates its client`() {
        val source = File("src/main/java/exh/ocr/OcrTextRecognizer.kt").readText()
        val start = source.indexOf("MlKitContext.initializeIfNeeded(")
        val client = source.indexOf("TextRecognition.getClient(")
        assertTrue(start in 1 until client, "initialize ML Kit, then create the client")
    }

    @Test
    fun `nothing else in the app starts ML Kit or the recognizer at launch`() {
        val others = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/exh/ocr/") }
            .filter { it.readText().contains("com.google.mlkit") }
            .map { it.name }
            .toList()
        assertTrue(others.isEmpty(), "ML Kit must only be referenced from exh/ocr, found in $others")
        val app = File("src/main/java/eu/kanade/tachiyomi/App.kt").readText()
        assertTrue(!app.contains("MlKit") && !app.contains("OcrTextRecognizer"))
    }
}
