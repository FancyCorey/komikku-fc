package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Guards group tracking against dropping progress recorded on another confirmed version. */
class LocalTrackingGroupHistorySourceTest {

    @Test
    fun `new shared local work aggregates history across confirmed targets`() {
        val text = source("app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt")

        assertTrue(text.contains("val sharedHistoryProgress = targets"))
        assertTrue(text.contains(".maxByOrNull { it.second.progressAt }"))
        // KMK v0.8.24: the seed now prefers history and falls back to read chapters
        assertTrue(text.contains("val seedChapter = sharedLatestRead?.third ?: sharedReadMark?.second"))
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
