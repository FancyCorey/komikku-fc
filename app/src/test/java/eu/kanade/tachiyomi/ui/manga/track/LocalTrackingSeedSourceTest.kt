package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.24: starting local tracking on one linked version begins at the furthest chapter read on any linked version, even
 * when those chapters have no reading history (the user's case: chapters marked read on Asura Scans, local tracking started
 * on Atsumaru at 0), and records the version the chapter was read on as the source.
 */
class LocalTrackingSeedSourceTest {
    private val source = File("src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt").readText()
        .substringAfter("fun setLocalTrackingStatus(")
        .substringBefore("fun removeLocalTracking(")

    @Test
    fun `a new shared work falls back to the furthest read chapter of any linked version`() {
        assertTrue(source.contains("val sharedReadMark = if (sharedLatestRead == null)"))
        assertTrue(source.contains(".filter { it.read && it.isRecognizedNumber }"))
        assertTrue(source.contains("val seedChapter = sharedLatestRead?.third ?: sharedReadMark?.second"))
    }

    @Test
    fun `the seed source is the version the chapter was read on, not the loop target`() {
        assertTrue(source.contains("lastChapterSource = seedChapter?.let { seedSource }"))
        assertFalse(source.contains("lastChapterSource = latestRead?.second?.let { target.source }"))
    }

    @Test
    fun `chapter modification seconds are converted to milliseconds`() {
        assertTrue(source.contains("chapter.lastModifiedAt * 1000"))
    }
}
