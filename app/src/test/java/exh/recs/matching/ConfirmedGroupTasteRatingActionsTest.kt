package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.ClearMangaTaste
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.InsertMangaTasteIfAbsent
import tachiyomi.domain.taste.interactor.SetMangaTasteBatch
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

class ConfirmedGroupTasteRatingActionsTest {
    private val origin = manga(1, "/origin")
    private val sibling = manga(2, "/sibling")
    private val member = manga(3, "/member")
    private val unconfirmed = manga(4, "/unconfirmed")
    private val preferences = SourcePreferences(FakePreferenceStore())
    private val links = mockk<GetCrossSourceMangaLinks>()
    private val getManga = mockk<GetManga>()
    private val getTaste = mockk<GetMangaTaste>()
    private val setTaste = mockk<SetMangaTasteBatch>(relaxed = true)
    private val insertTaste = mockk<InsertMangaTasteIfAbsent>()
    private val clearTaste = mockk<ClearMangaTaste>(relaxed = true)
    private val tracker = mockk<LocalTrackerRepository>(relaxed = true)
    private val resolver = mockk<CrossSourceIdentityAuthorizationResolver>()

    @BeforeEach
    fun setUp() {
        exh.util.EvaluationModeUndoJournal.clear()
    }

    @AfterEach
    fun tearDown() {
        exh.util.EvaluationModeUndoJournal.clear()
    }

    private fun actions() = ConfirmedGroupTasteRatingManager(
        sourcePreferences = preferences,
        getCrossSourceMangaLinks = links,
        getManga = getManga,
        getMangaTaste = getTaste,
        setMangaTasteBatch = setTaste,
        insertMangaTasteIfAbsent = insertTaste,
        clearMangaTaste = clearTaste,
        localTrackerRepository = tracker,
        identityResolver = resolver,
    )

    private fun setupGroup() {
        val originLink = link(origin)
        val rows = listOf(originLink, link(sibling), link(member), link(unconfirmed))
        coEvery { links.awaitBySourceUrl(origin.source, origin.url) } returns originLink
        coEvery { links.awaitByGroupId("group") } returns rows
        coEvery { resolver.confirmedGroupMembers(origin.source, origin.url, rows) } returns
            listOf(originLink, link(sibling), link(member))
        coEvery { getManga.await(origin.url, origin.source) } returns origin
        coEvery { getManga.await(sibling.url, sibling.source) } returns sibling
        coEvery { getManga.await(member.url, member.source) } returns member
        coEvery { tracker.getWorkIdBySourceUrl(sibling.source, sibling.url) } returns "sibling-work"
        coEvery { tracker.getWorkIdBySourceUrl(member.source, member.url) } returns "member-work"
    }

    @Test
    fun `confirmed tracked join inherits latest group rating but ignores unconfirmed and unrelated rows`() = runTest {
        setupGroup()
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.LIKE, 100)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns taste(sibling, MangaRating.LOVE, 200)
        coEvery { getTaste.await(member.source, member.url) } returns null
        coEvery { insertTaste.await(member, MangaRating.LOVE) } returns true

        actions().propagateToConfirmedMember(origin, member)

