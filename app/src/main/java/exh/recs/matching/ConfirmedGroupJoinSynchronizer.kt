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
import tachiyomi.domain.taste.model.CrossSourceIdentityPair
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
    // KMK v0.8.25: which confirmations grouping wrote, so removing or undoing the grouping can take them back
    private val confirmations: Lazy<GroupJoinConfirmations> = lazy { GroupJoinConfirmations() },
) {

    /**
     * KMK v0.8.25: what joining did. [sharingComplete] is false when a sharing step failed (the link itself is saved either way);
     * [rejected] are pairs the user marked as not the same manga. Any rejection stops sharing for the complete batch.
     */
    data class JoinOutcome(
        val sharingComplete: Boolean,
        val rejected: List<CrossSourceIdentityPair> = emptyList(),
    )

    /** @return true when every step ran without error. */
    suspend fun onMembersJoined(groupId: String, joined: Collection<Pair<Long, String>>): Boolean =
        join(groupId, joined).sharingComplete

    /** [actionId] ties the confirmations this writes to one group action, so its Undo can take them back. */
    suspend fun join(groupId: String, joined: Collection<Pair<Long, String>>, actionId: String? = null): JoinOutcome {
        if (joined.isEmpty()) return JoinOutcome(true)
        var ok = true
        var rejected = emptyList<CrossSourceIdentityPair>()
        try {
            val members = getCrossSourceMangaLinks.awaitByGroupId(groupId)
            val joinedKeys = joined.toSet()
            val anchorLink = members.firstOrNull { (it.source to it.url) !in joinedKeys }
                ?: members.firstOrNull()
                ?: return JoinOutcome(true)

            val confirmed = confirmMembers(members, actionId)
            ok = confirmed.first && ok
            rejected = confirmed.second
            // Sharing and tracker consolidation must not bypass a rejection between non-anchor members.
            // Failed/conflicting confirmation writes grant no authority to share.
            if (!confirmed.first || rejected.isNotEmpty()) return JoinOutcome(false, rejected)
            val anchor = anchorLink.toManga() ?: return JoinOutcome(false)
            val allManga = members.mapNotNull { it.toManga() }
            val sharingMembers = members

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
                    .forEach { ratingActions.applyGroupRatingToJoinedMember(sharingMembers, it) }
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
        return JoinOutcome(ok, rejected)
    }

    private suspend fun confirmMembers(
        members: List<CrossSourceMangaLink>,
        actionId: String?,
    ): Pair<Boolean, List<CrossSourceIdentityPair>> {
        val keys = members.map { CrossSourceRecordKey(it.source, it.url) }.distinct()
        val pairs = keys.flatMapIndexed { index, left ->
            keys.drop(index + 1).map { right -> CrossSourceIdentityDecisionPolicy.canonicalPair(left, right) }
        }
        if (pairs.isEmpty()) return true to emptyList()
        // Grouping implies "same manga", but never over the user's own "Not the same manga".
        val outcome = identityController.confirmUnlessRejected(pairs)
        try {
            confirmations.value.recordWritten(outcome.written, actionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "KMK group: could not remember which confirmations grouping wrote" }
        }
        val ok = outcome.result != CrossSourceIdentityMutationResult.FAILED && outcome.result != CrossSourceIdentityMutationResult.CONFLICT
        return ok to outcome.rejected
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
