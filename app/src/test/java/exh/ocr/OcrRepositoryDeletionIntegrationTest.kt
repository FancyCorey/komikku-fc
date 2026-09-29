package exh.ocr

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.presentation.more.settings.screen.advanced.clearNonLibraryMangaAndOcrRows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl

class OcrRepositoryDeletionIntegrationTest {

    @Test
    fun `chapter and manga repository deletion remove their OCR rows`() = runTest {
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
        val chapters = ChapterRepositoryImpl(handler)
        val mangas = MangaRepositoryImpl(handler)

        insertManga(driver, mangaId = 1)
        insertManga(driver, mangaId = 2)
        insertChapter(driver, chapterId = 10, mangaId = 1)
        insertChapter(driver, chapterId = 20, mangaId = 2)
        insertOcrRow(driver, mangaId = 1, chapterId = 10)
        insertOcrRow(driver, mangaId = 2, chapterId = 20)

        chapters.removeChaptersWithIds(listOf(10))
        assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE chapter_id = 10"))

        mangas.deleteManga(2)
        assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE manga_id = 2"))
        assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page"))
    }

    @Test
    fun `clear database OCR cleanup follows the keep-read selection`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        insertManga(driver, mangaId = 1)
        insertManga(driver, mangaId = 2)
        insertChapter(driver, chapterId = 20, mangaId = 2)
        driver.execute(null, "UPDATE chapters SET read = 1 WHERE _id = 20", 0)
        insertOcrRow(driver, mangaId = 1, chapterId = 10)
        insertOcrRow(driver, mangaId = 2, chapterId = 20)

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

        clearNonLibraryMangaAndOcrRows(
            database = AndroidDatabaseHandler(database, driver),
            ocrIndexRepository = OcrIndexRepository(AndroidDatabaseHandler(database, driver)),
            sourceIds = listOf(1),
            keepReadManga = true,
        )

        assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE manga_id = 1"))
        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE manga_id = 2"))
    }

    @Test
    fun `late OCR write is ignored after its chapter is deleted`() = runTest {
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
        insertManga(driver, mangaId = 1)
        insertChapter(driver, chapterId = 10, mangaId = 1)

        val repository = OcrIndexRepository(handler)
        val runGeneration = repository.beginIndexing()
        repository.deleteAll()
        ChapterRepositoryImpl(handler).removeChaptersWithIds(listOf(10))
        val writeAccepted = repository.upsertPage(
            mangaId = 1,
            sourceId = 1,
            mangaUrl = "/manga/1",
            mangaTitle = "Manga 1",
            chapterId = 10,
            chapterUrl = "/chapter/10",
            chapterName = "Chapter",
            pageIndex = 0,
            pageUri = "page",
            pageIdentity = "page",
            pageModifiedAt = null,
            pageSize = null,
            engineKey = "mlkit",
            engineVersion = "1",
            languageHint = "la",
            rawText = "late text",
            normalizedText = "late text",
            indexedAt = 0,
            errorMessage = null,
            recognizedTextLength = 9,
            recognizedWordCount = 2,
            ocrStatus = OCR_STATUS_SUCCESS,
            runGeneration = runGeneration,
        )

        assertEquals(false, writeAccepted)
        assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page"))

        insertChapter(driver, chapterId = 10, mangaId = 1)
        val currentGeneration = repository.beginIndexing()
        val currentWriteAccepted = repository.upsertPage(
            mangaId = 1,
            sourceId = 1,
            mangaUrl = "/manga/1",
            mangaTitle = "Manga 1",
            chapterId = 10,
            chapterUrl = "/chapter/10",
            chapterName = "Chapter",
            pageIndex = 0,
            pageUri = "page",
            pageIdentity = "page",
            pageModifiedAt = null,
            pageSize = null,
            engineKey = "mlkit",
            engineVersion = "1",
            languageHint = "la",
            rawText = "current text",
            normalizedText = "current text",
            indexedAt = 1,
            errorMessage = null,
            recognizedTextLength = 12,
            recognizedWordCount = 2,
            ocrStatus = OCR_STATUS_SUCCESS,
            runGeneration = currentGeneration,
        )

        assertTrue(currentWriteAccepted)
        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page"))
    }

    @Test
    fun `chapter deletion rolls back OCR cleanup when the chapter delete fails`() = runTest {
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
        insertManga(driver, mangaId = 1)
        insertChapter(driver, chapterId = 10, mangaId = 1)
        insertOcrRow(driver, mangaId = 1, chapterId = 10)
        driver.execute(
            null,
            """
                CREATE TRIGGER fail_chapter_delete
                BEFORE DELETE ON chapters
                BEGIN
                    SELECT RAISE(ABORT, 'forced chapter delete failure');
                END
            """.trimIndent(),
            0,
        )

        var failure: Exception? = null
        try {
            ChapterRepositoryImpl(AndroidDatabaseHandler(database, driver)).removeChaptersWithIds(listOf(10))
        } catch (exception: Exception) {
            failure = exception
        }
        assertTrue(failure != null)

        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM chapters WHERE _id = 10"))
        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE chapter_id = 10"))
    }

    @Test
    fun `reindexing the same page updates freshness metadata`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
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
            insertManga(driver, 1)
            insertChapter(driver, 10, 1)
            val repository = OcrIndexRepository(AndroidDatabaseHandler(database, driver))
            val generation = repository.beginIndexing()
            suspend fun write(modifiedAt: Long, size: Long) = repository.upsertPage(
                mangaId = 1,
                sourceId = 1,
                mangaUrl = "/manga/1",
                mangaTitle = "Manga 1",
                chapterId = 10,
                chapterUrl = "/chapter/10",
                chapterName = "Chapter",
                pageIndex = 0,
                pageUri = "page",
                pageIdentity = "page",
                pageModifiedAt = modifiedAt,
                pageSize = size,
                engineKey = "mlkit",
                engineVersion = "1",
                languageHint = "la",
                rawText = "text",
                normalizedText = "text",
                indexedAt = modifiedAt,
                errorMessage = null,
                recognizedTextLength = 4,
                recognizedWordCount = 1,
                ocrStatus = OCR_STATUS_SUCCESS,
                runGeneration = generation,
            )
            assertTrue(write(100, 1000))
            assertTrue(write(101, 1001))
            val state = repository.getExistingPageState(10, 0, "page", "mlkit", "1")!!
            assertEquals(101L, state.pageModifiedAt)
            assertEquals(1001L, state.pageSize)
            assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page"))
            assertTrue(shouldSkipOcrPage(state, "page", 101, 1001, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED))
        }
    }

    private fun insertManga(driver: JdbcSqliteDriver, mangaId: Long) {
        driver.execute(
            null,
            """
            INSERT INTO mangas(
                _id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added
            ) VALUES ($mangaId, 1, '/manga/$mangaId', 'Manga $mangaId', 0, 0, 1, 0, 0, 0, 0)
            """.trimIndent(),
            0,
        )
    }

    private fun insertChapter(driver: JdbcSqliteDriver, chapterId: Long, mangaId: Long) {
        driver.execute(
            null,
            """
            INSERT INTO chapters(
                _id, manga_id, url, name, read, bookmark, last_page_read,
                chapter_number, source_order, date_fetch, date_upload
            ) VALUES ($chapterId, $mangaId, '/chapter/$chapterId', 'Chapter', 0, 0, 0, 1, 0, 0, 0)
            """.trimIndent(),
            0,
        )
    }

    private fun insertOcrRow(driver: JdbcSqliteDriver, mangaId: Long, chapterId: Long) {
        driver.execute(
            null,
            """
            INSERT INTO ocr_indexed_page(
                manga_id, source_id, manga_url, manga_title, chapter_id, chapter_url,
                chapter_name, page_index, page_identity, engine_key, engine_version,
                raw_text, normalized_text, indexed_at
            ) VALUES ($mangaId, 1, '/manga/$mangaId', 'Manga', $chapterId,
                '/chapter/$chapterId', 'Chapter', 0, 'page', 'mlkit', '1',
                'text', 'text', 0)
            """.trimIndent(),
            0,
        )
    }
}

private fun JdbcSqliteDriver.longScalar(sql: String): Long {
    var value = -1L
    executeQuery(null, sql, { cursor ->
        check(cursor.next().value)
        value = cursor.getLong(0) ?: -1L
        app.cash.sqldelight.db.QueryResult.Value(Unit)
    }, 0)
    return value
}
