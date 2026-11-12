package exh.recs.matching

import exh.util.GroupJournalActionType
import exh.util.GroupUndoJournal
import exh.util.GroupUndoOutcome
import exh.util.GroupUndoResult
import exh.util.GroupUndoService
import exh.util.groupUndoLinkConflicts
import exh.util.groupUndoPrimaryConflicts
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Group membership changes retain current tracking data; no tracking snapshots are restored. */
class GroupTrackingLifecycle(
    private val links: GetCrossSourceMangaLinks,
    private val primaries: GetCrossSourceGroupPrimary,
    private val synchronizer: Lazy<ConfirmedGroupJoinSynchronizer>,
    private val repository: Lazy<LocalTrackerRepository> = lazy { Injekt.get() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Caller holds the membership coordinator across this call and the corresponding link write. */
    suspend fun separate(sources: Set<Pair<Long, String>>) {
        if (sources.isNotEmpty()) repository.value.separateSources(sources, clock())
    }

    suspend fun undo(
        entryId: String,
        service: GroupUndoService,
        confirmations: GroupJoinConfirmations,
    ): GroupUndoOutcome {
        val entry = GroupUndoJournal.snapshot().find { it.id == entryId } ?: return GroupUndoOutcome(GroupUndoResult.FAILED)
        if (!entry.reversible) return GroupUndoOutcome(GroupUndoResult.CONFLICT, entry.actionType)
        return try {
            val outcome = GroupTrackingMembershipCoordinator.withMembershipLock {
                // Check the same conflict contract before separating anything. The service checks again before restoring links.
                val current = entry.touchedKeys.associateWith { links.awaitBySourceUrl(it.source, it.url) }
                val conflict = current.any { (key, link) -> groupUndoLinkConflicts(entry.expectedPostLinks[key], link) } ||
                    entry.touchedGroupIds.any { groupUndoPrimaryConflicts(entry.expectedPostPrimaries[it], primaries.awaitByGroupId(it)) }
                if (conflict) return@withMembershipLock GroupUndoOutcome(GroupUndoResult.CONFLICT, entry.actionType)
                separate(
                    current.filter { (key, link) -> link != null && link.groupId != entry.previousLinks[key]?.groupId }
                        .keys.map { it.source to it.url }.toSet(),
                )
                service.undo(entryId)
            }
            if (outcome.result != GroupUndoResult.RESTORED || entry.actionType == GroupJournalActionType.SET_PRIMARY) return outcome
            confirmations.reverseForAction(entryId)
            confirmations.restoreClearedBy(entryId)
            var complete = true
            for (groupId in entry.previousLinks.values.mapNotNull { it?.groupId }.distinct()) {
                val members = links.awaitByGroupId(groupId)
                if (members.size > 1) {
                    // Re-read current records in the existing join/consolidation owner, including reading since removal.
                    complete = synchronizer.value.join(groupId, members.map { it.source to it.url }).sharingComplete && complete
                }
            }
            if (complete) outcome else GroupUndoOutcome(GroupUndoResult.FAILED, entry.actionType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Separation is durable and idempotent. A later link/sharing failure must never be presented as full success.
            GroupUndoOutcome(GroupUndoResult.FAILED, entry.actionType)
        }
    }
}
