package exh.recs.links

import exh.util.GroupUndoJournal
import exh.util.GroupUndoResult
import exh.util.GroupUndoService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.RestoreCrossSourceGroupState
import tachiyomi.domain.taste.model.CrossSourceMangaLink

class LinkGroupManagementScreenModelTest {
    @BeforeEach fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        GroupUndoJournal.clear()
    }

    @AfterEach fun tearDown() {
        Dispatchers.resetMain()
        GroupUndoJournal.clear()
    }

    private fun link(group: String, source: Long, url: String) = CrossSourceMangaLink(source, url, group, "Title $url", 1L, 1L)

    private fun manga(source: Long, url: String) = Manga.create().copy(source = source, url = url, ogTitle = "Title $url", favorite = false)

    private data class Fixture(
        val model: LinkGroupManagementScreenModel,
        val links: MutableList<CrossSourceMangaLink>,
        val primaries: MutableList<tachiyomi.domain.taste.model.CrossSourceGroupPrimary>,
        val confirmations: exh.recs.matching.GroupJoinConfirmations,
        val setPrimary: tachiyomi.domain.taste.interactor.SetCrossSourceGroupPrimary,
    )

    private fun model(
        initialLinks: List<CrossSourceMangaLink>,
        restore: RestoreCrossSourceGroupState,
        initialPrimaries: List<tachiyomi.domain.taste.model.CrossSourceGroupPrimary> = emptyList(),
    ): Fixture {
        val links = initialLinks.toMutableList()
        val primaries = initialPrimaries.toMutableList()
        val linkReader = mockk<GetCrossSourceMangaLinks>()
        val primaryReader = mockk<GetCrossSourceGroupPrimary>()
        coEvery { linkReader.awaitAll() } answers { links.toList() }
        coEvery { linkReader.awaitBySourceUrl(any(), any()) } answers { links.firstOrNull { it.source == firstArg() && it.url == secondArg() } }
        coEvery { linkReader.awaitByGroupId(any()) } answers { links.filter { it.groupId == firstArg<String>() } }
        coEvery { primaryReader.awaitAll() } answers { primaries.toList() }
        coEvery { primaryReader.awaitByGroupId(any()) } answers { primaries.singleOrNull { it.groupId == firstArg<String>() } }
        val manga = mockk<MangaRepository>(relaxed = true)
        coEvery { manga.getMangaByUrlAndSourceId(any(), any()) } returns null
        coEvery { manga.getFavorites() } returns emptyList()
        val confirmations = mockk<exh.recs.matching.GroupJoinConfirmations>(relaxed = true)
        val setPrimary = mockk<tachiyomi.domain.taste.interactor.SetCrossSourceGroupPrimary>(relaxed = true)
        coEvery { confirmations.rejectedAmong(any(), any()) } returns emptyList()
        val screen = LinkGroupManagementScreenModel(
            getCrossSourceMangaLinks = linkReader,
            mangaRepository = manga,
            sourceManager = mockk<SourceManager>(relaxed = true),
            getCrossSourceGroupPrimary = primaryReader,
            restoreCrossSourceGroupState = restore,
            groupUndoService = GroupUndoService(linkReader, primaryReader, restore),
            groupConfirmations = lazy { confirmations },
            setCrossSourceGroupPrimary = lazy { setPrimary },
            sourcePreferences = lazy { eu.kanade.domain.source.service.SourcePreferences(exh.util.FakePreferenceStore()) },
        )
        return Fixture(screen, links, primaries, confirmations, setPrimary)
    }

    // KMK v0.8.25 -->
    @Test
    fun `adding a title the user marked Not the same manga links nothing and reports the pair`() = runTest {
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(link("existing", 1L, "kept")), restore)
        val pair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
            tachiyomi.domain.taste.model.CrossSourceRecordKey(2L, "a"),
            tachiyomi.domain.taste.model.CrossSourceRecordKey(1L, "kept"),
        )
        coEvery { fixture.confirmations.rejectedAmong(any(), any()) } returns listOf(pair)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        fixture.model.addMangaToGroup("existing", listOf(manga(2L, "a"))) { result = it }
        advanceUntilIdle()

        coVerify(exactly = 0) { restore.await(any(), any(), any(), any()) }
        assertFalse(result!!.succeeded)
        assertEquals(setOf("Title kept", "Title a"), result!!.conflicts.single().let { setOf(it.firstTitle, it.secondTitle) })
        assertTrue(GroupUndoJournal.snapshot().isEmpty(), "nothing to undo because nothing was saved")
    }

    @Test
    fun `confirming the pair as the same manga is the user's decision and then the add goes ahead`() = runTest {
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(link("existing", 1L, "kept")), restore)
        val pair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
            tachiyomi.domain.taste.model.CrossSourceRecordKey(2L, "a"),
            tachiyomi.domain.taste.model.CrossSourceRecordKey(1L, "kept"),
        )
        coEvery { fixture.confirmations.rejectedAmong(any(), any()) } returnsMany listOf(listOf(pair), emptyList())
        coEvery { fixture.confirmations.confirmAsUser(listOf(pair)) } returns true
        advanceUntilIdle()
        var first: LinkGroupMutationResult? = null
        var second: LinkGroupMutationResult? = null
        val add = { onResult: (LinkGroupMutationResult) -> Unit -> fixture.model.addMangaToGroup("existing", listOf(manga(2L, "a")), onResult) }

        add { first = it }
        advanceUntilIdle()
        fixture.model.confirmSameManga(first!!.conflicts) { add { second = it } }
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.confirmations.confirmAsUser(listOf(pair)) }
        coVerify(exactly = 1) { restore.await(any(), emptyList(), emptyList(), emptyList()) }
        assertTrue(second!!.succeeded)
    }

    @Test
    fun `removing a title takes back what grouping confirmed between it and its former group`() = runTest {
        val selected = link("g1", 1L, "selected")
        val peer = link("g1", 2L, "peer")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(selected, peer), restore)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        fixture.model.ungroupMember(selected.source, selected.url) { result = it }
        advanceUntilIdle()

        coVerify {
            fixture.confirmations.reverseBetween(
                tachiyomi.domain.taste.model.CrossSourceRecordKey(1L, "selected"),
                listOf(tachiyomi.domain.taste.model.CrossSourceRecordKey(2L, "peer")),
                result!!.undoEntryId,
            )
        }
    }

    @Test
    fun `undo puts identity back the way the grouping found it`() = runTest {
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(link("existing", 1L, "kept")), restore)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null
        fixture.model.addMangaToGroup("existing", listOf(manga(2L, "a"))) { result = it }
        advanceUntilIdle()
        // the undo sees the rows it wrote
        fixture.links += link("existing", 2L, "a")
        var undone: exh.util.GroupUndoOutcome? = null

        fixture.model.undo(result!!.undoEntryId!!) { undone = it }
        advanceUntilIdle()

        assertEquals(GroupUndoResult.RESTORED, undone?.result)
        coVerify { fixture.confirmations.reverseForAction(result!!.undoEntryId!!) }
        coVerify { fixture.confirmations.restoreClearedBy(result!!.undoEntryId!!) }
    }

    @Test
    fun `the link and its sharing are reported separately`() = runTest {
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(link("existing", 1L, "kept")), restore)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        // no synchronizer can be built in this test, which is exactly a sharing step failing after the link was saved
        fixture.model.addMangaToGroup("existing", listOf(manga(2L, "a"))) { result = it }
        advanceUntilIdle()

        assertTrue(result!!.succeeded, "the link is saved")
        assertEquals(LinkGroupSharing.INCOMPLETE, result!!.sharing)
        assertEquals("existing", result!!.groupId, "so the screen can offer Retry for this group")
    }

    @Test
    fun `the primary version is listed first and Set as primary saves it with an undo`() = runTest {
        val a = link("g", 1L, "a")
        val b = link("g", 2L, "b")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(
            listOf(a, b),
            restore,
            initialPrimaries = listOf(tachiyomi.domain.taste.model.CrossSourceGroupPrimary("g", 2L, "b", 5L)),
        )
        advanceUntilIdle()
        val group = (fixture.model.state.value as LinkGroupManagementScreenModel.State.Success).groups.single()
        assertEquals("b", group.members.first().link.url, "the primary version comes first")
        var result: LinkGroupMutationResult? = null

        fixture.model.setPrimaryVersion("g", 1L, "a") { result = it }
        advanceUntilIdle()

        coVerify { fixture.setPrimary.await("g", 1L, "a") }
        assertTrue(result!!.succeeded)
        assertEquals(exh.util.GroupJournalActionType.SET_PRIMARY, GroupUndoJournal.snapshot().single().actionType)
    }
    // KMK <--

    @Test
    fun `move touches only selected member and journals undo after atomic write succeeds`() = runTest {
        val a = link("old", 1L, "a")
        val b = link("old", 2L, "b")
        val c = link("destination", 3L, "c")
        val restore = mockk<RestoreCrossSourceGroupState>()
        val fixture = model(listOf(a, b, c), restore)
        coEvery { restore.await(any(), any(), any(), any()) } answers {
            val upserts = firstArg<List<CrossSourceMangaLink>>()
            val deletes = secondArg<List<Pair<Long, String>>>()
            fixture.links.removeAll { row -> deletes.any { it.first == row.source && it.second == row.url } }
            upserts.forEach { row ->
                fixture.links.removeAll { it.source == row.source && it.url == row.url }
                fixture.links += row
            }
        }
        advanceUntilIdle()

        var result: LinkGroupMutationResult? = null
        fixture.model.moveMember(1L, "a", "destination") { result = it }
        advanceUntilIdle()

        assertTrue(result?.succeeded == true)
        assertTrue(result?.undoEntryId != null)
        val writes = io.mockk.slot<List<CrossSourceMangaLink>>()
        coVerify(exactly = 1) { restore.await(capture(writes), emptyList(), emptyList(), emptyList()) }
        assertEquals(listOf("a"), writes.captured.map { it.url })
        assertEquals("destination", writes.captured.single().groupId)
        assertEquals(1, GroupUndoJournal.snapshot().size)

        var undoResult: exh.util.GroupUndoOutcome? = null
        fixture.model.undo(result!!.undoEntryId!!) { undoResult = it }
        advanceUntilIdle()
        assertEquals(GroupUndoResult.RESTORED, undoResult?.result)
        assertEquals("old", fixture.links.single { it.source == 1L && it.url == "a" }.groupId)
        assertTrue(GroupUndoJournal.isEmpty())
    }

    @Test
    fun `partial persistence failure reports failure and does not offer undo`() = runTest {
        val a = link("old", 1L, "a")
        val b = link("destination", 2L, "b")
        val restore = mockk<RestoreCrossSourceGroupState>()
        coEvery { restore.await(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")
        val fixture = model(listOf(a, b), restore)
        advanceUntilIdle()

        var result: LinkGroupMutationResult? = null
        fixture.model.moveMember(1L, "a", "destination") { result = it }
        advanceUntilIdle()

        assertFalse(result?.succeeded ?: true)
        assertEquals(null, result?.undoEntryId)
        assertTrue(GroupUndoJournal.isEmpty())
    }

    @Test
    fun `add persists every selected candidate as one reversible mutation`() = runTest {
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(link("existing", 1L, "kept")), restore)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        fixture.model.addMangaToGroup("existing", listOf(manga(2L, "a"), manga(3L, "b"))) { result = it }
        advanceUntilIdle()

        val writes = io.mockk.slot<List<CrossSourceMangaLink>>()
        coVerify { restore.await(capture(writes), emptyList(), emptyList(), emptyList()) }
        assertEquals(setOf("a", "b"), writes.captured.map { it.url }.toSet())
        assertTrue(writes.captured.all { it.groupId == "existing" })
        assertTrue(result?.succeeded == true && result?.undoEntryId != null)
        assertEquals(2, GroupUndoJournal.snapshot().single().touchedKeys.size)
    }

    @Test
    fun `create makes one group from selected members and leaves other groups intact`() = runTest {
        val existing = link("untouched", 9L, "outside")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(existing), restore)
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        fixture.model.createGroup(listOf(manga(1L, "a"), manga(2L, "b"))) { result = it }
        advanceUntilIdle()

        val linkWrites = io.mockk.slot<List<CrossSourceMangaLink>>()
        val primaryWrites = io.mockk.slot<List<tachiyomi.domain.taste.model.CrossSourceGroupPrimary>>()
        coVerify { restore.await(capture(linkWrites), emptyList(), capture(primaryWrites), emptyList()) }
        assertEquals(setOf(1L to "a", 2L to "b"), linkWrites.captured.map { it.source to it.url }.toSet())
        assertEquals(1, linkWrites.captured.map { it.groupId }.distinct().size)
        assertEquals(linkWrites.captured.first().groupId, primaryWrites.captured.single().groupId)
        assertEquals("untouched", existing.groupId)
        assertTrue(result?.succeeded == true)
    }

    @Test
    fun `ungrouping one member leaves its group peers and other groups untouched`() = runTest {
        val selected = link("g1", 1L, "selected")
        val peer = link("g1", 2L, "peer")
        val unrelated = link("g2", 3L, "unrelated")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(listOf(selected, peer, unrelated), restore)
        coEvery { restore.await(any(), any(), any(), any()) } answers {
            val deletes = secondArg<List<Pair<Long, String>>>()
            fixture.links.removeAll { row -> deletes.any { it.first == row.source && it.second == row.url } }
        }
        advanceUntilIdle()
        var result: LinkGroupMutationResult? = null

        fixture.model.ungroupMember(selected.source, selected.url) { result = it }
        advanceUntilIdle()

        val deletes = io.mockk.slot<List<Pair<Long, String>>>()
        coVerify { restore.await(emptyList(), capture(deletes), emptyList(), emptyList()) }
        assertEquals(listOf(1L to "selected"), deletes.captured)
        assertTrue(result?.succeeded == true)
        assertEquals(setOf("peer", "unrelated"), fixture.links.map { it.url }.toSet())
        assertEquals("g1", peer.groupId)
        assertEquals("g2", unrelated.groupId)
    }

    @Test
    fun `merge moves only the chosen source group into the destination`() = runTest {
        val from = listOf(link("from", 1L, "a"), link("from", 2L, "b"))
        val into = link("into", 3L, "c")
        val unrelated = link("other", 4L, "d")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(from + into + unrelated, restore)
        advanceUntilIdle()

        fixture.model.mergeGroups("from", "into")
        advanceUntilIdle()

        val writes = io.mockk.slot<List<CrossSourceMangaLink>>()
        coVerify { restore.await(capture(writes), emptyList(), emptyList(), emptyList()) }
        assertEquals(setOf("a", "b"), writes.captured.map { it.url }.toSet())
        assertTrue(writes.captured.all { it.groupId == "into" })
        assertEquals("other", unrelated.groupId)
    }

    @Test
    fun `ungroup removes every member only from the selected group`() = runTest {
        val selected = listOf(link("g1", 1L, "a"), link("g1", 2L, "b"))
        val unrelated = link("g2", 3L, "c")
        val restore = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val fixture = model(selected + unrelated, restore)
        coEvery { restore.await(any(), any(), any(), any()) } answers {
            val deletes = secondArg<List<Pair<Long, String>>>()
            fixture.links.removeAll { row -> deletes.any { it.first == row.source && it.second == row.url } }
        }
        advanceUntilIdle()

        fixture.model.ungroupGroup("g1")
        advanceUntilIdle()

        val deletes = io.mockk.slot<List<Pair<Long, String>>>()
        coVerify { restore.await(emptyList(), capture(deletes), emptyList(), emptyList()) }
        assertEquals(setOf(1L to "a", 2L to "b"), deletes.captured.toSet())
        assertEquals(listOf("c"), fixture.links.map { it.url })
    }

    @Test
    fun `successful group ungroup undo restores selected members and primary only`() = runTest {
        val selected = listOf(link("g1", 1L, "a"), link("g1", 2L, "b"))
        val unrelated = listOf(link("g2", 3L, "c"), link("g2", 4L, "d"))
        val selectedPrimary = tachiyomi.domain.taste.model.CrossSourceGroupPrimary("g1", 2L, "b", 5L)
        val unrelatedPrimary = tachiyomi.domain.taste.model.CrossSourceGroupPrimary("g2", 3L, "c", 6L)
        val restore = mockk<RestoreCrossSourceGroupState>()
        val fixture = model(selected + unrelated, restore, listOf(selectedPrimary, unrelatedPrimary))
        coEvery { restore.await(any(), any(), any(), any()) } answers {
            val linkUpserts = firstArg<List<CrossSourceMangaLink>>()
            val linkDeletes = secondArg<List<Pair<Long, String>>>()
            val primaryUpserts = thirdArg<List<tachiyomi.domain.taste.model.CrossSourceGroupPrimary>>()
            val primaryDeletes = arg<List<String>>(3)
            fixture.links.removeAll { row ->
                linkDeletes.any { it.first == row.source && it.second == row.url } ||
                    linkUpserts.any { it.source == row.source && it.url == row.url }
            }
            fixture.links += linkUpserts
            fixture.primaries.removeAll { row ->
                row.groupId in primaryDeletes || primaryUpserts.any { it.groupId == row.groupId }
            }
            fixture.primaries += primaryUpserts
        }
        advanceUntilIdle()

        var mutation: LinkGroupMutationResult? = null
        fixture.model.ungroupGroup("g1") { mutation = it }
        advanceUntilIdle()

        assertTrue(mutation?.succeeded == true)
        assertTrue(mutation?.undoEntryId != null)
        assertEquals(unrelated.toSet(), fixture.links.toSet())
        assertEquals(listOf(unrelatedPrimary), fixture.primaries)

        var undo: exh.util.GroupUndoOutcome? = null
        fixture.model.undo(mutation!!.undoEntryId!!) { undo = it }
        advanceUntilIdle()

        assertEquals(GroupUndoResult.RESTORED, undo?.result)
        assertEquals((selected + unrelated).toSet(), fixture.links.toSet())
        assertEquals(setOf(selectedPrimary, unrelatedPrimary), fixture.primaries.toSet())
        assertTrue(GroupUndoJournal.isEmpty())
    }

    @Test
    fun `cancellation during group ungroup skips failure callback and leaves state unchanged`() = runTest {
        val selected = listOf(link("g1", 1L, "a"), link("g1", 2L, "b"))
        val restore = mockk<RestoreCrossSourceGroupState>()
        coEvery { restore.await(any(), any(), any(), any()) } throws CancellationException("scope cancelled")
        val fixture = model(selected, restore)
        advanceUntilIdle()
        var callbackCalled = false

        fixture.model.ungroupGroup("g1") { callbackCalled = true }
        advanceUntilIdle()

        assertFalse(callbackCalled)
        assertEquals(selected, fixture.links)
        assertTrue(GroupUndoJournal.isEmpty())
        val state = fixture.model.state.value as LinkGroupManagementScreenModel.State.Success
        assertEquals(setOf("a", "b"), state.allGroups.single().links.map { it.url }.toSet())
    }
}
