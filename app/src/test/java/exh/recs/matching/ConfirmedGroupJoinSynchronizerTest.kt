package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.ShareTrackerBindingWithLinkedVersions
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.track.interactor.GetTracks
import java.io.File

/**
 * v0.8.25: adding titles to a link group from the group manager or the rated list used to save the link and stop, so the new
 * titles never got the group's confirmation, tracking, rating or tracker entry.
 */
class ConfirmedGroupJoinSynchronizerTest {

    private val existing = manga(1, "/existing")
    private val added = manga(2, "/added")
    private val addedToo = manga(3, "/added-too")
    private val rows = listOf(link(existing), link(added), link(addedToo))

    private val links = mockk<GetCrossSourceMangaLinks>()
    private val getManga = mockk<GetManga>()
    private val getTracks = mockk<GetTracks>()
    private val identity = mockk<CrossSourceIdentityDecisionController>()
    private val tracking = mockk<ConfirmedGroupLocalTrackingPropagator>()
    private val rating = mockk<ConfirmedGroupTasteRatingActions>(relaxed = true)
    private val share = mockk<ShareTrackerBindingWithLinkedVersions>(relaxed = true)
    private var replays = 0
    private val order = mutableListOf<String>()

    private fun synchronizer() = ConfirmedGroupJoinSynchronizer(
        links,
        getManga,
        getTracks,
        identity,
        tracking,
        rating,
        share,
        SourcePreferences(FakePreferenceStore()),
        progressReplay = {
            order += "replay"
            replays++
        },
        chapterCount = { if (it.id == added.id) 0 else 5 },
        fetchChapters = { order += "fetch ${it.id}" },
        backgroundScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun stub() {
        coEvery { links.awaitByGroupId("g") } returns rows
        listOf(existing, added, addedToo).forEach { coEvery { getManga.await(it.url, it.source) } returns it }
        coEvery { identity.confirmUnlessRejected(any()) } returns CrossSourceIdentityConfirmOutcome(CrossSourceIdentityMutationResult.APPLIED)
        coEvery { tracking.propagateIfAnyTracked(any(), any()) } returns true
        coEvery { getTracks.await(existing.id) } returns emptyList()
    }

    @Test
    fun `new members are confirmed against an existing member, then tracked, then rated`() = runTest {
        stub()
        val ok = synchronizer().onMembersJoined("g", listOf(added.source to added.url, addedToo.source to addedToo.url))
        assertTrue(ok)
        coVerifyOrder {
            identity.confirmUnlessRejected(match { it.size == 2 })
            tracking.propagateIfAnyTracked(any(), any())
            rating.applyGroupRatingToJoinedMember(rows, added)
            rating.applyGroupRatingToJoinedMember(rows, addedToo)
        }
        coVerify(exactly = 0) { rating.applyGroupRatingToJoinedMember(any(), existing) }
        assertEquals(2, replays, "progress is replayed at once, and again after a version's chapters have loaded")
        assertEquals(listOf("replay", "fetch ${added.id}", "replay"), order, "the quick replay comes first; the slow chapter load comes after")
    }

    // KMK v0.8.25 -->
    @Test
    fun `a version the user marked Not the same manga is reported and gets nothing from the group`() = runTest {
        stub()
        val rejectedPair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
            tachiyomi.domain.taste.model.CrossSourceRecordKey(existing.source, existing.url),
            tachiyomi.domain.taste.model.CrossSourceRecordKey(added.source, added.url),
        )
        coEvery { identity.confirmUnlessRejected(any()) } returns
            CrossSourceIdentityConfirmOutcome(CrossSourceIdentityMutationResult.APPLIED, rejected = listOf(rejectedPair))

        val outcome = synchronizer().join("g", listOf(added.source to added.url, addedToo.source to addedToo.url))

        assertEquals(listOf(rejectedPair), outcome.rejected)
        coVerify(exactly = 0) { rating.applyGroupRatingToJoinedMember(any(), added) }
        coVerify { rating.applyGroupRatingToJoinedMember(listOf(link(existing), link(addedToo)), addedToo) }
        coVerify { tracking.propagateIfAnyTracked(match { list -> list.none { it.id == added.id } }, any()) }
    }
    // KMK <--

    @Test
    fun `the title the user is on receives the group rating when it is among the receivers`() = runTest {
        stub()
        synchronizer().onMembersJoined("g", listOf(existing.source to existing.url, added.source to added.url))
        coVerify { rating.applyGroupRatingToJoinedMember(rows, existing) }
        coVerify { rating.applyGroupRatingToJoinedMember(rows, added) }
    }

    @Test
    fun `the rating is applied before any chapter list is loaded`() = runTest {
        stub()
        val seen = mutableListOf<String>()
        io.mockk.coEvery { rating.applyGroupRatingToJoinedMember(any(), any()) } answers { seen += "rating" }
        ConfirmedGroupJoinSynchronizer(
            links, getManga, getTracks, identity, tracking, rating, share, SourcePreferences(FakePreferenceStore()),
            progressReplay = { seen += "replay" },
            chapterCount = { 0 },
            fetchChapters = { seen += "fetch" },
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        ).onMembersJoined("g", listOf(added.source to added.url))
        assertEquals("rating", seen.first(), "the rating never waits for a network call")
        assertTrue(seen.indexOf("rating") < seen.indexOf("fetch"))
    }

    @Test
    fun `a failing step does not stop the later steps`() = runTest {
        stub()
        coEvery { tracking.propagateIfAnyTracked(any(), any()) } throws IllegalStateException("boom")
        val ok = synchronizer().onMembersJoined("g", listOf(added.source to added.url))
        assertFalse(ok)
        coVerify { rating.applyGroupRatingToJoinedMember(rows, added) }
    }

    @Test
    fun `both screens that only write link rows call the synchronizer`() {
        val manager = File("src/main/java/exh/recs/links/LinkGroupManagementScreenModel.kt").readText()
        val rated = File("src/main/java/exh/recs/loved/LovedMangaScreenModel.kt").readText()
        // v0.8.25: the group manager uses join(), which also reports "Not the same manga" pairs and ties confirmations to the action
        assertTrue(manager.contains("joinSynchronizer.value.join(groupId, joined, actionId)"))
        listOf("ADD_MEMBER", "MOVE_MEMBER", "MERGE").forEach { assertTrue(manager.contains("actionType = GroupJournalActionType.$it")) }
        assertTrue(Regex("\\.joined\\(").findAll(manager).count() >= 4, "add, create, move and merge each sync")
        assertTrue(rated.contains("joinSynchronizer.value.onMembersJoined(plan.targetGroupId"))
    }

    @Test
    fun `tracking other versions from the manga screen finishes with the same sync`() {
        val match = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()
        val dialog = File("src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt").readText()
        assertTrue(match.contains("syncJoinedVersions(origin, targets)"))
        assertTrue(match.contains("(targets + origin)"), "the title the user is on receives the group's rating too")
        assertTrue(match.contains("ConfirmedGroupJoinSynchronizer().onMembersJoined(groupId"))
        assertTrue(dialog.contains("if (targets.size > 1) syncTrackedVersions(manga, targets)"))
        assertTrue(dialog.contains("ConfirmedGroupJoinSynchronizer()"))
    }

    private fun manga(source: Long, url: String) = Manga.create().copy(id = source, source = source, url = url, ogTitle = url)

    private fun link(manga: Manga) = CrossSourceMangaLink(manga.source, manga.url, "g", manga.url, 1L, 1L)
}
