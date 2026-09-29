package exh.ocr

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class OcrOrphanCleanupTest {

    @Test
    fun `cleanup keeps matching rows and removes orphaned or mismatched identities`() {
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

        driver.execute(
            null,
            """
            INSERT INTO mangas(
                _id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added
            ) VALUES (1, 1, '/manga/1', 'Manga 1', 0, 0, 1, 0, 0, 0, 0)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO mangas(
                _id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added
            ) VALUES (2, 1, '/manga/2', 'Manga 2', 0, 0, 1, 0, 0, 0, 0)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO chapters(
                _id, manga_id, url, name, read, bookmark, last_page_read,
                chapter_number, source_order, date_fetch, date_upload
            ) VALUES (10, 1, '/chapter/10', 'Chapter 10', 0, 0, 0, 10, 0, 0, 0)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO chapters(
                _id, manga_id, url, name, read, bookmark, last_page_read,
                chapter_number, source_order, date_fetch, date_upload
            ) VALUES (11, 2, '/chapter/11', 'Chapter 11', 0, 0, 0, 11, 0, 0, 0)
            """.trimIndent(),
            0,
        )
        insertOcrRow(driver, mangaId = 1, chapterId = 10, pageIdentity = "valid")
        insertOcrRow(driver, mangaId = 1, chapterId = 99, pageIdentity = "orphan")
        insertOcrRow(driver, mangaId = 1, chapterId = 11, pageIdentity = "mismatch")

        database.ocr_indexed_pageQueries.deleteOrphanedRows()

        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page"))
        assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM ocr_indexed_page WHERE page_identity = 'valid'"))
    }

    private fun insertOcrRow(
        driver: JdbcSqliteDriver,
        mangaId: Long,
        chapterId: Long,
        pageIdentity: String,
    ) {
        driver.execute(
            null,
            """
            INSERT INTO ocr_indexed_page(
                manga_id, source_id, manga_url, manga_title, chapter_id, chapter_url,
                chapter_name, page_index, page_identity, engine_key, engine_version,
                raw_text, normalized_text, indexed_at
            ) VALUES ($mangaId, 1, '/manga/$mangaId', 'Manga', $chapterId,
                '/chapter/$chapterId', 'Chapter', 0, '$pageIdentity', 'mlkit', '1',
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
