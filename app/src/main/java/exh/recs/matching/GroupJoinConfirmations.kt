package exh.recs.matching

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecision
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityPair
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/**
 * Which "same manga" confirmations were written by grouping, so removing or undoing that grouping can take them back.
 *
 * A confirmation saved by Add title, Create group, Move, Merge, Share rating and progress or Track other versions looks exactly like
 * one the user made in Review same-manga matches. Without this record a title removed from a group, or a join that was undone, kept
 * its confirmation, and with it the reader still offered that version to continue from and still shared ratings and progress with it.
 *
 * Only a confirmation that is still exactly the one grouping wrote is taken back (same decision, same time). A confirmation that
 * existed before the grouping, or a decision the user made later, is never touched. The record lives on this device only; if it is
 * lost, nothing is taken back, which is the safe direction.
 *
 * Taking a confirmation back stops future sharing between the two versions. What was already copied (a rating, a chapter, a tracker
 * entry) stays, because the user may have changed it since.
 */
class GroupJoinConfirmations(
    preferenceStore: PreferenceStore = Injekt.get(),
    private val getDecisions: GetCrossSourceIdentityDecisions = Injekt.get(),
    private val controller: CrossSourceIdentityDecisionController = CrossSourceIdentityDecisionController(),
) {
    @Serializable
    private data class Record(
        val leftSource: Long,
        val leftUrl: String,
        val rightSource: Long,
        val rightUrl: String,
        val updatedAt: Long,
        val actionId: String? = null,
    ) {
        val pair get() = CrossSourceIdentityPair(CrossSourceRecordKey(leftSource, leftUrl), CrossSourceRecordKey(rightSource, rightUrl))
    }

    private val pref = preferenceStore.getString(PREFERENCE_KEY, "")
    private val serializer = ListSerializer(Record.serializer())

    private fun records(): List<Record> = pref.get().takeIf { it.isNotBlank() }?.let {
        try {
            Json.decodeFromString(serializer, it)
        } catch (_: Exception) {
            emptyList()
        }
    } ?: emptyList()

    private fun save(records: List<Record>) = pref.set(Json.encodeToString(serializer, records.takeLast(MAX_RECORDS)))

    /** Remembers the confirmations a group action just wrote. */
    fun recordWritten(decisions: List<CrossSourceIdentityDecision>, actionId: String?) {
        if (decisions.isEmpty()) return
        synchronized(lock) {
            val written = decisions.filter(CrossSourceIdentityDecisionPolicy::isAuthoritativeConfirmation).map {
                Record(it.pair.left.source, it.pair.left.url, it.pair.right.source, it.pair.right.url, it.updatedAt, actionId)
            }
            val pairs = written.map { it.pair }.toSet()
            save(records().filterNot { it.pair in pairs } + written)
        }
    }

    /** Pairs among [keys] the user marked as not the same manga, checked before titles are linked together. */
    suspend fun rejectedAmong(joining: Collection<CrossSourceRecordKey>, existing: Collection<CrossSourceRecordKey>): List<CrossSourceIdentityPair> {
        val pairs = joining.flatMap { joiner -> (existing + joining).filter { it != joiner }.map { CrossSourceIdentityDecisionPolicy.canonicalPair(joiner, it) } }
            .distinct()
        return pairs.filter { CrossSourceIdentityDecisionPolicy.isCurrentRejection(getDecisions.await(it)) }
    }

    /** The user said these are the same manga after all. This is the user's own decision, so it is not recorded as grouping's. */
    suspend fun confirmAsUser(pairs: List<CrossSourceIdentityPair>): Boolean =
        controller.mutateAll(pairs, CrossSourceIdentityMutation.CONFIRM).let {
            it == CrossSourceIdentityMutationResult.APPLIED || it == CrossSourceIdentityMutationResult.UNCHANGED
        }

    /** Undo of a join: takes back what that action confirmed. */
    suspend fun reverseForAction(actionId: String): List<CrossSourceIdentityPair> =
        reverse { it.actionId == actionId }

    /**
     * A title left a group (Remove, Move, Ungroup all): takes back the confirmations grouping made between it and its former group.
     * [actionId] lets an Undo of that removal put them back.
     */
    suspend fun reverseBetween(
        member: CrossSourceRecordKey,
        formerPeers: Collection<CrossSourceRecordKey>,
        actionId: String?,
    ): List<CrossSourceIdentityPair> {
        val peers = formerPeers.toSet() - member
        val cleared = reverse { record ->
            val pair = record.pair
            (pair.left == member && pair.right in peers) || (pair.right == member && pair.left in peers)
        }
        if (actionId != null && cleared.isNotEmpty()) synchronized(lock) { clearedByAction.getOrPut(actionId) { mutableListOf() } += cleared }
        return cleared
    }

    /** Undo of a removal: confirms again what that removal took back, unless the user decided something since. */
    suspend fun restoreClearedBy(actionId: String) {
        val cleared = synchronized(lock) { clearedByAction.remove(actionId) }.orEmpty()
        if (cleared.isEmpty()) return
        val outcome = controller.confirmUnlessRejected(
            cleared.filter { pair ->
                val current = getDecisions.await(pair)
                current == null || current.deletedAt != null
            },
        )
        recordWritten(outcome.written, actionId)
    }

    private suspend fun reverse(matches: (Record) -> Boolean): List<CrossSourceIdentityPair> {
        val candidates = synchronized(lock) { records().filter(matches) }
        if (candidates.isEmpty()) return emptyList()
        val stillOurs = candidates.filter { record ->
            val current = getDecisions.await(record.pair)
            CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(current) && current?.updatedAt == record.updatedAt
        }
        val cleared = if (stillOurs.isEmpty()) {
            emptyList()
        } else {
            val result = try {
                controller.mutateAll(stillOurs.map { it.pair }, CrossSourceIdentityMutation.CLEAR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "KMK group: could not take back a grouping confirmation" }
                CrossSourceIdentityMutationResult.FAILED
            }
            if (result == CrossSourceIdentityMutationResult.APPLIED) stillOurs.map { it.pair } else emptyList()
        }
        // A record whose confirmation the user changed since is no longer grouping's; a failed clear keeps its record to retry later.
        val keep = stillOurs.filter { it.pair !in cleared }.map { it.pair }.toSet()
        val drop = candidates.map { it.pair }.toSet() - keep
        synchronized(lock) { save(records().filterNot { it.pair in drop }) }
        return cleared
    }

    companion object {
        const val PREFERENCE_KEY = "kmk_group_join_confirmations"
        const val MAX_RECORDS = 2_000
        private val lock = Any()

        // Undo lives only as long as the in-memory group undo journal, so what a removal cleared is kept the same way.
        private val clearedByAction = HashMap<String, MutableList<CrossSourceIdentityPair>>()
    }
}
// KMK <--
