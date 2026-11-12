package exh.recs.loved

import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.matching.ConfirmedGroupJoinSynchronizer
import exh.recs.matching.CrossSourceIdentityDecisionController
import exh.recs.matching.CrossSourceIdentityMutation
import exh.recs.matching.GroupJoinConfirmations
import exh.util.CrossSourceIdentityUndoJournal
import exh.util.FakePreferenceStore
import exh.util.FakeTasteRepository
import exh.util.GroupUndoJournal
import exh.util.GroupUndoService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.DeleteCrossSourceGroupCompletely
import tachiyomi.domain.taste.interactor.DeleteCrossSourceMangaLink
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.RestoreCrossSourceGroupState
import tachiyomi.domain.taste.interactor.UpsertCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste

class LovedMangaGroupIdentityLifecycleTest {
    private val repository = FakeTasteRepository()
    private val store = FakePreferenceStore()
    private val preferences = SourcePreferences(store)
    private val links = GetCrossSourceMangaLinks(repository)
    private val decisions = GetCrossSourceIdentityDecisions(repository)
    private var now = 10_000L
    private val controller = CrossSourceIdentityDecisionController(decisions, ReplaceCrossSourceIdentityDecisions(repository)) { now++ }
    private val confirmations = GroupJoinConfirmations(store, decisions, controller)
    private val tracker = mockk<tachiyomi.domain.tracker.repository.LocalTrackerRepository>(relaxed = true).also { repository ->
        coEvery { repository.separateSources(any(), any()) } returns Unit
    }
    private val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(CrossSourceRecordKey(1, "/a"), CrossSourceRecordKey(2, "/b"))
    private val entries = listOf(1L to "/a", 2L to "/b").map { (source, url) ->
        LovedMangaEntry(MangaTaste(source, source, url, url, MangaRating.LIKE.value, 1L, 1L), null)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        GroupUndoJournal.clear()
        CrossSourceIdentityUndoJournal.clear()
    }

    @AfterEach
    fun tearDown() {
        GroupUndoJournal.clear()
        CrossSourceIdentityUndoJournal.clear()
        Dispatchers.resetMain()
    }

