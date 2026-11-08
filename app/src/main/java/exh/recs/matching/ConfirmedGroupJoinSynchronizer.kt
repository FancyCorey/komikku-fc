package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.RecordLocalTrackedChapterProgress
import eu.kanade.domain.track.interactor.ShareTrackerBindingWithLinkedVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/**
 * Everything that has to happen after titles are added to, moved into or merged into a link group from a screen that only
 * writes link rows (Link groups, Rated titles).
 *
 * Before this existed those screens saved the link and stopped: the new titles were never marked as confirmed to be the same
 * manga, so every "linked versions" feature skipped them (they only act on confirmed members), and nothing ran that would
 * give them the group's tracking, chapter progress, rating or tracker entry.
 *
 * Order matters: confirm, then local tracking (so the new titles become tracked members), then rating, then tracker
 * entries, then a first replay of chapter progress; all of that is local and quick. Loading chapter lists from sources for
 * versions that were never opened runs in the background afterwards. Each step honours its own setting and a failure in one
 * never undoes the link or stops the next step. The caller passes every title that should receive the group, which includes
 * the title the user is standing on.
 */
class ConfirmedGroupJoinSynchronizer(
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val identityController: CrossSourceIdentityDecisionController = CrossSourceIdentityDecisionController(),
    private val localTrackingPropagator: ConfirmedGroupLocalTrackingPropagator =
        ConfirmedGroupLocalTrackingPropagator(Injekt.get<LocalTrackerRepository>()),
    private val ratingActions: ConfirmedGroupTasteRatingActions = ConfirmedGroupTasteRatingManager(),
    private val shareTrackerBinding: ShareTrackerBindingWithLinkedVersions = ShareTrackerBindingWithLinkedVersions(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // replays the saved progress of the group's own record, not of every tracked work
    private val progressReplay: suspend (List<Manga>) -> Unit = { Injekt.get<RecordLocalTrackedChapterProgress>().synchronizeFor(it) },
    private val chapterCount: suspend (Manga) -> Int = { Injekt.get<GetChaptersByMangaId>().await(it.id).size },
    private val fetchChapters: suspend (Manga) -> Unit = { Injekt.get<UpdateMangaFromRemote>().invoke(it, fetchChapters = true) },
    // Loading chapter lists from sources is slow, so it runs here and not while the screen waits.
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** @return true when every step ran without error. */
    suspend fun onMembersJoined(groupId: String, joined: Collection<Pair<Long, String>>): Boolean {
        if (joined.isEmpty()) return true
        var ok = true
        try {
            val members = getCrossSourceMangaLinks.awaitByGroupId(groupId)
            val joinedKeys = joined.toSet()
            val anchorLink = members.firstOrNull { (it.source to it.url) !in joinedKeys }
                ?: members.firstOrNull()
                ?: return true

            ok = confirmAgainstAnchor(anchorLink, members) && ok

            val anchor = anchorLink.toManga() ?: return ok
            val allManga = members.mapNotNull { it.toManga() }

            ok = step {
                localTrackingPropagator.propagateIfAnyTracked(
                    allManga,
                    enabled = sourcePreferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().get(),
                )
            } && ok
            // The rating is a local database write, so it comes straight after tracking and is never behind a network wait.
            ok = step {
                allManga
                    .filter { (it.source to it.url) in joinedKeys }
                    .forEach { ratingActions.applyGroupRatingToJoinedMember(members, it) }
            } && ok
            ok = step {
                for (track in getTracks.await(anchor.id)) shareTrackerBinding.await(track)
            } && ok
            // give every version that already has chapters the group's chapter progress right now
            ok = step { progressReplay(allManga) } && ok

            // A version that was never opened has no chapter list, so there is nothing to attach the group's chapter to.
            // Loading it needs the source, so it happens in the background and the chapter appears when it is done.
            val needChapters = allManga.filter { (it.source to it.url) in joinedKeys && chapterCount(it) == 0 }
            if (needChapters.isNotEmpty()) {
                backgroundScope.launch {
                    try {
                        needChapters.forEach { manga ->
                            try {
                                fetchChapters(manga)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "KMK group: could not load the chapters of a new group member" }
                            }
                        }
                        progressReplay(allManga)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "KMK group: could not apply progress after loading chapters" }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "KMK group: could not finish syncing the new group members" }
            ok = false
        }
        return ok
    }

    private suspend fun confirmAgainstAnchor(anchor: CrossSourceMangaLink, members: List<CrossSourceMangaLink>): Boolean {
        val pairs = members
            .filterNot { it.source == anchor.source && it.url == anchor.url }
            .map {
                CrossSourceIdentityDecisionPolicy.canonicalPair(
                    CrossSourceRecordKey(anchor.source, anchor.url),
                    CrossSourceRecordKey(it.source, it.url),
                )
            }
        if (pairs.isEmpty()) return true
        val result = identityController.mutateAll(pairs, CrossSourceIdentityMutation.CONFIRM)
        return result != CrossSourceIdentityMutationResult.FAILED && result != CrossSourceIdentityMutationResult.CONFLICT
    }

    private suspend fun step(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "KMK group: a sync step failed after linking" }
        false
    }

    private suspend fun CrossSourceMangaLink.toManga(): Manga? = getManga.await(url, source)
}
// KMK <--
