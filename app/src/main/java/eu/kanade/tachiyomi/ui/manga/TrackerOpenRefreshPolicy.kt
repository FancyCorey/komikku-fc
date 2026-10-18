package eu.kanade.tachiyomi.ui.manga

import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * v0.8.24 network review: opening a manga asks every connected tracker for its record ("Sync reading progress from
 * connected trackers"). Moving back and forth between a manga and the library repeated that for each open, and
 * offline it showed one tracker error per tracker every time.
 *
 * Automatic refreshes on open are skipped while offline and when the same manga was already refreshed within
 * [MIN_INTERVAL_MS] in this app session. A manual pull-to-refresh is never skipped. Local tracking work (gathering
 * linked versions, replaying local progress) does not use the network and always runs.
 */
internal object TrackerOpenRefreshPolicy {

    /** Provisional: how long an automatic refresh on open is reused before the trackers are asked again. */
    const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private val lastRefreshAt = ConcurrentHashMap<Long, Long>()

    fun shouldRefresh(manualRefresh: Boolean, online: Boolean, lastRefreshAt: Long?, now: Long): Boolean = when {
        manualRefresh -> true
        !online -> false
        lastRefreshAt == null -> true
        // A clock moved backwards counts as stale rather than suppressing refreshes indefinitely.
        now < lastRefreshAt -> true
        else -> now - lastRefreshAt >= MIN_INTERVAL_MS
    }

    /** Decides for [mangaId] and, when refreshing, records the time so the next open within the interval skips. */
    fun claim(mangaId: Long, manualRefresh: Boolean, online: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val refresh = shouldRefresh(manualRefresh, online, lastRefreshAt[mangaId], now)
        if (refresh) lastRefreshAt[mangaId] = now
        return refresh
    }
}
// KMK <--
