package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK --> v0.8.24 network review: automatic tracker refresh when a manga is opened
class TrackerOpenRefreshPolicyTest {

    private val interval = TrackerOpenRefreshPolicy.MIN_INTERVAL_MS

    @Test
    fun `manual pull always refreshes, even offline or right after another refresh`() {
        assertTrue(TrackerOpenRefreshPolicy.shouldRefresh(manualRefresh = true, online = false, lastRefreshAt = 1_000, now = 1_001))
    }

    @Test
    fun `automatic refresh is skipped offline`() {
        assertFalse(TrackerOpenRefreshPolicy.shouldRefresh(manualRefresh = false, online = false, lastRefreshAt = null, now = 0))
    }

    @Test
    fun `automatic refresh runs on first open and again only after the interval`() {
        val start = 10_000_000L
        assertTrue(TrackerOpenRefreshPolicy.shouldRefresh(false, true, lastRefreshAt = null, now = start))
        assertFalse(TrackerOpenRefreshPolicy.shouldRefresh(false, true, lastRefreshAt = start, now = start + interval - 1))
        assertTrue(TrackerOpenRefreshPolicy.shouldRefresh(false, true, lastRefreshAt = start, now = start + interval))
        // a clock moved backwards does not suppress refreshes
        assertTrue(TrackerOpenRefreshPolicy.shouldRefresh(false, true, lastRefreshAt = start, now = start - 1))
    }

    @Test
    fun `claim records refreshes per manga and an offline skip does not count as a refresh`() {
        val now = 50_000_000L
        val a = -101L
        val b = -102L
        assertFalse(TrackerOpenRefreshPolicy.claim(a, manualRefresh = false, online = false, now = now))
        assertTrue(TrackerOpenRefreshPolicy.claim(a, manualRefresh = false, online = true, now = now))
        assertFalse(TrackerOpenRefreshPolicy.claim(a, manualRefresh = false, online = true, now = now + 1))
        assertTrue(TrackerOpenRefreshPolicy.claim(b, manualRefresh = false, online = true, now = now + 1))
        assertTrue(TrackerOpenRefreshPolicy.claim(a, manualRefresh = true, online = true, now = now + 2))
    }

    @Test
    fun `the manga screen gates only the automatic network refresh, not local tracking work`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt").readText().replace("\r\n", "\n")
        val sync = source.substringAfter("private suspend fun syncTrackers(").substringBefore("fun fetchAllFromSource(")
        val gate = sync.indexOf("TrackerOpenRefreshPolicy.claim(mangaId, manualRefresh, online = isOnlineSafely())")
        assertTrue(gate in 0 until sync.indexOf("refreshTrackers(enhancedTrackersOnly = false)"))
        assertTrue(sync.indexOf("propagateConfirmedLocalTracking(it)") > gate)
        assertTrue(sync.indexOf("recordLocalTrackedChapterProgress.synchronize()") > gate)
    }
}
// KMK <--
