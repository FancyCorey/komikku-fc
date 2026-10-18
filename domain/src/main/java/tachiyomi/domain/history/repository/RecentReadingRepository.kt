package tachiyomi.domain.history.repository

import tachiyomi.domain.history.model.RecentReadingActivity

// KMK -->
/**
 * Read-only access to the recent-reading signal. Deliberately separate from [HistoryRepository] so the shared
 * history contract (and every fake of it) is untouched by the v0.8.24 recent-reading profile.
 */
interface RecentReadingRepository {
    /** One row per manga that has at least one read chapter whose last read is at or after [sinceMillis]. */
    suspend fun getRecentReadingActivity(sinceMillis: Long): List<RecentReadingActivity>
}
// KMK <--
