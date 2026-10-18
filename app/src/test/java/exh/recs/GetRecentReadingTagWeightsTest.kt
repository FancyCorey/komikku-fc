package exh.recs

import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.RecentReadingActivity
import tachiyomi.domain.history.repository.RecentReadingRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.taste.model.TagPreference
import tachiyomi.domain.taste.model.TasteProfile
import tachiyomi.domain.taste.model.normalizeTag

// KMK --> v0.8.24 recent-reading profile
class GetRecentReadingTagWeightsTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun manga(id: Long, source: Long = 1L, genres: List<String> = emptyList()) =
        Manga.create().copy(id = id, source = source, url = "/m$id", ogGenre = genres)

    private fun activity(id: Long, read: Long = 20, total: Long = 20, days: Long = 3, ageDays: Long = 0) =
        RecentReadingActivity(id, read, days, now - ageDays * day, total)

    private fun taste(mangaId: Long, rating: MangaRating) =
        MangaTaste(mangaId, 1L, "/m$mangaId", "t$mangaId", rating.value, 0L, 0L)

    private fun key(genre: String) = genre.normalizeTag()

    private class Fixture(
        val repository: RecentReadingRepository,
        val interactor: GetRecentReadingTagWeights,
        val preferences: SourcePreferences,
    )

    private fun fixture(
        activities: List<RecentReadingActivity>,
        mangas: List<Manga>,
        tastes: List<MangaTaste> = emptyList(),
        links: Map<Manga, CrossSourceMangaLink> = emptyMap(),
        groupMembers: Map<String, List<CrossSourceMangaLink>> = emptyMap(),
        repositoryFailure: Throwable? = null,
    ): Fixture {
        val repository = mockk<RecentReadingRepository>()
        if (repositoryFailure != null) {
            coEvery { repository.getRecentReadingActivity(any()) } throws repositoryFailure
        } else {
            coEvery { repository.getRecentReadingActivity(any()) } returns activities
        }
        val mangaRepository = mockk<MangaRepository>()
        mangas.forEach { coEvery { mangaRepository.getMangaById(it.id) } returns it }
        val getMangaTaste = mockk<GetMangaTaste>()
        coEvery { getMangaTaste.awaitAll() } returns tastes
        val getLinks = mockk<GetCrossSourceMangaLinks>()
        coEvery { getLinks.awaitBySourceUrl(any(), any()) } returns null
        links.forEach { (m, link) -> coEvery { getLinks.awaitBySourceUrl(m.source, m.url) } returns link }
        groupMembers.forEach { (groupId, members) -> coEvery { getLinks.awaitByGroupId(groupId) } returns members }
        val resolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        coEvery { resolver.confirmedGroupMembers(any(), any(), any()) } answers { thirdArg() }
        val preferences = SourcePreferences(FakePreferenceStore())
        val interactor = GetRecentReadingTagWeights(
            recentReadingRepository = repository,
            mangaRepository = mangaRepository,
            getMangaTaste = getMangaTaste,
            getCrossSourceMangaLinks = getLinks,
            sourcePreferences = preferences,
            identityAuthorizationResolver = resolver,
            clock = { now },
        )
        return Fixture(repository, interactor, preferences)
    }

    @Test
    fun `turned off it does nothing and does not read history`() = runTest {
        val f = fixture(listOf(activity(1)), listOf(manga(1, genres = listOf("Action"))))
        f.preferences.recommendationRecentReadingEnabled().set(false)

        assertEquals(emptyMap<String, Double>(), f.interactor.await(TasteProfile.EMPTY, emptyMap()))
        coVerify(exactly = 0) { f.repository.getRecentReadingActivity(any()) }
    }

    @Test
    fun `recent reading adds positive weights to each tag and honours the configured window`() = runTest {
        val f = fixture(listOf(activity(1)), listOf(manga(1, genres = listOf("Action", "Romance"))))
        f.preferences.recommendationRecentReadingWindowDays().set(3)

        val weights = f.interactor.await(TasteProfile.EMPTY, emptyMap())

        coVerify(exactly = 1) { f.repository.getRecentReadingActivity(now - 3 * day) }
        assertEquals(setOf(key("Action"), key("Romance")), weights.keys)
        assertTrue(weights.getValue(key("Action")) > 2.0, "a read-through recent manga outweighs a Love rating")
    }

    @Test
    fun `tag aliases are applied so aliased tags share one group`() = runTest {
        val f = fixture(
            listOf(activity(1)),
            listOf(manga(1, genres = listOf("Martial Arts", "Wuxia"))),
        )
        val aliasMap = mapOf(key("Wuxia") to key("Martial Arts"))

        val weights = f.interactor.await(TasteProfile.EMPTY, aliasMap)

        assertEquals(setOf(key("Martial Arts")), weights.keys)
        // One manga contributes once per distinct group, not once per alias.
        assertEquals(
            RecentReadingPolicy.finalGroupWeight(
                RecentReadingPolicy.perGenreWeight(RecentReadingPolicy.strength(activity(1), 7, 3, now)),
            ),
            weights.getValue(key("Martial Arts")),
            0.0,
        )
    }

    @Test
    fun `explicit block and explicit dislike tags never receive a recent weight`() = runTest {
        val f = fixture(
            listOf(activity(1)),
            listOf(manga(1, genres = listOf("Action", "Romance", "Horror"))),
        )
        val profile = TasteProfile(
            learnedTagWeights = emptyMap(),
            explicitTagPreferences = mapOf(
                key("Romance") to TagPreference.DISLIKE.value,
                key("Horror") to TagPreference.BLOCK.value,
            ),
            sourceAffinity = emptyMap(),
            blockedGroups = setOf(key("Horror")),
        )

        val weights = f.interactor.await(profile, emptyMap())

        assertEquals(setOf(key("Action")), weights.keys)
    }

    @Test
    fun `titles rated Dislike or Not interested are not counted as recent interest`() = runTest {
        val f = fixture(
            activities = listOf(activity(1), activity(2), activity(3)),
            mangas = listOf(
                manga(1, genres = listOf("Horror")),
                manga(2, genres = listOf("Drama")),
                manga(3, genres = listOf("Action")),
            ),
            tastes = listOf(taste(1, MangaRating.DISLIKE), taste(2, MangaRating.NOT_INTERESTED), taste(3, MangaRating.LIKE)),
        )

        val weights = f.interactor.await(TasteProfile.EMPTY, emptyMap())

        assertEquals(setOf(key("Action")), weights.keys, "only the Liked manga counts")
    }

    @Test
    fun `a confirmed group counts once using its strongest member and unlinked titles add up`() = runTest {
        val strong = manga(1, source = 1L, genres = listOf("Action"))
        val weak = manga(2, source = 2L, genres = listOf("Action"))
        val strongRow = activity(1, read = 20, total = 20, days = 3)
        val weakRow = activity(2, read = 5, total = 20, days = 1)
        val linkStrong = CrossSourceMangaLink(1L, "/m1", "G", "t", 0L, 0L)
        val linkWeak = CrossSourceMangaLink(2L, "/m2", "G", "t", 0L, 0L)

        val grouped = fixture(
            listOf(strongRow, weakRow),
            listOf(strong, weak),
            links = mapOf(strong to linkStrong, weak to linkWeak),
            groupMembers = mapOf("G" to listOf(linkStrong, linkWeak)),
        ).interactor.await(TasteProfile.EMPTY, emptyMap())
        val unlinked = fixture(listOf(strongRow, weakRow), listOf(strong, weak))
            .interactor.await(TasteProfile.EMPTY, emptyMap())

        val strongWeight = RecentReadingPolicy.perGenreWeight(RecentReadingPolicy.strength(strongRow, 7, 3, now))
        val weakWeight = RecentReadingPolicy.perGenreWeight(RecentReadingPolicy.strength(weakRow, 7, 3, now))
        assertEquals(RecentReadingPolicy.finalGroupWeight(strongWeight), grouped.getValue(key("Action")), 0.0)
        assertEquals(RecentReadingPolicy.finalGroupWeight(strongWeight + weakWeight), unlinked.getValue(key("Action")), 0.0)
    }

    @Test
    fun `a manga whose row is missing or has no tags is skipped without failing the others`() = runTest {
        val f = fixture(
            listOf(activity(1), activity(2), activity(3)),
            listOf(manga(1, genres = listOf("Action")), manga(3)),
        )
        // manga 2 has no mocked row: getMangaById throws for it.
        val repoField = f.interactor

        val weights = repoField.await(TasteProfile.EMPTY, emptyMap())

        assertEquals(setOf(key("Action")), weights.keys)
    }

    @Test
    fun `a failure fails open to no recent weights but cancellation propagates`() = runTest {
        val failing = fixture(emptyList(), emptyList(), repositoryFailure = IllegalStateException("db"))
        assertEquals(emptyMap<String, Double>(), failing.interactor.await(TasteProfile.EMPTY, emptyMap()))

        val cancelling = fixture(emptyList(), emptyList(), repositoryFailure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { cancelling.interactor.await(TasteProfile.EMPTY, emptyMap()) }
        }
    }

    @Test
    fun `no recent reading gives an empty result`() = runTest {
        val f = fixture(emptyList(), emptyList())
        assertFalse(f.interactor.await(TasteProfile.EMPTY, emptyMap()).isNotEmpty())
    }
}
// KMK <--