    private fun model(synchronizerOverride: ConfirmedGroupJoinSynchronizer? = null): LovedMangaScreenModel {
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        every { tastes.subscribeAll() } returns emptyFlow()
        val synchronizer = synchronizerOverride ?: ConfirmedGroupJoinSynchronizer(
            getCrossSourceMangaLinks = links,
            getManga = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            identityController = controller,
            localTrackingPropagator = mockk(relaxed = true),
            ratingActions = mockk(relaxed = true),
            shareTrackerBinding = mockk(relaxed = true),
            sourcePreferences = preferences,
            confirmations = lazy { confirmations },
        )
        return LovedMangaScreenModel(
            getMangaTaste = tastes,
            getManga = mockk(relaxed = true),
            getCrossSourceMangaLinks = links,
            getCrossSourceIdentityDecisions = decisions,
            sourceManager = mockk(relaxed = true),
            getCrossSourceGroupPrimary = GetCrossSourceGroupPrimary(repository),
            setCrossSourceGroupPrimary = mockk(relaxed = true),
            setMangaTaste = mockk(relaxed = true),
            setMangaTasteBatch = mockk(relaxed = true),
            clearMangaTaste = mockk(relaxed = true),
            upsertCrossSourceMangaLinks = UpsertCrossSourceMangaLinks(repository),
            deleteCrossSourceMangaLink = DeleteCrossSourceMangaLink(repository),
            sourcePreferences = preferences,
            mangaRepository = mockk(relaxed = true),
            deleteCrossSourceGroupCompletely = DeleteCrossSourceGroupCompletely(repository),
            groupUndoService = GroupUndoService(links, GetCrossSourceGroupPrimary(repository), RestoreCrossSourceGroupState(repository)),
            identityController = controller,
            confirmedTrackedMangaTasteTargets = mockk(relaxed = true),
            confirmedMangaGroupTargets = mockk(relaxed = true),
            confirmedGroupLocalTrackingPropagator = mockk(relaxed = true),
            joinSynchronizer = lazy { synchronizer },
            groupConfirmations = lazy { confirmations },
            trackingLifecycle = lazy {
                exh.recs.matching.GroupTrackingLifecycle(links, GetCrossSourceGroupPrimary(repository), lazy { synchronizer }, lazy { tracker }) { 42L }
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun select(model: LovedMangaScreenModel, sources: Set<Long> = setOf(1L, 2L)) {
        val field = StateScreenModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
        (field.get(model) as MutableStateFlow<LovedMangaScreenModel.State>).value = LovedMangaScreenModel.State.Success(
            entries = entries,
            groupDuplicates = false,
            selectedKeys = entries.filter { it.taste.source in sources }.map { RatedMangaKey.of(it.taste) }.toSet(),
            linkGroupByKey = links.awaitAll().associate { "${it.source}|${it.url}" to it.groupId },
        )
    }

    @Test
    fun `rated merge preserves rejection and writes neither links nor undo`() = runTest {
        controller.mutate(pair, CrossSourceIdentityMutation.REJECT)
        val before = decisions.await(pair)
        val model = model()
        select(model)
        var result: LovedMangaScreenModel.MergeResult? = null
        model.mergeSelectedIntoGroup { result = it }
        advanceUntilIdle()
        assertEquals(LovedMangaScreenModel.MergeResult.Failed, result)
        assertEquals(before, decisions.await(pair))
        assertTrue(links.awaitAll().isEmpty())
        assertTrue(GroupUndoJournal.snapshot().isEmpty())
    }

    @Test
    fun `rated merge undo clears only its own confirmation`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        assertTrue(CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(decisions.await(pair)))
        val entry = GroupUndoJournal.snapshot().single()
        model.undoLastGroupAction(entry.id)
        advanceUntilIdle()
        assertTrue(links.awaitAll().isEmpty())
        assertFalse(CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(decisions.await(pair)))
        coVerify(exactly = 1) { tracker.separateSources(setOf(1L to "/a", 2L to "/b"), 42L) }
    }

    @Test
    fun `rated remove and undo restore grouping identity`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        select(model, setOf(2L))
        var removal: exh.recs.links.LinkGroupMutationResult? = null
        model.removeSelectedFromGroup { removal = it }
        advanceUntilIdle()
        assertFalse(CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(decisions.await(pair)))
        model.undoLastGroupAction(requireNotNull(requireNotNull(removal).undoEntryId))
        advanceUntilIdle()
        assertEquals(2, links.awaitAll().size)
        assertTrue(CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(decisions.await(pair)))
    }

    @Test
    fun `rated ungroup revokes grouping confirmation`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        model.ungroup(links.awaitAll().first().groupId)
        advanceUntilIdle()
        assertTrue(links.awaitAll().isEmpty())
        assertFalse(CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(decisions.await(pair)))
    }

    @Test
    fun `rated ungroup preserves an independently confirmed identity`() = runTest {
        controller.mutate(pair, CrossSourceIdentityMutation.CONFIRM)
        val original = decisions.await(pair)
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        model.ungroup(links.awaitAll().first().groupId)
        advanceUntilIdle()
        assertTrue(links.awaitAll().isEmpty())
        assertEquals(original, decisions.await(pair))
    }

