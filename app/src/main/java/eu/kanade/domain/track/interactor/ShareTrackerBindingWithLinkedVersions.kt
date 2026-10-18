package eu.kanade.domain.track.interactor

import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.matching.ConfirmedMangaGroupTargets
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.24 -->
/**
 * With "Track linked versions together" on, linking a tracker entry to one version also links the same entry to every
 * confirmed linked version that has no entry on that tracker yet. Local database only: no tracker request is made and no
 * chapter is marked on another source; each version syncs its chapters when it is opened or refreshed. A version already
 * linked to a different entry on that tracker is left alone. Any failure is logged and never affects the original link.
 */
class ShareTrackerBindingWithLinkedVersions(
    private val sourcePreferences: () -> SourcePreferences = { Injekt.get() },
    private val getManga: () -> GetManga = { Injekt.get() },
    private val confirmedTargets: () -> ConfirmedMangaGroupTargets = { Injekt.get() },
    private val getTracks: () -> GetTracks = { Injekt.get() },
    private val insertTrack: () -> InsertTrack = { Injekt.get() },
) {

    suspend fun await(bound: Track) {
        if (bound.remoteId <= 0L) return
        try {
            if (!sourcePreferences().confirmedTrackedVersionLocalTrackingPropagationEnabled().get()) return
            val origin = getManga().await(bound.mangaId) ?: return
            val siblingIds = confirmedTargets().await(origin).map { it.id }.filter { it != bound.mangaId }.distinct()
            if (siblingIds.isEmpty()) return
            val existing = getTracks().await(siblingIds)
            val writer = insertTrack()
            TrackerBindingSharePolicy.newBindings(bound, siblingIds, existing).forEach { writer.await(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "KMK tracker: could not link the entry to other versions" }
        }
    }
}

object TrackerBindingSharePolicy {

    /** Rows to insert: one per sibling that has no entry on [bound]'s tracker, each a copy of [bound] for that sibling. */
    fun newBindings(bound: Track, siblingMangaIds: List<Long>, existingBySibling: Map<Long, List<Track>>): List<Track> =
        siblingMangaIds
            .filter { it != bound.mangaId }
            .distinct()
            .filter { siblingId -> existingBySibling[siblingId].orEmpty().none { it.trackerId == bound.trackerId } }
            .map { siblingId -> bound.copy(id = 0L, mangaId = siblingId) }
}
// KMK <--
