package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.service.TrackPreferences
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/**
 * Refreshes the external tracker record of every linked group once, so a chapter read on any tracker reaches all versions in the
 * group without opening one of them. Before this, the tracker was only asked when its own manga page was opened, so the other
 * versions stayed behind however long you waited.
 *
 * One refresh per group is enough: the entry is shared between the versions and the refresh copies it to the others. Runs only
 * when "sync local tracking from trackers" is on, after a library update, and never fails the update.
 */
class RefreshLinkedGroupTrackers(
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val refreshTracks: RefreshTracks = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
) {
    suspend fun await() {
        if (!trackPreferences.autoSyncLocalTrackingFromTrackers().get()) return
        val groups = getCrossSourceMangaLinks.awaitAll().groupBy { it.groupId }.filterValues { it.size > 1 }
        if (groups.isEmpty()) return
        val trackedMangaIds = getTracks.await().map { it.mangaId }.toSet()
        for (members in groups.values) {
            val mangaId = members.firstNotNullOfOrNull { link ->
                getManga.await(link.url, link.source)?.id?.takeIf { it in trackedMangaIds }
            } ?: continue
            try {
                refreshTracks.await(mangaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "KMK tracker: could not refresh a linked group" }
            }
        }
    }
}
// KMK <--
