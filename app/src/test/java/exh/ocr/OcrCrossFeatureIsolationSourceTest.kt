package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OcrCrossFeatureIsolationSourceTest {

    @Test
    fun `ocr entry points do not reference the known cross feature entry points`() {
        val sources = listOf(
            source("app/src/main/java/exh/ocr/OcrSearchScreen.kt"),
            source("app/src/main/java/exh/ocr/OcrSearchScreenModel.kt"),
            source("app/src/main/java/exh/ocr/OcrIndexWorker.kt"),
        ).joinToString("\n")

        // This is an explicit source guard for the current integration boundary, not a proof that
        // no future API could create an indirect runtime dependency.
        listOf(
            "RecordLocalTrackedChapterProgress",
            "RecommendsScreenModel",
            "BrowsePersonalRecommendations",
            "TrackInfoDialog",
            "RatingPropagation",
        ).forEach { forbiddenReference ->
            assertTrue(
                !sources.contains(forbiddenReference),
                "OCR entry points must not directly reference $forbiddenReference",
            )
        }
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
