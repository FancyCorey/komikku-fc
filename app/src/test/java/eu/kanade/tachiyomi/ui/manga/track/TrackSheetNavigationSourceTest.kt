package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.24: a linked version opened from the local-tracking versions list opens as a full page, not inside the sheet. */
class TrackSheetNavigationSourceTest {
    private val source = File("src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt").readText()

    @Test
    fun `a version from the local tracking list opens in the app navigator`() {
        val block = source.substringAfter("LocalTrackingVersionsDialog(").substringBefore("onProgressSharingChange")
        assertTrue(block.contains("(navigator.parent ?: navigator).push(MangaScreen(versionId, true))"))
        assertFalse(block.contains("\n                    navigator.push(MangaScreen(versionId, true))"))
    }
}
