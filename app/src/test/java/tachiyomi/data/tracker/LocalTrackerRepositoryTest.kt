package tachiyomi.data.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkList
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus

class LocalTrackerRepositoryTest {

    private fun repository(
        driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
    ): LocalTrackerRepositoryImpl {
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
        return LocalTrackerRepositoryImpl(AndroidDatabaseHandler(database, driver))
    }

    private fun work(id: String = "work-1") = LocalTrackedWork(
        id = id,
        title = "Example",
        normalizedTitle = "example",
        status = LocalTrackedWorkStatus.READING,
        lastChapterSource = null,
        lastChapterNumber = null,
        lastChapterUrl = null,
        lastChapterLabel = null,
        lastProgressAt = null,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun source(workId: String = "work-1", url: String = "/example") = LocalTrackedWorkSource(
        workId = workId,
        source = 1,
        url = url,
        title = "Example",
        confidence = 100,
        confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    @Test
    fun `work and source link survive without a manga row`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        assertEquals(work(), repository.getWork("work-1"))
        assertEquals(listOf(source()), repository.getSources("work-1"))
    }

    @Test
    fun `local metadata survives database round trip`() = runTest {
        val repository = repository()
        val expected = work().copy(
            score = 8.5,
            startDate = 1_100,
            finishDate = 2_100,
        )

        repository.upsertWork(expected)

        assertEquals(expected, repository.getWork(expected.id))
    }

    @Test
    fun `custom lists are trimmed case deduplicated and replace atomically`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.replaceLists("work-1", listOf(" Reading ", "reading", "Paused"))

        assertEquals(listOf("Paused", "Reading"), repository.getLists("work-1"))
    }

    @Test
    fun `custom list creation times survive entry round trip`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        val entries = listOf(
            LocalTrackedWorkList("Reading", 1_100),
            LocalTrackedWorkList("Paused", 1_200),
        )

        repository.replaceListEntries("work-1", entries)

