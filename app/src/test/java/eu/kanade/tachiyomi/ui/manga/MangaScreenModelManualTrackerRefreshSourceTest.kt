package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK --> v0.8.24: a manual pull on the manga page always uploads newer local progress; marking local chapters from
// the remote record stays behind "Sync reading progress from connected trackers".
class MangaScreenModelManualTrackerRefreshSourceTest {

    private val source = File("src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt")
        .readText()
        .replace("\r\n", "\n")

    private val syncTrackersBody = source
        .substringAfter("private suspend fun syncTrackers(manualRefresh: Boolean = false)")
        .substringBefore("recordLocalTrackedChapterProgress.synchronize()")

    @Test
    fun `manual pull takes the upload-only branch only when sync from trackers is off`() {
        val full = syncTrackersBody.indexOf("refreshTrackers(enhancedTrackersOnly = false)")
        val manual = syncTrackersBody.indexOf("else if (manualRefresh)")
        val uploadOnly = syncTrackersBody.indexOf("refreshTrackers(enhancedTrackersOnly = false, uploadOnly = true)")

        assertTrue(syncTrackersBody.contains("autoSyncProgressFromTrackers().get()"))
        assertTrue(full >= 0 && manual > full && uploadOnly > manual, "setting-on refresh first, then manual upload-only")
    }

    @Test
    fun `only the public refresh passes its manual flag and the load path does not upload`() {
        assertTrue(source.contains("syncTrackers(manualRefresh = manualFetch)"))
        assertEquals(
            1,
            Regex("""syncTrackers\(\)""").findAll(source).count(),
            "exactly one no-argument call (the load path), which must never take the manual upload-only branch",
        )
    }

    @Test
    fun `refreshTrackers forwards uploadOnly to RefreshTracks`() {
        assertTrue(
            source.contains("refreshTracks.await(mangaId, enhancedTrackersOnly = enhancedTrackersOnly, uploadOnly = uploadOnly)"),
        )
    }

    @Test
    fun `opening or refreshing a manga gathers its linked versions into one local tracking record before replay`() {
        val sync = source.substringAfter("private suspend fun syncTrackers(").substringBefore("fun fetchAllFromSource(")
        val gather = sync.indexOf("propagateConfirmedLocalTracking(it)")
        val replay = sync.indexOf("recordLocalTrackedChapterProgress.synchronize()")
        assertTrue(gather in 0 until replay, "linked versions must be gathered before local progress is replayed")
    }
}
// KMK <--
