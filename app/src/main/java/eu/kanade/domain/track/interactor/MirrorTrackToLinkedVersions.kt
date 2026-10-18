package eu.kanade.domain.track.interactor

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.24 -->
/**
 * A tracker entry is universal across every version of a manga: when one version's local copy of a remote entry changes
 * (reading, a manual edit in the tracking sheet, a refresh or a progress upload), every other manga row bound to the same
 * remote entry (same tracker, same remote id) gets the same state. This is a local database write only: no tracker request,
 * no chapter is marked on another source. Each version picks up chapter progress through its own sync when it is opened or
 * refreshed. Any failure is logged and never affects the update that triggered it.
 *
 * Dependencies resolve lazily inside the guarded block, so callers built without dependency injection (tests) stay unaffected.
 */
class MirrorTrackToLinkedVersions(
    private val getTracks: () -> GetTracks = { Injekt.get() },
    private val insertTrack: () -> InsertTrack = { Injekt.get() },
) {

    /**
     * @param authoritative true when [updated] is the remote entry's own current state (a refresh or a manual edit), so
     * other versions follow it even downwards; false on the reading path, where other versions are never lowered.
     */
    suspend fun await(updated: Track, authoritative: Boolean = false) {
        if (updated.remoteId <= 0L) return
        try {
            val reader = getTracks()
            val writer = insertTrack()
            reader.awaitByRemoteId(updated.trackerId, updated.remoteId)
                .filter { it.id != updated.id && it.mangaId != updated.mangaId }
                .forEach { sibling ->
                    TrackLinkedVersionMirrorPolicy.mirror(sibling, updated, allowLower = authoritative)?.let { writer.await(it) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "KMK tracker: could not update linked versions" }
        }
    }
}
// KMK <--
