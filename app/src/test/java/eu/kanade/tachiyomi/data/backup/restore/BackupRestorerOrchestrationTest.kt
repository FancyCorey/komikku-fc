package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.track.interactor.RecordLocalTrackedChapterProgress
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCrossSourceGroupPrimary
import eu.kanade.tachiyomi.data.backup.models.BackupCrossSourceMangaLink
import eu.kanade.tachiyomi.data.backup.models.BackupLocalTrackedWork
import eu.kanade.tachiyomi.data.backup.models.BackupLocalTrackedWorkList
import eu.kanade.tachiyomi.data.backup.models.BackupLocalTrackedWorkSource
import eu.kanade.tachiyomi.data.backup.models.BackupLocalTrackedWorkSourceProgress
import eu.kanade.tachiyomi.data.backup.models.BackupMangaTaste
import eu.kanade.tachiyomi.data.backup.restore.restorers.LocalTrackerBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.TasteRestorer
import exh.ocr.OcrIndexRepository
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
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
import tachiyomi.data.taste.TasteRepositoryImpl
import tachiyomi.data.tracker.LocalTrackerRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.UpsertCrossSourceMangaLinks
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.repository.TasteRepository
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

/**
 * AG15-F4: BackupRestorer orchestration was previously covered only per-component
 * (LocalTrackerBackupRestorerTest, TasteBackupEndToEndRoundTripTest) and, at the source-text level,
 * for its bookkeeping mutex (BackupRestorerBookkeepingSourceTest). None of those exercise the real
 * BackupRestorer wiring itself -- that options gating correctly selects which restorer runs on which
 * backup slice, and that the result is one restored graph with the right work/source/progress/list
 * (local tracker) and group/rating (taste) counts.
 *
 * The file-decoding boundary (BackupDecoder against a real content Uri) needs a real Android
 * Context/ContentResolver and is out of reach for this pure-JVM test module (no Robolectric here);
 * [BackupRestorer.restoreDecodedBackup] is the internal seam extracted for exactly this purpose, so
 * this drives the real BackupRestorer class from an already-decoded (and, for extra rigor, real
 * protobuf-wire-round-tripped) [Backup] instead.
 */
class BackupRestorerOrchestrationTest {

    private fun freshLocalTrackerRepository(): LocalTrackerRepositoryImpl {
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
        return LocalTrackerRepositoryImpl(AndroidDatabaseHandler(database, driver))
    }

    private class TasteRepositories(val taste: TasteRepositoryImpl, val manga: MangaRepositoryImpl)

    /**
     * Taste and manga repositories MUST share one database: restoring a manga rating without a
     * matching local manga row falls back to inserting a minimal one through [MangaRepository]
     * (see [TasteRestorer.restoreMinimalRatedMangaIdentity]) -- a separately-mocked manga repository
     * would make every rating restore silently fail with "missing or invalid manga identity"
     * instead of exercising the real path.
     */
    private fun freshTasteRepositories(): TasteRepositories {
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
        return TasteRepositories(taste = TasteRepositoryImpl(handler), manga = MangaRepositoryImpl(handler))
    }

    private fun tasteRestorer(tasteRepository: TasteRepository, mangaRepository: MangaRepository) = TasteRestorer(
        tasteRepository = tasteRepository,
        alternateSourceBridgeRepository = mockk(relaxed = true),
        mangaRepository = mangaRepository,
        getManga = GetManga(mangaRepository),
        getMangaTaste = GetMangaTaste(tasteRepository),
        getTagTaste = mockk(relaxed = true),
        getTagAliases = mockk(relaxed = true),
        getDisabledSources = mockk(relaxed = true),
        setSourceEnabled = mockk(relaxed = true),
        upsertTagAlias = mockk(relaxed = true),
        getCrossSourceMangaLinks = GetCrossSourceMangaLinks(tasteRepository),
        upsertCrossSourceMangaLinks = UpsertCrossSourceMangaLinks(tasteRepository),
        getCrossSourceGroupPrimary = GetCrossSourceGroupPrimary(tasteRepository),
        getMangaSourceQualitySignals = mockk(relaxed = true),
        upsertMangaSourceQualitySignal = mockk(relaxed = true),
        sourcePreferences = mockk(relaxed = true),
    )

