package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     * Then update chapter progress to all trackers.
     * This does not update local chapters' read status.
     */
    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    chapterNumber <= track.lastChapterRead /* SY --> */ ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)/* SY <-- */
                ) {
                    return@mapNotNull null
                }

                async<Result<Unit>> {
                    var remoteUpdateSucceeded = false
                    try {
                        val refreshedTrack = service.refresh(track.toDbTrack())
                            .toDomainTrack(idRequired = true)!!
                        val targetChapter = maxOf(chapterNumber, delayedTrackingStore.getTarget(track.id) ?: 0.0)
                        val updatedTrack = refreshedTrack.copy(
                            lastChapterRead = maxOf(refreshedTrack.lastChapterRead, targetChapter),
                        )
                        if (refreshedTrack.lastChapterRead < targetChapter) {
                            service.update(updatedTrack.toDbTrack(), true)
                            remoteUpdateSucceeded = true
                        }
                        // Persist refreshed remote progress even when it already satisfies the
                        // queued target; only then may the queue entry be cleared.
                        insertTrack.await(updatedTrack)
                        delayedTrackingStore.removeIfAtMost(track.id, updatedTrack.lastChapterRead)
                        // KMK --> v0.8.24: other versions bound to the same remote entry show the new progress
                        // right away instead of only after their own manual tracker refresh.
                        MirrorTrackToLinkedVersions({ getTracks }, { insertTrack }).await(updatedTrack)
                        // KMK <--
                        Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!remoteUpdateSucceeded) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                        }
                        Result.failure(e)
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
