package exh.recs.matching

import exh.util.CrossSourceIdentityUndoJournal
import exh.util.FakePreferenceStore
import exh.util.FakeTasteRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityPair
import tachiyomi.domain.taste.model.CrossSourceRecordKey

// KMK v0.8.25 -->
/**
 * Grouping implies "same manga" but never overrules "Not the same manga", and when the grouping is removed or undone, the
 * confirmation it made goes with it, while the user's own decisions stay.
 */
class GroupJoinConfirmationsTest {
    private val repository = FakeTasteRepository()
    private val get = GetCrossSourceIdentityDecisions(repository)
    private val replace = ReplaceCrossSourceIdentityDecisions(repository)
    private var now = 10_000L
    private val controller = CrossSourceIdentityDecisionController(get, replace) { now++ }
    private val confirmations = GroupJoinConfirmations(FakePreferenceStore(), get, controller)

    private val a = CrossSourceRecordKey(1L, "/a")
    private val b = CrossSourceRecordKey(2L, "/b")
    private val c = CrossSourceRecordKey(3L, "/c")
    private fun pair(x: CrossSourceRecordKey, y: CrossSourceRecordKey): CrossSourceIdentityPair = CrossSourceIdentityDecisionPolicy.canonicalPair(x, y)

    @AfterEach
    fun tearDown() = CrossSourceIdentityUndoJournal.clear()

    private suspend fun confirmed(x: CrossSourceRecordKey, y: CrossSourceRecordKey) =
        CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(get.await(pair(x, y)))

    private suspend fun rejected(x: CrossSourceRecordKey, y: CrossSourceRecordKey) =
        CrossSourceIdentityDecisionPolicy.isCurrentRejection(get.await(pair(x, y)))

    /** What grouping does: confirm unless rejected, and remember what it wrote. */
    private suspend fun groupJoin(actionId: String, vararg pairs: CrossSourceIdentityPair): CrossSourceIdentityConfirmOutcome =
        controller.confirmUnlessRejected(pairs.toList()).also { confirmations.recordWritten(it.written, actionId) }

    @Test
    fun `grouping never turns Not the same manga into a confirmation and says which pair it skipped`() = runTest {
        controller.mutate(pair(a, b), CrossSourceIdentityMutation.REJECT)

        val outcome = groupJoin("join", pair(a, b), pair(a, c))

        assertTrue(rejected(a, b), "the user's rejection stands")
        assertTrue(confirmed(a, c))
        assertEquals(listOf(pair(a, b)), outcome.rejected)
        assertEquals(listOf(pair(a, c)), outcome.written.map { it.pair })
        assertEquals(listOf(pair(a, b)), confirmations.rejectedAmong(listOf(b), listOf(a, c)))
    }

    @Test
    fun `undoing a join takes back only what that join confirmed`() = runTest {
        controller.mutate(pair(a, b), CrossSourceIdentityMutation.CONFIRM) // the user's own, before any grouping
        groupJoin("join", pair(a, b), pair(a, c))

        confirmations.reverseForAction("join")

        assertTrue(confirmed(a, b), "a confirmation made before the grouping is kept")
        assertTrue(!confirmed(a, c), "the confirmation the join made is taken back")
    }

    @Test
    fun `removing a title takes back grouping's confirmation but keeps a decision the user made later`() = runTest {
        groupJoin("join", pair(a, b), pair(a, c))
        controller.mutate(pair(a, c), CrossSourceIdentityMutation.REJECT)
        controller.mutate(pair(a, c), CrossSourceIdentityMutation.CONFIRM) // the user confirmed it again, themselves

        confirmations.reverseBetween(a, listOf(b, c), actionId = "remove")

        assertTrue(!confirmed(a, b), "grouping's confirmation goes with the removed title")
        assertTrue(confirmed(a, c), "a decision the user made after the grouping is never taken back")
    }

    @Test
    fun `undoing a removal confirms the pair again unless the user decided since`() = runTest {
        groupJoin("join", pair(a, b), pair(a, c))
        confirmations.reverseBetween(a, listOf(b, c), actionId = "remove")
        controller.mutate(pair(a, c), CrossSourceIdentityMutation.REJECT)

        confirmations.restoreClearedBy("remove")

        assertTrue(confirmed(a, b), "put back as it was before the removal")
        assertTrue(rejected(a, c), "a rejection made after the removal is kept")
    }

    @Test
    fun `a removal unrelated to grouping changes no identity decision`() = runTest {
        controller.mutate(pair(a, b), CrossSourceIdentityMutation.CONFIRM)
        assertTrue(confirmations.reverseBetween(a, listOf(b), actionId = "remove").isEmpty())
        assertTrue(confirmed(a, b))
    }

    @Test
    fun `the user can still decide the pair is the same manga, and that is not taken back by later ungrouping`() = runTest {
        controller.mutate(pair(a, b), CrossSourceIdentityMutation.REJECT)
        assertTrue(confirmations.confirmAsUser(listOf(pair(a, b))))
        confirmations.reverseBetween(a, listOf(b), actionId = "remove")
        assertTrue(confirmed(a, b))
    }
}
// KMK <--