    @Test
    fun `incomplete merge sharing returns group and undo identity and retry does not repeat merge`() = runTest {
        val synchronizer = mockk<ConfirmedGroupJoinSynchronizer>()
        coEvery { synchronizer.join(any(), any(), any()) } returns
            ConfirmedGroupJoinSynchronizer.JoinOutcome(false)
        val model = model(synchronizer)
        select(model)
        var result: LovedMangaScreenModel.MergeResult? = null
        model.mergeSelectedIntoGroup { result = it }
        advanceUntilIdle()
        assertTrue(result is LovedMangaScreenModel.MergeResult.PartialSharing)
        val partial = result as LovedMangaScreenModel.MergeResult.PartialSharing
        val savedLinks = links.awaitAll()
        val savedJournal = GroupUndoJournal.snapshot()
        assertEquals(savedLinks.first().groupId, partial.groupId)
        assertEquals(savedJournal.single().id, partial.undoEntryId)

        model.retryGroupSharing(partial.groupId, partial.undoEntryId) { result = it }
        advanceUntilIdle()
        assertEquals(partial, result, "a failed retry must still report incomplete sharing")
        coEvery { synchronizer.join(any(), any(), any()) } returns
            ConfirmedGroupJoinSynchronizer.JoinOutcome(true)
        model.retryGroupSharing(partial.groupId, partial.undoEntryId) { result = it }
        advanceUntilIdle()
        assertEquals(LovedMangaScreenModel.MergeResult.Success(partial.undoEntryId), result)
        assertEquals(savedLinks, links.awaitAll())
        assertEquals(savedJournal, GroupUndoJournal.snapshot())
        coVerify(exactly = 3) {
            synchronizer.join(partial.groupId, savedLinks.map { it.source to it.url }, partial.undoEntryId)
        }
    }

    @Test
    fun `sharing exception after saved merge reports partial and an undone group cannot be retried`() = runTest {
        val synchronizer = mockk<ConfirmedGroupJoinSynchronizer>()
        coEvery { synchronizer.join(any(), any(), any()) } throws IllegalStateException("sharing unavailable")
        val model = model(synchronizer)
        select(model)
        var result: LovedMangaScreenModel.MergeResult? = null
        model.mergeSelectedIntoGroup { result = it }
        advanceUntilIdle()
        assertTrue(result is LovedMangaScreenModel.MergeResult.PartialSharing)
        val partial = result as LovedMangaScreenModel.MergeResult.PartialSharing
        assertEquals(2, links.awaitAll().size)
        model.undoLastGroupAction(requireNotNull(partial.undoEntryId))
        advanceUntilIdle()
        model.retryGroupSharing(partial.groupId, partial.undoEntryId) { result = it }
        advanceUntilIdle()
        assertEquals(LovedMangaScreenModel.MergeResult.Failed, result)
        assertTrue(links.awaitAll().isEmpty())
        coVerify(exactly = 1) { synchronizer.join(any(), any(), any()) }
    }

    @Test
    fun `rated remove and ungroup separate before deleting links and expose separation failure`() = runTest {
        for (wholeGroup in listOf(false, true)) {
            GroupUndoJournal.clear()
            val model = model()
            select(model)
            model.mergeSelectedIntoGroup()
            advanceUntilIdle()
            val before = links.awaitAll()
            val group = before.first().groupId
            val expected = if (wholeGroup) setOf(1L to "/a", 2L to "/b") else setOf(2L to "/b")
            val journalBefore = GroupUndoJournal.snapshot()
            coEvery { tracker.separateSources(expected, 42L) } coAnswers {
                assertEquals(before, links.awaitAll())
                throw IllegalStateException("split failed")
            }
            select(model, setOf(2L))
            var result: exh.recs.links.LinkGroupMutationResult? = null
            if (wholeGroup) model.ungroup(group) { result = it } else model.removeSelectedFromGroup { result = it }
            advanceUntilIdle()
            assertFalse(requireNotNull(result).succeeded)
            assertEquals(before, links.awaitAll())
            assertEquals(journalBefore, GroupUndoJournal.snapshot())
            coEvery { tracker.separateSources(expected, 42L) } coAnswers {
                assertEquals(before, links.awaitAll())
            }
            if (wholeGroup) model.ungroup(group) { result = it } else model.removeSelectedFromGroup { result = it }
            advanceUntilIdle()
            assertTrue(requireNotNull(result).succeeded)
            assertEquals(if (wholeGroup) 0 else 1, links.awaitAll().size)
            // Reset the fixture for the next case without changing the tracker API or restoring progress snapshots.
            repository.deleteAllCrossSourceMangaLinks()
            coEvery { tracker.separateSources(any(), any()) } returns Unit
        }
    }