    // Every BackupRestorer sub-restorer default resolves several Injekt.get() collaborators AT
    // CONSTRUCTION time, before RestoreOptions ever gates whether it is actually invoked -- so each
    // one this test does not exercise must still be overridden with a relaxed mock, not left as the
    // Injekt-backed default (which throws InjektionException with no Injekt bootstrap in a unit test).
    private fun restorer(
        localTrackerRepository: LocalTrackerRepository,
        tasteRepositories: TasteRepositories,
        ocrIndexRepository: OcrIndexRepository = mockk(relaxed = true),
    ) = BackupRestorer(
        context = mockk<Context>(relaxed = true),
        notifier = mockk<BackupNotifier>(relaxed = true),
        isSync = false,
        categoriesRestorer = mockk(relaxed = true),
        preferenceRestorer = mockk(relaxed = true),
        extensionStoreRestorer = mockk(relaxed = true),
        mangaRestorer = mockk<MangaRestorer>(relaxed = true),
        savedSearchRestorer = mockk(relaxed = true),
        feedRestorer = mockk(relaxed = true),
        tasteRestorer = tasteRestorer(tasteRepositories.taste, tasteRepositories.manga),
        localTrackerBackupRestorer = LocalTrackerBackupRestorer(localTrackerRepository),
        ocrIndexRepository = ocrIndexRepository,
    )

    private fun roundTripThroughWire(backup: Backup): Backup {
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        return ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
    }

