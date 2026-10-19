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

        // KMK v0.8.24: history and read chapters of every confirmed target feed one seed decision
        assertTrue(text.contains("val seed = LocalTrackingHistoryProgressPolicy.seed("))
        assertTrue(text.contains("targets.map { target ->"))
        assertTrue(text.contains("history = getHistory.await(target.id),"))
        assertTrue(text.contains("chapters = getChaptersByMangaId.await(target.id),"))
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
