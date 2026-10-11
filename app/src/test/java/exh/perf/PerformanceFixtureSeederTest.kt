package exh.perf

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import exh.recs.TestInjektSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.taste.TasteRepositoryImpl
import tachiyomi.data.tracker.LocalTrackerRepositoryImpl

/**
 * KMK C4 (HR-2026-08-26-CONSTRAINED-DEVICE-PERFORMANCE-PROGRAM): proves
 * [PerformanceFixtureSeeder] writes a [PerformanceFixtureGenerator]-generated dataset into a REAL
 * database (the actual production SQLDelight schema, real repositories -- not a fake/in-memory
 * stand-in) end to end. Uses the exact same `JdbcSqliteDriver(IN_MEMORY)` + `Database.Schema
 * .create` + `AndroidDatabaseHandler` construction pattern already established by
 * AlternateSourceBridgeRepositoryTest/LocalTrackerRepositoryTest -- this is what makes the fixture
 * genuinely host-verifiable before ever touching a real device.
 */
class PerformanceFixtureSeederTest {

    companion object {
        // KMK: `getFavorites()`/`getAll()` map favorite=true rows into real `Manga` domain objects,
        // which eagerly resolves GetCustomMangaInfo via Injekt (see TestInjektSupport's own doc) --
        // needed here even though PerformanceFixtureSeeder itself never constructs a favorite Manga
        // directly, because reading favorites BACK for assertions still does.
        @JvmStatic
        @BeforeAll
        fun setUpInjekt() {
            TestInjektSupport.ensureCustomMangaInfoBound()
        }
    }

    private class Repositories(
        val manga: MangaRepositoryImpl,
        val chapter: ChapterRepositoryImpl,
        val category: CategoryRepositoryImpl,
        val history: HistoryRepositoryImpl,
        val taste: TasteRepositoryImpl,
        val localTracker: LocalTrackerRepositoryImpl,
    )

    private fun freshRepositories(): Repositories {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        return Repositories(
            manga = MangaRepositoryImpl(handler),
            chapter = ChapterRepositoryImpl(handler),
            category = CategoryRepositoryImpl(handler),
            history = HistoryRepositoryImpl(handler),
            taste = TasteRepositoryImpl(handler),
            localTracker = LocalTrackerRepositoryImpl(handler),
        )
    }

    private suspend fun seedSmall(repos: Repositories): Pair<PerformanceFixtureDataset, PerformanceFixtureSeeder.SeedResult> {
        val dataset = PerformanceFixtureGenerator.generate(PerformanceFixtureSpec.SMALL)
        val result = PerformanceFixtureSeeder.seed(
            dataset = dataset,
            mangaRepository = repos.manga,
            chapterRepository = repos.chapter,
            categoryRepository = repos.category,
            historyRepository = repos.history,
            tasteRepository = repos.taste,
            localTrackerRepository = repos.localTracker,
        )
        return dataset to result
    }

