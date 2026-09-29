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
        val screen = LinkGroupManagementScreenModel(
            getCrossSourceMangaLinks = linkReader,
            mangaRepository = manga,
            sourceManager = mockk<SourceManager>(relaxed = true),
            getCrossSourceGroupPrimary = primaryReader,
            restoreCrossSourceGroupState = restore,
            groupUndoService = GroupUndoService(linkReader, primaryReader, restore),
        )
        return Fixture(screen, links, primaries)
    }

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