    @Test
    fun `undo removal rejoins restored members and reports sharing failure truthfully`() = runTest {
        val synchronizer = mockk<ConfirmedGroupJoinSynchronizer>()
        coEvery { synchronizer.join(any(), any(), any()) } returns ConfirmedGroupJoinSynchronizer.JoinOutcome(true)
        val model = model(synchronizer)
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        val group = links.awaitAll().first().groupId
        select(model, setOf(2L))
        var removal: exh.recs.links.LinkGroupMutationResult? = null
        model.removeSelectedFromGroup { removal = it }
        advanceUntilIdle()
        var rejoinedAfterRestore = false
        coEvery { synchronizer.join(group, setOf(1L to "/a", 2L to "/b").toList(), any()) } coAnswers {
            assertEquals(setOf(1L to "/a", 2L to "/b"), links.awaitByGroupId(group).map { it.source to it.url }.toSet())
            exh.recs.matching.GroupTrackingMembershipCoordinator.withMembershipLock { rejoinedAfterRestore = true }
            ConfirmedGroupJoinSynchronizer.JoinOutcome(false)
        }
        var outcome: exh.util.GroupUndoOutcome? = null
        model.undoLastGroupAction(requireNotNull(requireNotNull(removal).undoEntryId)) { outcome = it }
        advanceUntilIdle()
        assertEquals(2, links.awaitAll().size)
        assertTrue(rejoinedAfterRestore)
        assertEquals(exh.util.GroupUndoResult.FAILED, outcome?.result)
        coVerify(exactly = 0) { tracker.upsertWork(any()) }
        coVerify(exactly = 0) { tracker.upsertSourceProgress(any()) }
        coVerify(exactly = 0) { tracker.deleteSourceProgress(any(), any(), any()) }
    }

    @Test
    fun `undo merge separation failure preserves links and leaves undo available`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        val before = links.awaitAll()
        val entry = GroupUndoJournal.snapshot().single()
        coEvery { tracker.separateSources(any(), any()) } throws IllegalStateException("split failed")
        var outcome: exh.util.GroupUndoOutcome? = null
        model.undoLastGroupAction(entry.id) { outcome = it }
        advanceUntilIdle()
        assertEquals(exh.util.GroupUndoResult.FAILED, outcome?.result)
        assertEquals(before, links.awaitAll())
        assertEquals(listOf(entry), GroupUndoJournal.snapshot())
    }

    @Test
    fun `successful removal and ungroup without a journal entry are not failures`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        val group = links.awaitAll().first().groupId
        io.mockk.mockkObject(exh.util.GroupUndoRecorder)
        try {
            every { exh.util.GroupUndoRecorder.buildRemoveFromGroupEntry(any(), any()) } returns null
            every { exh.util.GroupUndoRecorder.buildUngroupEntry(any(), any(), any(), any()) } returns null
            select(model, setOf(2L))
            var result: exh.recs.links.LinkGroupMutationResult? = null
            model.removeSelectedFromGroup { result = it }
            advanceUntilIdle()
            assertTrue(result?.succeeded == true)
            assertEquals(null, result?.undoEntryId)
            assertEquals(1, links.awaitAll().size)
            model.ungroup(group) { result = it }
            advanceUntilIdle()
            assertTrue(result?.succeeded == true)
            assertEquals(null, result?.undoEntryId)
            assertTrue(links.awaitAll().isEmpty())
        } finally {
            io.mockk.unmockkObject(exh.util.GroupUndoRecorder)
        }
    }

    @Test
    fun `undo conflict is checked before tracker separation`() = runTest {
        val model = model()
        select(model)
        model.mergeSelectedIntoGroup()
        advanceUntilIdle()
        val entry = GroupUndoJournal.snapshot().single()
        val moved = links.awaitAll().first().copy(groupId = "other")
        repository.upsertCrossSourceMangaLinks(listOf(moved))
        var outcome: exh.util.GroupUndoOutcome? = null
        model.undoLastGroupAction(entry.id) { outcome = it }
        advanceUntilIdle()
        assertEquals(exh.util.GroupUndoResult.CONFLICT, outcome?.result)
        coVerify(exactly = 0) { tracker.separateSources(any(), any()) }
    }
}