        coVerify(exactly = 1) { insertTaste.await(member, MangaRating.LOVE) }
        coVerify(exactly = 0) { getManga.await(unconfirmed.url, unconfirmed.source) }
    }

    @Test
    fun `confirmed tracked join sync uses the saved source row when its manga is not in the library`() = runTest {
        setupGroup()
        coEvery { getManga.await(member.url, member.source) } returns null
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.LOVE, 100)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns null
        coEvery { getTaste.await(member.source, member.url) } returns null
        coEvery { insertTaste.await(member, MangaRating.LOVE) } returns true

        actions().propagateToConfirmedMember(origin, member)

        coVerify(exactly = 1) { insertTaste.await(member, MangaRating.LOVE) }
    }

    @Test
    fun `disabled preference and existing manual destination rating both prevent automatic overwrite`() = runTest {
        setupGroup()
        preferences.confirmedTrackedVersionRatingPropagationEnabled().set(false)
        actions().propagateToConfirmedMember(origin, member)
        coVerify(exactly = 0) { links.awaitBySourceUrl(any(), any()) }
        coVerify(exactly = 0) { setTaste.await(any(), any()) }

        preferences.confirmedTrackedVersionRatingPropagationEnabled().set(true)
        setupGroup()
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.LOVE, 200)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns taste(sibling, MangaRating.LIKE, 100)
        coEvery { getTaste.await(member.source, member.url) } returns taste(member, MangaRating.DISLIKE, 300)

        actions().propagateToConfirmedMember(origin, member)

        coVerify(exactly = 0) { setTaste.await(any(), any()) }
    }

    @Test
    fun `manual rating winning atomic insert race is preserved`() = runTest {
        setupGroup()
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.LOVE, 200)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns null
        coEvery { getTaste.await(member.source, member.url) } returns null
        coEvery { insertTaste.await(member, MangaRating.LOVE) } returns false

        actions().propagateToConfirmedMember(origin, member)

        coVerify(exactly = 1) { insertTaste.await(member, MangaRating.LOVE) }
        coVerify(exactly = 0) { setTaste.await(any(), any()) }
        assertEquals(0, exh.util.EvaluationModeUndoJournal.snapshot().size)
    }

    @Test
    fun `current rating follows most recently updated confirmed member`() = runTest {
        setupGroup()
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.DISLIKE, 10)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns taste(sibling, MangaRating.NOT_INTERESTED, 20)
        coEvery { getTaste.await(member.source, member.url) } returns null

        assertEquals(MangaRating.NOT_INTERESTED, actions().currentRating(origin))
    }

    @Test
    fun `unconfirmed member cannot receive automatic group rating`() = runTest {
        setupGroup()
        coEvery { getManga.await(unconfirmed.url, unconfirmed.source) } returns null

        actions().propagateToConfirmedMember(origin, unconfirmed)

        coVerify(exactly = 0) { getTaste.await(any(), any()) }
        coVerify(exactly = 0) { setTaste.await(any(), any()) }
        coVerify(exactly = 0) { insertTaste.await(any(), any()) }
        coVerify(exactly = 0) { getManga.await(unconfirmed.url, unconfirmed.source) }
    }

    @Test
    fun `confirmed but not locally tracked member is not given automatic group rating`() = runTest {
        setupGroup()
        coEvery { getManga.await(member.url, member.source) } returns null
        coEvery { tracker.getWorkIdBySourceUrl(member.source, member.url) } returns null

        actions().propagateToConfirmedMember(origin, member)

        coVerify(exactly = 0) { getTaste.await(any(), any()) }
        coVerify(exactly = 0) { setTaste.await(any(), any()) }
        coVerify(exactly = 0) { insertTaste.await(any(), any()) }
    }

    @Test
    fun `confirmed member receives inherited rating only after local tracking exists`() = runTest {
        setupGroup()
        coEvery { tracker.getWorkIdBySourceUrl(member.source, member.url) } returns null
        coEvery { getTaste.await(origin.source, origin.url) } returns taste(origin, MangaRating.LIKE, 100)
        coEvery { getTaste.await(sibling.source, sibling.url) } returns null
        coEvery { getTaste.await(member.source, member.url) } returns null
        coEvery { insertTaste.await(member, MangaRating.LIKE) } returns true
        val actions = actions()

        actions.propagateToConfirmedMember(origin, member)
        coVerify(exactly = 0) { insertTaste.await(any(), any()) }

        coEvery { tracker.getWorkIdBySourceUrl(member.source, member.url) } returns "migration-created-work"
        actions.propagateToConfirmedMember(origin, member)

        coVerify(exactly = 1) { insertTaste.await(member, MangaRating.LIKE) }
    }

    @Test
    fun `group set and clear affect only confirmed locally tracked members`() = runTest {
        setupGroup()
        coEvery { getTaste.await(any(), any()) } returns null
        coEvery { tracker.getWorkIdBySourceUrl(origin.source, origin.url) } returns "origin-work"

        actions().setGroupRating(origin, MangaRating.DISLIKE)
        actions().setGroupRating(origin, null)

        coVerify(exactly = 1) { setTaste.await(listOf(origin, sibling, member), MangaRating.DISLIKE) }
        coVerify(exactly = 1) { clearTaste.await(origin.source, origin.url) }
        coVerify(exactly = 1) { clearTaste.await(sibling.source, sibling.url) }
        coVerify(exactly = 1) { clearTaste.await(member.source, member.url) }
        coVerify(exactly = 0) { clearTaste.await(unconfirmed.source, unconfirmed.url) }
        assertEquals(6, exh.util.EvaluationModeUndoJournal.snapshot().size)
    }

    @Test
    fun `unset group has no effective rating`() = runTest {
        setupGroup()
        coEvery { getTaste.await(any(), any()) } returns null

        assertNull(actions().currentRating(origin))
    }

    private fun manga(source: Long, url: String) = Manga.create().copy(source = source.toLong(), url = url, ogTitle = url)

    private fun link(manga: Manga) =
        CrossSourceMangaLink(manga.source, manga.url, "group", manga.url, 1L, 1L)

    private fun taste(manga: Manga, rating: MangaRating, updatedAt: Long) = MangaTaste(
        mangaId = manga.id,
        source = manga.source,
        url = manga.url,
        title = manga.title,
        rating = rating.value,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
