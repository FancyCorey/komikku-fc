package tachiyomi.data.history

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.history.model.RecentReadingActivity
import tachiyomi.domain.history.repository.RecentReadingRepository

// KMK -->
class RecentReadingRepositoryImpl(
    private val handler: DatabaseHandler,
) : RecentReadingRepository {

    override suspend fun getRecentReadingActivity(sinceMillis: Long): List<RecentReadingActivity> {
        return handler.awaitList {
            historyQueries.getRecentReadingActivity(sinceMillis) { mangaId, chaptersRead, activeDays, lastReadAt, totalChapters ->
                RecentReadingActivity(
                    mangaId = mangaId,
                    chaptersRead = chaptersRead,
                    activeDays = activeDays,
                    lastReadAt = lastReadAt ?: 0L,
                    totalChapters = totalChapters,
                )
            }
        }
    }
}
// KMK <--
