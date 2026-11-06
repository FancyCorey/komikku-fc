package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
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

/**
 * v0.8.25: a title put into a group takes the group's rating even when it has no local tracking yet. The older
 * "propagate to confirmed member" path required tracking first, so most newly grouped titles never got a rating.
 */
class ConfirmedGroupJoinRatingTest {
    private val rated = manga(1, "/rated")
    private val joined = manga(2, "/joined")
    private val preferences = SourcePreferences(FakePreferenceStore())
    private val getTaste = mockk<GetMangaTaste>()
    private val insertTaste = mockk<InsertMangaTasteIfAbsent>()
    private val rows = listOf(link(rated), link(joined))

    @BeforeEach
    fun setUp() = exh.util.EvaluationModeUndoJournal.clear()

    @AfterEach
    fun tearDown() = exh.util.EvaluationModeUndoJournal.clear()

    private fun actions() = ConfirmedGroupTasteRatingManager(
        sourcePreferences = preferences,
        getCrossSourceMangaLinks = mockk<GetCrossSourceMangaLinks>(),
        getManga = mockk<GetManga>(),
        getMangaTaste = getTaste,
        setMangaTasteBatch = mockk<SetMangaTasteBatch>(relaxed = true),
        insertMangaTasteIfAbsent = insertTaste,
        clearMangaTaste = mockk<ClearMangaTaste>(relaxed = true),
        localTrackerRepository = mockk<LocalTrackerRepository>(relaxed = true),
        identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>(),
    )

    @Test
    fun `an untracked joined title takes the rating of the group`() = runTest {
        coEvery { getTaste.await(rated.source, rated.url) } returns taste(rated, MangaRating.LOVE, 10)
        coEvery { getTaste.await(joined.source, joined.url) } returns null
        coEvery { insertTaste.await(joined, MangaRating.LOVE) } returns true
        actions().applyGroupRatingToJoinedMember(rows, joined)
        coVerify { insertTaste.await(joined, MangaRating.LOVE) }
    }

    @Test
    fun `a joined title that already has its own rating keeps it`() = runTest {
        coEvery { getTaste.await(rated.source, rated.url) } returns taste(rated, MangaRating.LOVE, 10)
        coEvery { getTaste.await(joined.source, joined.url) } returns taste(joined, MangaRating.DISLIKE, 5)
        actions().applyGroupRatingToJoinedMember(rows, joined)
        coVerify(exactly = 0) { insertTaste.await(any(), any()) }
    }

    @Test
    fun `turning rating sharing off leaves the joined title unrated`() = runTest {
        preferences.confirmedTrackedVersionRatingPropagationEnabled().set(false)
        coEvery { getTaste.await(rated.source, rated.url) } returns taste(rated, MangaRating.LOVE, 10)
        coEvery { getTaste.await(joined.source, joined.url) } returns null
        actions().applyGroupRatingToJoinedMember(rows, joined)
        coVerify(exactly = 0) { insertTaste.await(any(), any()) }
    }

    private fun manga(source: Long, url: String) = Manga.create().copy(id = source, source = source, url = url, ogTitle = url)

    private fun link(manga: Manga) = CrossSourceMangaLink(manga.source, manga.url, "group", manga.url, 1L, 1L)

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