    @Test
    fun `library restore removes orphan OCR rows while preserving valid pages`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
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
            driver.execute(
                null,
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized,
                    viewer, chapter_flags, cover_last_modified, date_added)
                VALUES (1, 1, '/manga/1', 'Manga 1', 0, 0, 1, 0, 0, 0, 0)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read,
                    chapter_number, source_order, date_fetch, date_upload)
                VALUES (10, 1, '/chapter/10', 'Chapter 10', 0, 0, 0, 10, 0, 0, 0)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO ocr_indexed_page(manga_id, source_id, manga_url, manga_title,
                    chapter_id, chapter_url, chapter_name, page_index, page_identity,
                    engine_key, engine_version, raw_text, normalized_text, indexed_at)
                VALUES
                    (1, 1, '/manga/1', 'Manga 1', 10, '/chapter/10', 'Chapter 10',
                     0, 'valid', 'mlkit', '1', 'text', 'text', 0),
                    (1, 1, '/manga/1', 'Manga 1', 99, '/chapter/99', 'Chapter 99',
                     0, 'orphan', 'mlkit', '1', 'text', 'text', 0)
                """.trimIndent(),
                0,
            )
            restorer(freshLocalTrackerRepository(), freshTasteRepositories(), OcrIndexRepository(handler))
                .restoreDecodedBackup(
                    Backup(backupManga = emptyList()),
                    RestoreOptions(
                        libraryEntries = true,
                        categories = false,
                        appSettings = false,
                        extensionStores = false,
                        sourceSettings = false,
                        savedSearchesFeeds = false,
                        tasteProfile = false,
                        localTracker = false,
                    ),
                )
            var identities = emptyList<String>()
            driver.executeQuery(null, "SELECT page_identity FROM ocr_indexed_page", { cursor ->
                identities = buildList { while (cursor.next().value) add(checkNotNull(cursor.getString(0))) }
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 0)
            assertEquals(listOf("valid"), identities)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `restoring a decoded backup drives both the local tracker and taste owners and restores the full graph`() = runTest {
        val now = System.currentTimeMillis()
        val backupWork = BackupLocalTrackedWork(
            id = "orch-work-1",
            title = "Orchestration Fixture",
            normalizedTitle = "orchestration fixture",
            status = LocalTrackedWorkStatus.READING.name,
            createdAt = now,
            updatedAt = now,
            sources = listOf(
                BackupLocalTrackedWorkSource(
                    source = 10L,
                    url = "/orch/manga",
                    title = "Orchestration Fixture",
                    confidence = 100,
                    confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            hasSources = true,
            sourceProgress = listOf(
                BackupLocalTrackedWorkSourceProgress(
                    source = 10L,
                    url = "/orch/manga",
                    chapterNumber = 5.0,
                    hasChapterNumber = true,
                    chapterUrl = "/orch/manga/chapter-5",
                    chapterLabel = "Chapter 5",
                    progressAt = now,
                    updatedAt = now,
                ),
            ),
            hasSourceProgress = true,
            lists = listOf(BackupLocalTrackedWorkList(name = "reading", createdAt = now)),
            hasLists = true,
        )

        val groupId = "orch-group-1"
        val backupTaste = BackupMangaTaste(
            mangaId = 9001L,
            source = 20L,
            url = "/orch/taste-manga",
            title = "Orchestration Taste Fixture",
            rating = MangaRating.LOVE.value,
            updatedAt = now,
        )
        val backupLink = BackupCrossSourceMangaLink(
            source = 20L,
            url = "/orch/taste-manga",
            groupId = groupId,
            title = "Orchestration Taste Fixture",
            updatedAt = now,
        )
        val backupPrimary = BackupCrossSourceGroupPrimary(groupId = groupId, source = 20L, url = "/orch/taste-manga", updatedAt = now)

        val backup = roundTripThroughWire(
            Backup(
                backupManga = emptyList(),
                backupLocalTrackedWorks = listOf(backupWork),
                backupMangaTastes = listOf(backupTaste),
                backupCrossSourceMangaLinks = listOf(backupLink),
                backupCrossSourceGroupPrimaries = listOf(backupPrimary),
            ),
        )
        assertEquals(1, backup.backupLocalTrackedWorks.size)
        assertEquals(1, backup.backupMangaTastes.size)

        val localTrackerRepository = freshLocalTrackerRepository()
        val tasteRepositories = freshTasteRepositories()
        val options = RestoreOptions(
            libraryEntries = false,
            categories = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            savedSearchesFeeds = false,
            tasteProfile = true,
            localTracker = true,
        )

        restorer(localTrackerRepository, tasteRepositories).restoreDecodedBackup(backup, options)

        // Local tracker: work, source, progress, and list counts.
        val restoredWork = localTrackerRepository.getWork("orch-work-1")
        assertTrue(restoredWork != null, "expected the local tracked work to be restored")
        assertEquals(1, localTrackerRepository.getSources("orch-work-1").size)
        assertEquals(1, localTrackerRepository.getSourceProgressForWork("orch-work-1").size)
        assertEquals(1, localTrackerRepository.getListEntries("orch-work-1").size)

        // Taste: group (cross-source link/primary) and rating counts.
        val taste = tasteRepositories.taste
        assertEquals(1, taste.getAllMangaTastes().size)
        assertEquals(1, taste.getAllCrossSourceMangaLinks().size)
        assertEquals(1, taste.getAllCrossSourceGroupPrimaries().size)
        assertEquals(MangaRating.LOVE.value, taste.getAllMangaTastes().single().rating)
    }

    @Test
    fun `decoded local tracker replay marks populated real chapters through the chapter repository`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
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
            val localTracker = LocalTrackerRepositoryImpl(handler)
            val mangaRepository = MangaRepositoryImpl(handler)
            val chapterRepository = ChapterRepositoryImpl(handler)
            insertReplayManga(driver)
            insertReplayChapters(driver)

            val replay = RecordLocalTrackedChapterProgress(
                localTrackerRepository = localTracker,
                trackPreferences = TrackPreferences(FakePreferenceStore()),
                getCrossSourceMangaLinks = mockk(relaxed = true),
                identityAuthorizationResolver = mockk<CrossSourceIdentityAuthorizationResolver>(relaxed = true),
                alternateSourceBridgeRepository = mockk(relaxed = true),
                mangaRepository = mangaRepository,
                chapterRepository = chapterRepository,
            )
            val now = System.currentTimeMillis()
            val backupWork = BackupLocalTrackedWork(
                id = "replay-work",
                title = "Replay Fixture",
                normalizedTitle = "replay fixture",
                status = LocalTrackedWorkStatus.READING.name,
                createdAt = now,
                updatedAt = now,
                sources = listOf(
                    BackupLocalTrackedWorkSource(
                        source = 10L,
                        url = "/replay/manga",
                        title = "Replay Fixture",
                        confidence = 100,
                        confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED.name,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
                hasSources = true,
                sourceProgress = listOf(
                    BackupLocalTrackedWorkSourceProgress(
                        source = 10L,
                        url = "/replay/manga",
                        chapterNumber = 2.0,
                        hasChapterNumber = true,
                        chapterUrl = "/replay/manga/chapter-2",
                        chapterLabel = "Chapter 2",
                        progressAt = now,
                        updatedAt = now,
                    ),
                ),
                hasSourceProgress = true,
            )
            val decoded = roundTripThroughWire(
                Backup(
                    backupManga = emptyList(),
                    backupLocalTrackedWorks = listOf(backupWork),
                ),
            )
            val errors = LocalTrackerBackupRestorer(localTracker, replay).restore(decoded.backupLocalTrackedWorks)

            assertEquals(emptyList<String>(), errors)
            assertEquals(1, localTracker.getSources("replay-work").size)
            assertEquals(1, localTracker.getSourceProgressForWork("replay-work").size)
            assertEquals(2L, driver.longScalar("SELECT COUNT(*) FROM chapters WHERE manga_id = 7001 AND read = 1"))
            assertEquals(1L, driver.longScalar("SELECT COUNT(*) FROM chapters WHERE manga_id = 7001 AND read = 0"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `cancellation after real chapter writes completes local replay then propagates`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
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
            val localTracker = LocalTrackerRepositoryImpl(handler)
            val mangaRepository = MangaRepositoryImpl(handler)
            val chapterRepository = ChapterRepositoryImpl(handler)
            insertReplayManga(driver)
            insertReplayChapters(driver)
            insertSecondReplayManga(driver)

            val chapterWriteCommitted = CompletableDeferred<Unit>()
            val continueReplay = CompletableDeferred<Unit>()
            var firstChapterWrite = true
            val observedChapterRepository = object : ChapterRepository by chapterRepository {
                override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                    chapterRepository.updateAll(chapterUpdates)
                    if (firstChapterWrite) {
                        firstChapterWrite = false
                        chapterWriteCommitted.complete(Unit)
                        continueReplay.await()
                    }
                }
            }
            val replay = RecordLocalTrackedChapterProgress(
                localTrackerRepository = localTracker,
                trackPreferences = TrackPreferences(FakePreferenceStore()).also {
                    it.autoInheritLocalProgress().set(false)
                },
                getCrossSourceMangaLinks = mockk(relaxed = true),
                identityAuthorizationResolver = mockk(relaxed = true),
                alternateSourceBridgeRepository = mockk(relaxed = true),
                mangaRepository = mangaRepository,
                chapterRepository = observedChapterRepository,
            )
            val restored = async {
                LocalTrackerBackupRestorer(localTracker, replay).restore(listOf(replayBackupWork()))
            }

            chapterWriteCommitted.await()
            val readsBeforeResume = driver.longScalar("SELECT COUNT(*) FROM chapters WHERE read = 1")
            assertTrue(readsBeforeResume in 1L..3L)
            restored.cancel(CancellationException("restore-cancelled"))
            continueReplay.complete(Unit)
            restored.join()

            var cancellation: CancellationException? = null
            try {
                restored.await()
            } catch (failure: CancellationException) {
                cancellation = failure
            }
            assertEquals("restore-cancelled", cancellation?.message)
            assertEquals(4L, driver.longScalar("SELECT COUNT(*) FROM chapters WHERE read = 1"))
            assertEquals("/replay/manga/chapter-2", localTracker.getWork("replay-work")?.lastChapterUrl)
            assertEquals(2, localTracker.getSourceProgressForWork("replay-work").size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `cancellation during real restore graph write rolls back and skips chapter replay`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
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
            val localTracker = LocalTrackerRepositoryImpl(handler)
            val mangaRepository = MangaRepositoryImpl(handler)
            val chapterRepository = ChapterRepositoryImpl(handler)
            insertReplayManga(driver)
            insertReplayChapters(driver)

            val graphWriteCommitted = CompletableDeferred<Unit>()
            val continueRestore = CompletableDeferred<Unit>()
            val observedLocalTracker = object : LocalTrackerRepository by localTracker {
                override suspend fun upsertSourceProgress(progress: tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress) {
                    localTracker.upsertSourceProgress(progress)
                    graphWriteCommitted.complete(Unit)
                    continueRestore.await()
                }
            }
            var chapterReplayStarted = false
            val observedChapterRepository = object : ChapterRepository by chapterRepository {
                override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                    chapterReplayStarted = true
                    chapterRepository.updateAll(chapterUpdates)
                }
            }
            val replay = RecordLocalTrackedChapterProgress(
                localTrackerRepository = localTracker,
                trackPreferences = TrackPreferences(FakePreferenceStore()).also {
                    it.autoInheritLocalProgress().set(false)
                },
                getCrossSourceMangaLinks = mockk(relaxed = true),
                identityAuthorizationResolver = mockk(relaxed = true),
                alternateSourceBridgeRepository = mockk(relaxed = true),
                mangaRepository = mangaRepository,
                chapterRepository = observedChapterRepository,
            )
            val restore = async {
                LocalTrackerBackupRestorer(observedLocalTracker, replay).restore(listOf(replayBackupWork()))
            }

            graphWriteCommitted.await()
            restore.cancel(CancellationException("restore-cancelled-before-replay"))
            continueRestore.complete(Unit)
            restore.join()

            var cancellation: CancellationException? = null
            try {
                restore.await()
            } catch (failure: CancellationException) {
                cancellation = failure
            }
            assertEquals("restore-cancelled-before-replay", cancellation?.message)
            assertEquals(null, localTracker.getWork("replay-work"))
            assertEquals(0L, driver.longScalar("SELECT COUNT(*) FROM chapters WHERE manga_id = 7001 AND read = 1"))
            assertEquals(false, chapterReplayStarted)
        } finally {
            driver.close()
        }
    }

    private fun replayBackupWork(): BackupLocalTrackedWork {
        val now = System.currentTimeMillis()
        return BackupLocalTrackedWork(
            id = "replay-work",
            title = "Replay Fixture",
            normalizedTitle = "replay fixture",
            status = LocalTrackedWorkStatus.READING.name,
            lastChapterSource = 10L,
            lastChapterNumber = 2.0,
            lastChapterUrl = "/replay/manga/chapter-2",
            lastChapterLabel = "Chapter 2",
            lastProgressAt = now,
            createdAt = now,
            updatedAt = now,
            sources = listOf(
                BackupLocalTrackedWorkSource(
                    source = 10L,
                    url = "/replay/manga",
                    title = "Replay Fixture",
                    confidence = 100,
                    confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED.name,
                    createdAt = now,
                    updatedAt = now,
                ),
                BackupLocalTrackedWorkSource(
                    source = 11L,
                    url = "/replay/manga-alt",
                    title = "Replay Fixture Alternate",
                    confidence = 100,
                    confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            hasSources = true,
            sourceProgress = listOf(
                BackupLocalTrackedWorkSourceProgress(
                    source = 10L,
                    url = "/replay/manga",
                    chapterNumber = 2.0,
                    hasChapterNumber = true,
                    chapterUrl = "/replay/manga/chapter-2",
                    chapterLabel = "Chapter 2",
                    progressAt = now,
                    updatedAt = now,
                ),
                BackupLocalTrackedWorkSourceProgress(
                    source = 11L,
                    url = "/replay/manga-alt",
                    chapterNumber = 2.0,
                    hasChapterNumber = true,
                    chapterUrl = "/replay/manga-alt/chapter-2",
                    chapterLabel = "Chapter 2",
                    progressAt = now,
                    updatedAt = now,
                ),
            ),
            hasSourceProgress = true,
        )
    }

    private fun insertReplayManga(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            INSERT INTO mangas(
                _id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added
            ) VALUES (7001, 10, '/replay/manga', 'Replay Fixture', 0, 0, 1, 0, 0, 0, 0)
            """.trimIndent(),
            0,
        )
    }

    private fun insertSecondReplayManga(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            INSERT INTO mangas(
                _id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added
            ) VALUES (7002, 11, '/replay/manga-alt', 'Replay Fixture Alternate', 0, 0, 1, 0, 0, 0, 0)
            """.trimIndent(),
            0,
        )
        (1..3).forEach { number ->
            driver.execute(
                null,
                """
                INSERT INTO chapters(
                    _id, manga_id, url, name, read, bookmark, last_page_read,
                    chapter_number, source_order, date_fetch, date_upload
                ) VALUES (${8000 + number}, 7002, '/replay/manga-alt/chapter-$number', 'Chapter $number', 0, 0, 0, $number, 0, 0, 0)
                """.trimIndent(),
                0,
            )
        }
    }

    private fun insertReplayChapters(driver: JdbcSqliteDriver) {
        (1..3).forEach { number ->
            driver.execute(
                null,
                """
                INSERT INTO chapters(
                    _id, manga_id, url, name, read, bookmark, last_page_read,
                    chapter_number, source_order, date_fetch, date_upload
                ) VALUES (${7000 + number}, 7001, '/replay/manga/chapter-$number', 'Chapter $number', 0, 0, 0, $number, 0, 0, 0)
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
}