    @Test
    fun `seeds exactly totalManga real manga rows with real generated ids`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        assertEquals(dataset.manga.size, result.mangaIds.size)
        assertEquals(result.mangaIds.toSet().size, result.mangaIds.size, "every generated manga must get a distinct real id")
        assertEquals(dataset.manga.size, repos.manga.getAll().size)
    }

    @Test
    fun `favorite flag lands correctly without ever constructing a Manga with favorite=true directly`() = runTest {
        val repos = freshRepositories()
        val (dataset, _) = seedSmall(repos)

        val expectedFavorites = dataset.manga.count { it.favorite }
        val actualFavorites = repos.manga.getFavorites().size
        assertEquals(expectedFavorites, actualFavorites)
    }

    @Test
    fun `categories are created and assigned to the right favorite manga`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        assertEquals(dataset.categoryNames.size, result.categoryIds.size)
        val mangaWithCategories = dataset.manga.withIndex().filter { it.value.categoryIndices.isNotEmpty() }
        assertTrue(mangaWithCategories.isNotEmpty())
        val (index, generated) = mangaWithCategories.first()
        val mangaId = result.mangaIds[index]
        val categories = repos.category.getCategoriesByMangaId(mangaId)
        assertEquals(generated.categoryIndices.size, categories.size)
    }

    @Test
    fun `chapter counts and shapes (zero, decimals, specials, long-running) land exactly as generated`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        val expectedTotalChapters = dataset.manga.sumOf { it.chapters.size }
        assertEquals(expectedTotalChapters, result.chapterCount)

        dataset.manga.forEachIndexed { i, generated ->
            val actual = repos.chapter.getChapterByMangaId(result.mangaIds[i], applyFilter = false)
            assertEquals(generated.chapters.size, actual.size, "chapter count mismatch for manga index $i")
            assertEquals(
                generated.chapters.map { it.chapterNumber }.sorted(),
                actual.map { it.chapterNumber }.sorted(),
                "chapter numbers must round-trip exactly, including decimals/specials/duplicates, for manga index $i",
            )
        }
    }

    @Test
    fun `history rows are created only for the designated chapters`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        val expectedHistoryCount = dataset.manga.sumOf { it.historyChapterIndices.size }
        assertEquals(expectedHistoryCount, result.historyCount)
        assertTrue(expectedHistoryCount > 0, "the SMALL preset must actually exercise history insertion")
    }

    @Test
    fun `every rated manga's taste row round trips with the correct rating value`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        val allTastes = repos.taste.getAllMangaTastes()
        assertEquals(dataset.manga.count { it.rating != null }, allTastes.size)
        assertEquals(result.ratedCount, allTastes.size)

        dataset.manga.withIndex().filter { it.value.rating != null }.forEach { (i, generated) ->
            val taste = repos.taste.getMangaTaste(result.mangaIds[i])
            assertTrue(taste != null, "expected a taste row for manga index $i")
            assertEquals(generated.rating!!.value, taste!!.rating)
        }
    }

    @Test
    fun `cross-source links and exactly one primary per group round trip`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        val links = repos.taste.getAllCrossSourceMangaLinks()
        assertEquals(result.crossSourceLinkCount, links.size)

        val groupIds = dataset.manga.mapNotNull { it.crossSourceGroupId }.distinct()
        assertTrue(groupIds.isNotEmpty())
        groupIds.forEach { groupId ->
            val groupLinks = repos.taste.getCrossSourceMangaLinksByGroupId(groupId)
            assertEquals(2, groupLinks.size, "group $groupId must round-trip with exactly 2 links")
            val primary = repos.taste.getCrossSourceGroupPrimary(groupId)
            assertTrue(primary != null, "group $groupId must have a primary")
        }
    }

    @Test
    fun `local tracking work rows are created only for the designated favorite manga`() = runTest {
        val repos = freshRepositories()
        val (dataset, result) = seedSmall(repos)

        val expected = dataset.manga.count { it.hasLocalTracking }
        assertEquals(expected, result.localTrackingCount)
        assertTrue(expected > 0, "the SMALL preset must actually exercise local tracking insertion")

        dataset.manga.withIndex().filter { it.value.hasLocalTracking }.forEach { (i, _) ->
            val mangaId = result.mangaIds[i]
            val work = repos.localTracker.getWork("perf-fixture-work-$mangaId")
            assertTrue(work != null, "expected a local tracked work row for manga index $i")
        }
    }

    @Test
    fun `tag tastes and aliases from the dataset round trip`() = runTest {
        val repos = freshRepositories()
        val (dataset, _) = seedSmall(repos)

        assertEquals(dataset.tagTastes.size, repos.taste.getAllTagTastes().size)
        assertEquals(dataset.tagAliases.size, repos.taste.getAllTagAliases().size)
    }

    @Test
    fun `the full REALISTIC (approximately 1,000-manga) preset seeds end to end without error`() = runTest {
        val repos = freshRepositories()
        val dataset = PerformanceFixtureGenerator.generate(PerformanceFixtureSpec.REALISTIC)

        val elapsedMs = System.currentTimeMillis()
        val result = PerformanceFixtureSeeder.seed(
            dataset = dataset,
            mangaRepository = repos.manga,
            chapterRepository = repos.chapter,
            categoryRepository = repos.category,
            historyRepository = repos.history,
            tasteRepository = repos.taste,
            localTrackerRepository = repos.localTracker,
        )
        val took = System.currentTimeMillis() - elapsedMs

        assertEquals(PerformanceFixtureSpec.REALISTIC.totalManga, result.mangaIds.size)
        assertEquals(dataset.manga.sumOf { it.chapters.size }, result.chapterCount)
        assertEquals(PerformanceFixtureSpec.REALISTIC.totalManga, repos.manga.getAll().size)
        // KMK: not a strict performance budget (this is an in-memory host DB, not the real device
        // target C4's own measurement matrix will profile) -- just a sanity ceiling proving the
        // reusable script itself does not have a pathological (e.g. O(n^2)) blowup at target scale.
        assertTrue(took < 60_000, "seeding the REALISTIC preset took ${took}ms, unexpectedly slow even for an in-memory host DB")
    }

    @Test
    fun `repeated seeding in the same database rejects before changing persisted rows`() = runTest {
        val repos = freshRepositories()
        val (_, first) = seedSmall(repos)
        val mangaBefore = repos.manga.getAll()
        val categoriesBefore = repos.category.getAll()
        val chaptersBefore = first.mangaIds.associateWith { repos.chapter.getChapterByMangaId(it, applyFilter = false) }
        val historyBefore = first.mangaIds.associateWith { repos.history.getHistoryByMangaId(it) }
        val tastesBefore = repos.taste.getAllMangaTastes()
        val linksBefore = repos.taste.getAllCrossSourceMangaLinks()
        val primariesBefore = repos.taste.getAllCrossSourceGroupPrimaries()
        val tagsBefore = repos.taste.getAllTagTastes()
        val aliasesBefore = repos.taste.getAllTagAliases()
        val trackingBefore = repos.localTracker.getAllWorksAsFlow().first()
        val assignmentsBefore = first.mangaIds.associateWith { repos.category.getCategoriesByMangaId(it) }
        val durationBefore = repos.history.getTotalReadDuration()

        var rejection: IllegalStateException? = null
        try {
            seedSmall(repos)
        } catch (error: IllegalStateException) {
            rejection = error
        }
        assertEquals(
            "Performance fixture identities already exist; refusing to append duplicate fixture data",
            rejection?.message,
        )
        assertEquals(mangaBefore, repos.manga.getAll())
        assertEquals(categoriesBefore, repos.category.getAll())
        assertEquals(chaptersBefore, first.mangaIds.associateWith { repos.chapter.getChapterByMangaId(it, applyFilter = false) })
        assertEquals(historyBefore, first.mangaIds.associateWith { repos.history.getHistoryByMangaId(it) })
        assertEquals(tastesBefore, repos.taste.getAllMangaTastes())
        assertEquals(linksBefore, repos.taste.getAllCrossSourceMangaLinks())
        assertEquals(primariesBefore, repos.taste.getAllCrossSourceGroupPrimaries())
        assertEquals(tagsBefore, repos.taste.getAllTagTastes())
        assertEquals(aliasesBefore, repos.taste.getAllTagAliases())
        assertEquals(trackingBefore, repos.localTracker.getAllWorksAsFlow().first())
        assertEquals(assignmentsBefore, first.mangaIds.associateWith { repos.category.getCategoriesByMangaId(it) })
        assertEquals(durationBefore, repos.history.getTotalReadDuration())
    }

    @Test
    fun `partial identity overlap rejects the whole seed before categories or new manga are written`() = runTest {
        val repos = freshRepositories()
        val dataset = PerformanceFixtureGenerator.generate(PerformanceFixtureSpec.SMALL)
        val collision = dataset.manga.last()
        val preexisting = repos.manga.insertNetworkManga(
            listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = collision.source, url = collision.url, ogTitle = "Existing row")),
            updateInfo = false,
        )
        val categoriesBefore = repos.category.getAll()
        var rejection: IllegalStateException? = null
        try {
            seedSmall(repos)
        } catch (error: IllegalStateException) {
            rejection = error
        }
        assertEquals(
            "Performance fixture identities already exist; refusing to append duplicate fixture data",
            rejection?.message,
        )
        assertEquals(preexisting, repos.manga.getAll())
        assertEquals(categoriesBefore, repos.category.getAll())
        assertTrue(repos.chapter.getChapterByMangaId(preexisting.single().id, applyFilter = false).isEmpty())
        assertTrue(repos.history.getHistoryByMangaId(preexisting.single().id).isEmpty())
        assertTrue(repos.taste.getAllMangaTastes().isEmpty())
        assertTrue(repos.taste.getAllCrossSourceMangaLinks().isEmpty())
        assertTrue(repos.taste.getAllCrossSourceGroupPrimaries().isEmpty())
        assertTrue(repos.taste.getAllTagTastes().isEmpty())
        assertTrue(repos.taste.getAllTagAliases().isEmpty())
        assertTrue(repos.localTracker.getAllWorksAsFlow().first().isEmpty())
        assertTrue(repos.category.getCategoriesByMangaId(preexisting.single().id).isEmpty())
    }

    @Test
    fun `seeding twice against two independent fresh databases produces identical row counts`() = runTest {
        val repos1 = freshRepositories()
        val repos2 = freshRepositories()
        val (_, result1) = seedSmall(repos1)
        val (_, result2) = seedSmall(repos2)

        assertEquals(result1.mangaIds.size, result2.mangaIds.size)
        assertEquals(result1.chapterCount, result2.chapterCount)
        assertEquals(result1.historyCount, result2.historyCount)
        assertEquals(result1.ratedCount, result2.ratedCount)
        assertEquals(result1.crossSourceLinkCount, result2.crossSourceLinkCount)
        assertEquals(result1.localTrackingCount, result2.localTrackingCount)
    }
}
