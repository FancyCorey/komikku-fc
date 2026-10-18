package tachiyomi.data.taste

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.InsertMangaTasteIfAbsent
import tachiyomi.domain.taste.interactor.SetMangaTasteBatch
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste

class MangaTasteInsertIfAbsentRepositoryTest {
    private fun repositories(): Pair<MangaRepositoryImpl, TasteRepositoryImpl> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
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
        return MangaRepositoryImpl(handler) to TasteRepositoryImpl(handler)
    }

    @Test
    fun `automatic insert does not replace an existing manual rating`() = runTest {
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
        val mangaId = handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = 2L,
                url = "/tracked",
                artist = null,
                author = null,
                description = null,
                genre = emptyList(),
                title = "Tracked",
                status = 0,
                thumbnailUrl = null,
                favorite = false,
                lastUpdate = 0L,
                nextUpdate = 0L,
                initialized = false,
                viewerFlags = 0L,
                chapterFlags = 0L,
                coverLastModified = 0L,
                dateAdded = 0L,
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0L,
                version = 0L,
                notes = "",
                memo = kotlinx.serialization.json.buildJsonObject {},
            )
            mangasQueries.selectLastInsertedRowId()
        }
        val repository = TasteRepositoryImpl(handler)
        val manualRating = MangaTaste(mangaId, 2L, "/tracked", "Tracked", 3, 1L, 2L)
        val automaticRating = manualRating.copy(rating = 1, updatedAt = 3L)

        repository.upsertMangaTaste(manualRating)

        assertFalse(repository.insertMangaTasteIfAbsent(automaticRating))
        assertEquals(manualRating, repository.getMangaTaste(mangaId))
    }

    @Test
    fun `taste repository rejects a sentinel manga id before either write path`() = runTest {
        val (_, repository) = repositories()
        val invalid = MangaTaste(-1L, 41L, "/missing-row", "Missing", MangaRating.LOVE.value, 1L, 1L)
        var upsertRejected = false
        var insertRejected = false

        try {
            repository.upsertMangaTaste(invalid)
        } catch (_: IllegalArgumentException) {
            upsertRejected = true
        }
        try {
            repository.insertMangaTasteIfAbsent(invalid)
        } catch (_: IllegalArgumentException) {
            insertRejected = true
        }

        assertTrue(upsertRejected)
        assertTrue(insertRejected)
        assertEquals(null, repository.getMangaTaste(41L, "/missing-row"))
    }

    @Test
    fun `automatic ratings for separate missing library manga create valid distinct database identities`() = runTest {
        val (mangaRepository, tasteRepository) = repositories()
        val insertRating = InsertMangaTasteIfAbsent(tasteRepository, mangaRepository)
        val firstTarget = Manga.create().copy(source = 21L, url = "/tracked/first", ogTitle = "First")
        val secondTarget = Manga.create().copy(source = 22L, url = "/tracked/second", ogTitle = "Second")
        assertNull(mangaRepository.getMangaByUrlAndSourceId(firstTarget.url, firstTarget.source))
        assertNull(mangaRepository.getMangaByUrlAndSourceId(secondTarget.url, secondTarget.source))

        assertTrue(insertRating.await(firstTarget, MangaRating.LIKE))
        assertTrue(insertRating.await(secondTarget, MangaRating.LOVE))

        val firstManga = requireNotNull(mangaRepository.getMangaByUrlAndSourceId(firstTarget.url, firstTarget.source))
        val secondManga = requireNotNull(mangaRepository.getMangaByUrlAndSourceId(secondTarget.url, secondTarget.source))
        val firstTaste = requireNotNull(tasteRepository.getMangaTaste(firstTarget.source, firstTarget.url))
        val secondTaste = requireNotNull(tasteRepository.getMangaTaste(secondTarget.source, secondTarget.url))
        assertTrue(firstManga.id > 0L)
        assertTrue(secondManga.id > 0L)
        assertNotEquals(firstManga.id, secondManga.id)
        assertFalse(firstManga.favorite)
        assertFalse(secondManga.favorite)
        assertEquals(firstManga.id, firstTaste.mangaId)
        assertEquals(secondManga.id, secondTaste.mangaId)
        assertEquals(MangaRating.LIKE.value, firstTaste.rating)
        assertEquals(MangaRating.LOVE.value, secondTaste.rating)
        assertEquals(firstTaste, tasteRepository.getMangaTaste(firstManga.id))
        assertEquals(secondTaste, tasteRepository.getMangaTaste(secondManga.id))
        assertEquals(2, tasteRepository.getAllMangaTastes().size)
    }

    @Test
    fun `explicit group batch creates valid nonfavorite rows before storing missing manga ratings`() = runTest {
        val (mangaRepository, tasteRepository) = repositories()
        val setRatings = SetMangaTasteBatch(tasteRepository, mangaRepository)
        val firstTarget = Manga.create().copy(source = 31L, url = "/group/first", ogTitle = "Group First")
        val secondTarget = Manga.create().copy(source = 32L, url = "/group/second", ogTitle = "Group Second")

        setRatings.await(listOf(firstTarget, secondTarget), MangaRating.DISLIKE)

        val firstManga = requireNotNull(mangaRepository.getMangaByUrlAndSourceId(firstTarget.url, firstTarget.source))
        val secondManga = requireNotNull(mangaRepository.getMangaByUrlAndSourceId(secondTarget.url, secondTarget.source))
        assertTrue(firstManga.id > 0L)
        assertTrue(secondManga.id > 0L)
        assertNotEquals(firstManga.id, secondManga.id)
        assertFalse(firstManga.favorite)
        assertFalse(secondManga.favorite)
        assertEquals(MangaRating.DISLIKE.value, tasteRepository.getMangaTaste(firstTarget.source, firstTarget.url)?.rating)
        assertEquals(MangaRating.DISLIKE.value, tasteRepository.getMangaTaste(secondTarget.source, secondTarget.url)?.rating)
    }
}