        assertEquals(entries.sortedBy { it.name.lowercase() }, repository.getListEntries("work-1"))
    }

    @Test
    fun `one source entry cannot be attached to two local works`() = runTest {
        val repository = repository()
        repository.upsertWork(work("work-1"))
        repository.upsertWork(work("work-2"))
        repository.upsertSource(source("work-1"))

        var rejected = false
        try {
            repository.upsertSource(source("work-2"))
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(emptyList<LocalTrackedWorkSource>(), repository.getSources("work-2"))
    }

    @Test
    fun `consolidating works preserves canonical identity and dependent rows`() = runTest {
        val repository = repository()
        val canonical = work("work-1").copy(score = 7.0, startDate = 1_100)
        val duplicate = work("work-2").copy(score = 8.5, finishDate = 3_100, updatedAt = 3_000)
        repository.upsertWork(canonical)
        repository.upsertWork(duplicate)
        repository.upsertSource(source("work-1", "/canonical"))
        repository.upsertSource(source("work-2", "/duplicate").copy(inheritanceOptedOut = true))
        repository.upsertSourceProgress(
            LocalTrackedWorkSourceProgress(
                workId = "work-2",
                source = 1,
                url = "/duplicate",
                chapterNumber = 12.0,
                chapterUrl = "/duplicate/ch-12",
                chapterLabel = "Chapter 12",
                progressAt = 2_500,
                updatedAt = 2_500,
            ),
        )
        val duplicateList = LocalTrackedWorkList("Favorites", 1_500)
        repository.replaceListEntries("work-2", listOf(duplicateList))

        repository.consolidateWork("work-1", "work-2")

        val merged = repository.getWork("work-1")!!
        assertEquals(canonical.id, merged.id)
        assertEquals("Example", merged.title)
        assertEquals("example", merged.normalizedTitle)
        assertEquals(8.5, merged.score)
        assertEquals(canonical.startDate, merged.startDate)
        assertEquals(duplicate.finishDate, merged.finishDate)
        assertEquals(minOf(canonical.createdAt, duplicate.createdAt), merged.createdAt)
        assertEquals(maxOf(canonical.updatedAt, duplicate.updatedAt), merged.updatedAt)
        assertNull(repository.getWork("work-2"))
        assertEquals(setOf("/canonical", "/duplicate"), repository.getSources("work-1").map { it.url }.toSet())
        assertEquals("work-1", repository.getWorkIdBySourceUrl(1, "/duplicate"))
        val mergedSource = repository.getSources("work-1").single { it.url == "/duplicate" }
        assertEquals(100, mergedSource.confidence)
        assertEquals(LocalTrackedWorkSourceConfirmation.USER_CONFIRMED, mergedSource.confirmation)
        assertTrue(mergedSource.inheritanceOptedOut)
        assertEquals(duplicateList.createdAt, repository.getListEntries("work-1").single().createdAt)
        val mergedProgress = repository.getSourceProgress("work-1", 1, "/duplicate")!!
        assertEquals(12.0, mergedProgress.chapterNumber)
        assertEquals("/duplicate/ch-12", mergedProgress.chapterUrl)
        assertEquals("Chapter 12", mergedProgress.chapterLabel)
        assertEquals(2_500L, mergedProgress.progressAt)
        assertEquals(2_500L, mergedProgress.updatedAt)
        assertEquals(listOf(duplicateList), repository.getListEntries("work-1"))

        repository.consolidateWork("work-1", "work-2")

        assertEquals(merged, repository.getWork("work-1"))
        assertEquals(setOf("/canonical", "/duplicate"), repository.getSources("work-1").map { it.url }.toSet())
        assertEquals(listOf(duplicateList), repository.getListEntries("work-1"))
    }

    @Test
    fun `separating several sources preserves all saved fields and subsequent updates are independent`() = runTest {
        val repository = repository()
        val original = work().copy(
            status = LocalTrackedWorkStatus.COMPLETED,
            lastChapterSource = 9,
            lastChapterNumber = 42.5,
            lastChapterUrl = "/retained/ch-42",
            lastChapterLabel = "Chapter 42.5",
            lastProgressAt = 4_000,
            score = 8.5,
            startDate = 1_100,
            finishDate = 4_100,
            updatedAt = 5_000,
        )
        repository.upsertWork(original)
        val versions = listOf(
            source(url = "/a").copy(title = "Version A", confidence = 73, inheritanceOptedOut = true),
            source(url = "/b").copy(source = 2, confirmation = LocalTrackedWorkSourceConfirmation.SUGGESTED),
            source(url = "/retained").copy(source = 9),
        )
        val progress = versions.mapIndexed { index, version ->
            LocalTrackedWorkSourceProgress(
                workId = original.id,
                source = version.source,
                url = version.url,
                chapterNumber = if (index == 1) null else 12.5 + index,
                chapterUrl = "${version.url}/saved",
                chapterLabel = if (index == 1) "Special" else "Saved chapter",
                progressAt = 2_000 + index.toLong(),
                inheritedFromSource = 9,
                inheritedFromUrl = "/retained",
                updatedAt = 3_000 + index.toLong(),
            )
        }
        versions.forEach { repository.upsertSource(it) }
        progress.forEach { repository.upsertSourceProgress(it) }
        val lists = listOf(LocalTrackedWorkList("Favorites", 1_234), LocalTrackedWorkList("Finished", 2_345))
        repository.replaceListEntries(original.id, lists)
        val selected = versions.take(2).map { it.source to it.url }.toSet()

        repository.separateSources(selected, now = 6_000)

        val ids = versions.map { repository.getWorkIdBySourceUrl(it.source, it.url)!! }
        assertEquals(3, ids.toSet().size)
        assertEquals(original.id, ids.last())
        assertEquals(3, repository.getAllWorksAsFlow().first().size)
        versions.take(2).forEachIndexed { index, version ->
            val id = ids[index]
            assertEquals(original.copy(id = id, updatedAt = 6_000), repository.getWork(id))
            assertEquals(listOf(version.copy(workId = id)), repository.getSources(id))
            assertEquals(progress[index].copy(workId = id), repository.getSourceProgress(id, version.source, version.url))
            assertNull(repository.getSourceProgress(original.id, version.source, version.url))
            assertEquals(lists, repository.getListEntries(id))
        }
        assertEquals(original, repository.getWork(original.id))
        assertEquals(listOf(versions.last()), repository.getSources(original.id))
        assertEquals(progress.last(), repository.getSourceProgress(original.id, 9, "/retained"))
        assertEquals(lists, repository.getListEntries(original.id))

        val separated = repository.getAllWorksAsFlow().first()
        repository.separateSources(selected, now = 7_000)
        assertEquals(separated, repository.getAllWorksAsFlow().first())
        assertEquals(ids, versions.map { repository.getWorkIdBySourceUrl(it.source, it.url) })

        repository.recordProgress(ids[0], 1, 50.0, "/a/new", "Chapter 50", 8_000)
        repository.recordSourceProgress(
            progress[0].copy(workId = ids[0], chapterNumber = 50.0, progressAt = 8_000, updatedAt = 8_000),
        )
        repository.upsertWork(repository.getWork(ids[0])!!.copy(score = 9.0))
        repository.replaceListEntries(ids[0], emptyList())
        assertEquals(50.0, repository.getWork(ids[0])!!.lastChapterNumber)
        assertEquals(original.copy(id = ids[1], updatedAt = 6_000), repository.getWork(ids[1]))
        assertEquals(progress[1].copy(workId = ids[1]), repository.getSourceProgress(ids[1], 2, "/b"))
        assertEquals(lists, repository.getListEntries(ids[1]))
        assertEquals(original, repository.getWork(original.id))
        assertEquals(lists, repository.getListEntries(original.id))
        repository.recordProgress(original.id, 9, 60.0, "/retained/new", "Chapter 60", 9_000)
        assertEquals(50.0, repository.getWork(ids[0])!!.lastChapterNumber)
        assertEquals(42.5, repository.getWork(ids[1])!!.lastChapterNumber)
    }

    @Test
    fun `separating all sources preserves progress with foreign keys enabled or disabled`() = runTest {
        for (foreignKeys in listOf("ON", "OFF")) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                driver.execute(null, "PRAGMA foreign_keys = $foreignKeys", 0)
                val repository = repository(driver)
                val original = work().copy(lastChapterNumber = 25.0, lastChapterLabel = "Chapter 25", lastProgressAt = 4_000)
                repository.upsertWork(original)
                // Same provider, different URLs must still become separate works.
                val versions = listOf(source(url = "/a"), source(url = "/b"), source(url = "/c"))
                versions.forEach { repository.upsertSource(it) }
                val saved = LocalTrackedWorkSourceProgress(
                    workId = original.id,
                    source = 1,
                    url = "/a",
                    chapterNumber = 12.0,
                    chapterUrl = "/a/ch-12",
                    chapterLabel = "Chapter 12",
                    progressAt = 2_000,
                    updatedAt = 2_000,
                )
                repository.upsertSourceProgress(saved)
                val lists = listOf(LocalTrackedWorkList("Favorites", 1_234))
                repository.replaceListEntries(original.id, lists)
                val selected = versions.map { it.source to it.url }.toSet()

                repository.separateSources(selected, now = 500)

                assertNull(repository.getWork(original.id))
                assertTrue(repository.getSources(original.id).isEmpty())
                assertTrue(repository.getListEntries(original.id).isEmpty())
                assertNull(repository.getSourceProgress(original.id, 1, "/a"))
                val ids = versions.map { repository.getWorkIdBySourceUrl(it.source, it.url)!! }
                assertEquals(3, ids.toSet().size)
                assertFalse(original.id in ids)
                versions.forEachIndexed { index, version ->
                    assertEquals(original.copy(id = ids[index]), repository.getWork(ids[index]))
                    assertEquals(listOf(version.copy(workId = ids[index])), repository.getSources(ids[index]))
                    assertEquals(lists, repository.getListEntries(ids[index]))
                }
                assertEquals(saved.copy(workId = ids[0]), repository.getSourceProgress(ids[0], 1, "/a"))
                assertNull(repository.getSourceProgress(ids[1], 1, "/b"))
                assertNull(repository.getSourceProgress(ids[2], 1, "/c"))
                val works = repository.getAllWorksAsFlow().first()
                repository.separateSources(selected, now = 9_000)
                assertEquals(works, repository.getAllWorksAsFlow().first())
                assertEquals(3, works.size)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `separating single source untracked and empty selections is a no op`() = runTest {
        val repository = repository()
        val original = work()
        repository.upsertWork(original)
        repository.upsertSource(source())
        repository.separateSources(emptySet(), now = 2_000)
        repository.separateSources(setOf(1L to "/example", 99L to "/untracked"), now = 3_000)
        assertEquals(listOf(original), repository.getAllWorksAsFlow().first())
        assertEquals(listOf(source()), repository.getSources(original.id))
        assertNull(repository.getWorkIdBySourceUrl(99, "/untracked"))
    }

    @Test
    fun `separation failure rolls back earlier work and moved progress across the entire selection`() = runTest {
        for (foreignKeys in listOf("ON", "OFF")) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                driver.execute(null, "PRAGMA foreign_keys = $foreignKeys", 0)
                val repository = repository(driver)
                val originals = listOf(work("work-1"), work("work-2"))
                val versions = originals.flatMap { original ->
                    listOf(source(original.id, "/${original.id}/move"), source(original.id, "/${original.id}/keep"))
                }
                originals.forEach { repository.upsertWork(it) }
                versions.forEach { repository.upsertSource(it) }
                val progress = versions.map { version ->
                    LocalTrackedWorkSourceProgress(
                        workId = version.workId,
                        source = version.source,
                        url = version.url,
                        chapterNumber = 12.0,
                        chapterUrl = "${version.url}/chapter",
                        chapterLabel = "Chapter 12",
                        progressAt = 2_000,
                        updatedAt = 2_000,
                    )
                }
                progress.forEach { repository.upsertSourceProgress(it) }
                val lists = listOf(LocalTrackedWorkList("Favorites", 1_234))
                originals.forEach { repository.replaceListEntries(it.id, lists) }
                // Fail after the first work has been separated and the second source's old rows deleted.
                driver.execute(
                    null,
                    """
                    CREATE TRIGGER fail_second_separation BEFORE INSERT ON local_tracked_work_source
                    WHEN NEW.url = '/work-2/move' AND NEW.work_id != 'work-2'
                    BEGIN SELECT RAISE(ABORT, 'injected separation failure'); END
                    """.trimIndent(),
                    0,
                )
                val failure = runCatching {
                    repository.separateSources(linkedSetOf(1L to "/work-1/move", 1L to "/work-2/move"), now = 3_000)
                }.exceptionOrNull()
                assertTrue(failure != null)
                assertTrue(
                    generateSequence(failure) { it.cause }
                        .any { it.message.orEmpty().contains("injected separation failure") },
                )
                assertEquals(originals, repository.getAllWorksAsFlow().first())
                originals.forEach { original ->
                    assertEquals(
                        versions.filter { it.workId == original.id }.toSet(),
                        repository.getSources(original.id).toSet(),
                    )
                    assertEquals(lists, repository.getListEntries(original.id))
                }
                progress.forEach {
                    assertEquals(it, repository.getSourceProgress(it.workId, it.source, it.url))
                    assertEquals(it.workId, repository.getWorkIdBySourceUrl(it.source, it.url))
                }
                driver.execute(null, "DROP TRIGGER fail_second_separation", 0)
                repository.separateSources(linkedSetOf(1L to "/work-1/move", 1L to "/work-2/move"), now = 3_000)
                assertEquals(4, repository.getAllWorksAsFlow().first().size)
                progress.forEach {
                    val id = repository.getWorkIdBySourceUrl(it.source, it.url)!!
                    assertEquals(it.copy(workId = id), repository.getSourceProgress(id, it.source, it.url))
                    if (it.url.endsWith("/move")) {
                        assertFalse(id == it.workId)
                        assertNull(repository.getSourceProgress(it.workId, it.source, it.url))
                    } else {
                        assertEquals(it.workId, id)
                    }
                }
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `deleting local work removes only its local links and lists`() = runTest {
        val repository = repository()
        repository.upsertWork(work("work-1"))
        repository.upsertWork(work("work-2"))
        repository.upsertSource(source("work-1"))
        repository.upsertSource(source("work-2", "/other"))
        repository.replaceLists("work-1", listOf("Reading"))

        repository.deleteWork("work-1")

        assertEquals(null, repository.getWork("work-1"))
        assertEquals(emptyList<LocalTrackedWorkSource>(), repository.getSources("work-1"))
        assertEquals(emptyList<String>(), repository.getLists("work-1"))
        assertEquals(listOf(source("work-2", "/other")), repository.getSources("work-2"))
    }

    @Test
    fun `progress resolves by source url and never regresses recognized chapter number`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        assertEquals("work-1", repository.getWorkIdBySourceUrl(1, "/example"))
        repository.recordProgress("work-1", 1, 10.0, "/chapter-10", "Chapter 10", 2_000)
        repository.recordProgress("work-1", 1, 9.0, "/chapter-9", "Chapter 9", 3_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(10.0, retained.lastChapterNumber)
        assertEquals("/chapter-10", retained.lastChapterUrl)

        repository.recordProgress("work-1", 1, 11.0, "/chapter-11", "Chapter 11", 4_000)
        val advanced = repository.getWork("work-1")!!
        assertEquals(11.0, advanced.lastChapterNumber)
        assertEquals("/chapter-11", advanced.lastChapterUrl)
    }

    @Test
    fun `same chapter progress is timestamp monotonic at work level`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        repository.recordProgress("work-1", 1, 12.0, "/chapter-12-newer", "Chapter 12", 4_000)
        repository.recordProgress("work-1", 1, 12.0, "/chapter-12-older", "Chapter 12", 3_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(12.0, retained.lastChapterNumber)
        assertEquals("/chapter-12-newer", retained.lastChapterUrl)
        assertEquals(4_000L, retained.lastProgressAt)
    }

    @Test
    fun `pending progress advances monotonically until a source chapter is resolved`() = runTest {
        val repository = repository()
        repository.upsertWork(work().copy(status = LocalTrackedWorkStatus.PLANNED))

        repository.recordPendingProgress("work-1", 1, 5.0, "Chapter 5", 4_000)
        repository.recordPendingProgress("work-1", 1, 3.0, "Chapter 3", 5_000)
        repository.recordPendingProgress("work-1", 1, 5.0, "Older chapter 5", 3_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(5.0, retained.lastChapterNumber)
        assertEquals("Chapter 5", retained.lastChapterLabel)
        assertEquals(4_000L, retained.lastProgressAt)
        assertEquals(LocalTrackedWorkStatus.READING, retained.status)
        assertEquals(4_000L, retained.startDate)
    }

    @Test
    fun `pending progress cannot replace a resolved source chapter`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        repository.recordProgress("work-1", 1, 12.0, "/chapter-12", "Chapter 12", 4_000)

        repository.recordPendingProgress("work-1", 1, 20.0, "Chapter 20", 5_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(12.0, retained.lastChapterNumber)
        assertEquals("/chapter-12", retained.lastChapterUrl)
        assertEquals("Chapter 12", retained.lastChapterLabel)
        assertEquals(4_000L, retained.lastProgressAt)
    }

    @Test
    fun `advancing chapter does not regress work update timestamp`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        repository.recordProgress("work-1", 1, 12.0, "/chapter-12", "Chapter 12", 4_000)
        repository.recordProgress("work-1", 1, 13.0, "/chapter-13", "Chapter 13", 3_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(13.0, retained.lastChapterNumber)
        assertEquals(3_000L, retained.lastProgressAt)
        assertEquals(4_000L, retained.updatedAt)
    }

    @Test
    fun `first recorded progress derives the local start date`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        repository.recordProgress("work-1", 1, 1.0, "/chapter-1", "Chapter 1", 2_000)

        assertEquals(2_000L, repository.getWork("work-1")!!.startDate)
    }

    @Test
    fun `first recorded progress moves planned work into reading without overriding terminal status`() = runTest {
        val repository = repository()
        repository.upsertWork(work().copy(status = LocalTrackedWorkStatus.PLANNED))
        repository.upsertSource(source())

        repository.recordProgress("work-1", 1, 1.0, "/chapter-1", "Chapter 1", 2_000)

        assertEquals(LocalTrackedWorkStatus.READING, repository.getWork("work-1")!!.status)
        LocalTrackedWorkStatus.values()
            .filter { it != LocalTrackedWorkStatus.PLANNED && it != LocalTrackedWorkStatus.READING }
            .forEach { status ->
                repository.upsertWork(work().copy(status = status))
                repository.recordProgress("work-1", 1, 2.0, "/chapter-2-${status.name}", "Chapter 2", 3_000)
                assertEquals(status, repository.getWork("work-1")!!.status)
            }
    }

    @Test
    fun `unknown chapter labels do not replace recognized progress`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        repository.recordProgress("work-1", 1, 4.0, "/chapter-4", "Chapter 4", 2_000)

        repository.recordProgress("work-1", 1, null, "/special", "Special", 3_000)

        val retained = repository.getWork("work-1")!!
        assertEquals(4.0, retained.lastChapterNumber)
        assertEquals("/chapter-4", retained.lastChapterUrl)
    }

    @Test
    fun `source progress preserves target identity and inheritance provenance`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        val progress = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = 12.0,
            chapterUrl = "/target-chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 2_000,
            inheritedFromSource = 2,
            inheritedFromUrl = "/origin",
            updatedAt = 2_000,
        )

        repository.upsertSourceProgress(progress)

        assertEquals(progress, repository.getSourceProgress("work-1", 1, "/example"))
        repository.setInheritanceOptedOut("work-1", 1, "/example", optedOut = true, updatedAt = 3_000)
        assertTrue(repository.getSources("work-1").single().inheritanceOptedOut)
    }

    @Test
    fun `source progress requires an existing source link`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        val progress = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/missing",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 2_000,
            updatedAt = 2_000,
        )

        var rejected = false
        try {
            repository.upsertSourceProgress(progress)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertNull(repository.getSourceProgress("work-1", 1, "/missing"))
    }

    @Test
    fun `stale source metadata cannot overwrite newer confirmation or opt out`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(
            source().copy(
                confirmation = LocalTrackedWorkSourceConfirmation.SUGGESTED,
                inheritanceOptedOut = true,
                updatedAt = 3_000,
            ),
        )

        repository.upsertSource(
            source().copy(
                confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                inheritanceOptedOut = false,
                updatedAt = 2_000,
            ),
        )

        val retained = repository.getSources("work-1").single()
        assertEquals(LocalTrackedWorkSourceConfirmation.SUGGESTED, retained.confirmation)
        assertTrue(retained.inheritanceOptedOut)
        assertEquals(3_000, retained.updatedAt)
    }

    @Test
    fun `newer source metadata cannot erase an explicit progress sharing opt out`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        repository.setInheritanceOptedOut("work-1", 1, "/example", optedOut = true, updatedAt = 2_000)

        repository.upsertSource(source().copy(title = "Updated title", updatedAt = 3_000))

        val retained = repository.getSources("work-1").single()
        assertEquals("Updated title", retained.title)
        assertTrue(retained.inheritanceOptedOut)

        repository.setInheritanceOptedOut("work-1", 1, "/example", optedOut = false, updatedAt = 4_000)
        assertFalse(repository.getSources("work-1").single().inheritanceOptedOut)
    }

    @Test
    fun `stale source progress cannot overwrite newer child fields`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        val newer = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 4_000,
            updatedAt = 4_000,
        )
        repository.upsertSourceProgress(newer)

        repository.upsertSourceProgress(
            newer.copy(
                chapterNumber = 11.0,
                chapterUrl = "/chapter-11",
                chapterLabel = "Chapter 11",
                progressAt = 3_000,
                updatedAt = 3_000,
            ),
        )

        assertEquals(newer, repository.getSourceProgress("work-1", 1, "/example"))
    }

    @Test
    fun `recorded source progress never regresses or loses a recognized chapter`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        val chapter12 = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 2_000,
            updatedAt = 2_000,
        )
        repository.recordSourceProgress(chapter12)

        repository.recordSourceProgress(
            chapter12.copy(
                chapterNumber = 11.0,
                chapterUrl = "/chapter-11",
                chapterLabel = "Chapter 11",
                progressAt = 3_000,
                updatedAt = 3_000,
            ),
        )
        repository.recordSourceProgress(
            chapter12.copy(
                chapterNumber = null,
                chapterUrl = "/special",
                chapterLabel = "Special",
                progressAt = 4_000,
                updatedAt = 4_000,
            ),
        )

        assertEquals(chapter12, repository.getSourceProgress("work-1", 1, "/example"))

        val chapter13 = chapter12.copy(
            chapterNumber = 13.0,
            chapterUrl = "/chapter-13",
            chapterLabel = "Chapter 13",
            progressAt = 5_000,
            updatedAt = 5_000,
        )
        repository.recordSourceProgress(chapter13)

        assertEquals(chapter13, repository.getSourceProgress("work-1", 1, "/example"))
    }

    @Test
    fun `recorded source progress requires an existing source link`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        val progress = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/missing",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 2_000,
            updatedAt = 2_000,
        )

        var rejected = false
        try {
            repository.recordSourceProgress(progress)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertNull(repository.getSourceProgress("work-1", 1, "/missing"))
    }

    @Test
    fun `recorded unknown source progress is timestamp monotonic`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        val unknown = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = null,
            chapterUrl = "/special-1",
            chapterLabel = "Special 1",
            progressAt = 2_000,
            updatedAt = 2_000,
        )
        repository.recordSourceProgress(unknown)
        repository.recordSourceProgress(
            unknown.copy(
                chapterUrl = "/special-0",
                chapterLabel = "Special 0",
                progressAt = 1_000,
                updatedAt = 1_000,
            ),
        )

        assertEquals(unknown, repository.getSourceProgress("work-1", 1, "/example"))
    }

    @Test
    fun `recorded same chapter progress is timestamp monotonic`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        val newer = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12-newer",
            chapterLabel = "Chapter 12 (newer)",
            progressAt = 3_000,
            updatedAt = 3_000,
        )
        repository.recordSourceProgress(newer)
        repository.recordSourceProgress(
            newer.copy(
                chapterUrl = "/chapter-12-older",
                chapterLabel = "Chapter 12 (older)",
                progressAt = 2_000,
                updatedAt = 2_000,
            ),
        )

        assertEquals(newer, repository.getSourceProgress("work-1", 1, "/example"))
    }

    @Test
    fun `recorded higher chapter does not regress source update timestamp`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())

        val chapter12 = LocalTrackedWorkSourceProgress(
            workId = "work-1",
            source = 1,
            url = "/example",
            chapterNumber = 12.0,
            chapterUrl = "/chapter-12",
            chapterLabel = "Chapter 12",
            progressAt = 4_000,
            updatedAt = 4_000,
        )
        repository.recordSourceProgress(chapter12)
        repository.recordSourceProgress(
            chapter12.copy(
                chapterNumber = 13.0,
                chapterUrl = "/chapter-13",
                chapterLabel = "Chapter 13",
                progressAt = 3_000,
                updatedAt = 3_000,
            ),
        )

        val retained = repository.getSourceProgress("work-1", 1, "/example")!!
        assertEquals(13.0, retained.chapterNumber)
        assertEquals(3_000L, retained.progressAt)
        assertEquals(4_000L, retained.updatedAt)
    }

    @Test
    fun `deleting a source also deletes its source progress`() = runTest {
        val repository = repository()
        repository.upsertWork(work())
        repository.upsertSource(source())
        repository.upsertSourceProgress(
            LocalTrackedWorkSourceProgress(
                workId = "work-1",
                source = 1,
                url = "/example",
                chapterNumber = null,
                chapterUrl = "/special",
                chapterLabel = "Special",
                progressAt = 2_000,
                updatedAt = 2_000,
            ),
        )

        repository.deleteSource("work-1", 1, "/example")

        assertNull(repository.getSourceProgress("work-1", 1, "/example"))
    }
}
