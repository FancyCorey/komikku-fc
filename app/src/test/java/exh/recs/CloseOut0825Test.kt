package exh.recs

import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.loved.LovedMangaEntry
import exh.recs.loved.ratedCoverCandidates
import exh.util.FakePreferenceStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import java.io.File

// KMK v0.8.25 -->
/** The last fixes before 0.8.25 closes: the chapter minimum everywhere, covers that survive a dead source, and the focus row. */
class CloseOut0825Test {

    private fun manga(id: Long, source: Long = id) = Manga.create().copy(id = id, source = source, url = "/m/$id")

    // ---- minimum chapter count

    @Test
    fun `away from data saving every shown pick is checked, not only the first twenty`() {
        val rows = (1L..10L).map { row -> (1L..10L).map { row * 100 + it } }
        assertEquals(100, ForYouChapterCountCheck.pick(rows, emptySet(), ForYouChapterCountCheck.MAX_CHECKS_ALL_SHOWN).size)
        assertEquals(ForYouChapterCountCheck.MAX_CHECKS_PER_REFRESH, ForYouChapterCountCheck.pick(rows, emptySet()).size)
    }

    @Test
    fun `library counts win, checked counts fill the gaps, unknown stays unknown`() {
        val preferences = SourcePreferences(FakePreferenceStore())
        val checker = RecommendationChapterCountChecker(mockk(relaxed = true), preferences, now = { 1_000L })
        preferences.recommendationCheckedChapterCounts().set(
            ForYouChapterCountCheck.serialize(mapOf(2L to ForYouChapterCountCheck.Checked(4, 900), 1L to ForYouChapterCountCheck.Checked(99, 900)), 1_000L),
        )

        assertEquals(mapOf(1L to 30L, 2L to 4L), checker.knownCounts(listOf(1L, 2L, 3L), mapOf(1L to 30L)))
    }

    @Test
    fun `a check remembers what it loaded and treats a failure or an empty list as unknown`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        val checker = RecommendationChapterCountChecker(
            mockk(relaxed = true),
            preferences,
            now = { 5_000L },
            loadCount = { manga ->
                when (manga.id) {
                    1L -> 3L
                    2L -> 0L
                    else -> error("source down")
                }
            },
        )

        val counts = checker.check(listOf(manga(1), manga(2), manga(3)))

        assertEquals(mapOf(1L to 3L), counts)
        assertEquals(mapOf(1L to 3L), checker.knownCounts(listOf(1L, 2L, 3L), emptyMap()), "remembered for the next screen")
    }

    @Test
    fun `For You, a manga's and a group's Recommendations all use the same checked counts and check their rows`() {
        val forYou = File("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt").readText()
        val recommends = File("src/main/java/exh/recs/RecommendsScreenModel.kt").readText()
        assertTrue(forYou.contains("if (dataSaving) ForYouChapterCountCheck.MAX_CHECKS_PER_REFRESH else ForYouChapterCountCheck.MAX_CHECKS_ALL_SHOWN"))
        assertTrue(!forYou.contains("if (minChapterCount > 0 && !dataSaving)"), "saving data no longer turns the check off")
        assertTrue(recommends.contains("chapterCountChecker.knownCounts("))
        assertTrue(recommends.contains("removeTooShort(minChapterCount)"))
    }

    // ---- For You focus panel

    @Test
    fun `the focus panel offers the chapter minimum and saves it with Apply and with Clear`() {
        val tab = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText().replace("\r\n", "\n")
        assertTrue(tab.contains("ForYouMinChapterRow(selected = pendingMinChapters"))
        assertEquals(2, Regex("screenModel.setMinChapterCount\\(pendingMinChapters\\)\n\\s+screenModel.setActiveFocus").findAll(tab).count())
        assertTrue(FOR_YOU_MIN_CHAPTER_CHOICES.all { RecommendationMinChapterCountPolicy.resolve(it) == it }, "every choice is a supported value")
    }

    // ---- covers

    private fun entry(id: Long, withManga: Boolean = true) = LovedMangaEntry(
        MangaTaste(mangaId = id, source = id, url = "/m/$id", title = "T$id", rating = MangaRating.LOVE.value, createdAt = 0L, updatedAt = 0L),
        if (withManga) manga(id) else null,
    )

    @Test
    fun `a rated item's cover falls back through its group's other versions in order, each once`() {
        val shown = entry(2)
        val candidates = ratedCoverCandidates(shown, listOf(entry(1), shown, entry(3, withManga = false), entry(4)))
        assertEquals(listOf(2L, 1L, 4L), candidates.map { it.id })
    }

    @Test
    fun `the rated grid shows the first cover that loads`() {
        val screen = File("src/main/java/exh/recs/loved/RatedMangaScreen.kt").readText()
        assertTrue(screen.contains("cover = rememberRatedCover(item),"))
        assertTrue(screen.contains("if (result is coil3.request.SuccessResult) {"))
    }
}
// KMK <--
