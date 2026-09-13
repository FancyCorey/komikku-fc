package eu.kanade.domain.track.interactor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
import tachiyomi.data.tracker.LocalTrackerRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.repository.AlternateSourceBridgeRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

class LocalTrackedChapterReadSyncTest {

    @Test
    fun `external pending progress resolves after offset chapter rows arrive`() = runTest {
        val repositories = realRepositories()
        val repository = repositories.localTracker
        val mangaRepository = repositories.manga
        val chapterRepository = repositories.chapter
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoSyncLocalTrackingFromTrackers().set(true)
            it.autoInheritLocalProgress().set(false)
        }
        val manga = repositories.manga.insertNetworkManga(
            listOf(Manga.create().copy(source = 9L, url = "/manga", ogTitle = "Manga")),
            updateInfo = false,
        ).single()
        val work = LocalTrackedWork(
            id = "work",
            title = manga.title,
            normalizedTitle = "manga",
            status = tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING,
            lastChapterSource = null,
            lastChapterNumber = null,
            lastChapterUrl = null,
            lastChapterLabel = null,
            lastProgressAt = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository.upsertWork(work)
        repository.upsertSource(
            LocalTrackedWorkSource(
                workId = work.id,
                source = manga.source,
                url = manga.url,
                title = manga.title,
                confidence = 100,
                confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        coEvery { links.awaitBySourceUrl(manga.source, manga.url) } returns null
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()
        val replay = RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        )
        val sync = SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            recordLocalTrackedChapterProgress = replay,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        )

        sync.sync(
            Track(
                id = 1L,
                mangaId = manga.id,
                trackerId = 10L,
                remoteId = 20L,
                libraryId = null,
                title = manga.title,
                lastChapterRead = 3.0,
                totalChapters = 0L,
                status = 1L,
                score = 0.0,
                remoteUrl = "https://tracker.example/manga",
                startDate = 0L,
                finishDate = 0L,
                private = false,
            ),
        )
        val pending = repository.getWork(work.id)!!
        assertEquals(3.0, pending.lastChapterNumber)
        assertEquals(null, pending.lastChapterUrl)

        chapterRepository.addAll(
            (8L..11L).map { number ->
                chapter(-1L, "/manga/ch-$number", number.toDouble()).copy(mangaId = manga.id)
            },
        )
        replay.synchronize()

        val resolved = repository.getWork(work.id)!!
        assertEquals(10.0, resolved.lastChapterNumber)
        assertEquals("/manga/ch-10", resolved.lastChapterUrl)
        val persistedChapters = chapterRepository.getChapterByMangaId(manga.id).associateBy { it.chapterNumber }
        assertEquals(true, persistedChapters.getValue(8.0).read)
        assertEquals(true, persistedChapters.getValue(9.0).read)
        assertEquals(true, persistedChapters.getValue(10.0).read)
        assertEquals(false, persistedChapters.getValue(11.0).read)
    }

    @Test
    fun `synchronize marks chapters through persisted source progress even when propagation is off`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val manga = Manga.create().copy(id = 42L, source = 9L, url = "/manga")
        val progress = LocalTrackedWorkSourceProgress(
            workId = "work",
            source = manga.source,
            url = manga.url,
            chapterNumber = 3.0,
            chapterUrl = "/manga/ch-3",
            chapterLabel = "Chapter 3",
            progressAt = 10L,
            updatedAt = 10L,
        )
        val work = LocalTrackedWork(
            id = "work",
            title = "Manga",
            normalizedTitle = "manga",
            status = tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING,
            lastChapterSource = manga.source,
            lastChapterNumber = progress.chapterNumber,
            lastChapterUrl = progress.chapterUrl,
            lastChapterLabel = progress.chapterLabel,
            lastProgressAt = progress.progressAt,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val source = LocalTrackedWorkSource(
            workId = work.id,
            source = manga.source,
            url = manga.url,
            title = manga.title,
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val chapters = listOf(
            chapter(1L, "/manga/ch-1", 1.0),
            chapter(2L, "/manga/ch-2", 2.0),
            chapter(3L, "/manga/ch-3", 3.0),
            chapter(4L, "/manga/ch-4", 4.0),
        )
        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(work))
        coEvery { repository.getSourceProgressForWork(work.id) } returns listOf(progress)
        coEvery { repository.getSources(work.id) } returns listOf(source)
        coEvery { mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga
        coEvery { chapterRepository.getChapterByUrlAndMangaId(progress.chapterUrl, manga.id) } returns chapters[2]
        coEvery { chapterRepository.getChapterByMangaId(manga.id) } returns chapters
        coEvery { links.awaitBySourceUrl(manga.source, manga.url) } returns null
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).synchronize()

        coVerify {
            chapterRepository.updateAll(
                match { updates ->
                    updates.map { it.id } == listOf(1L, 2L, 3L) && updates.all { it.read == true }
                },
            )
        }
    }

    @Test
    fun `synchronize replays pending numeric progress after source chapters arrive`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val manga = Manga.create().copy(id = 42L, source = 9L, url = "/manga")
        val work = LocalTrackedWork(
            id = "work",
            title = "Manga",
            normalizedTitle = "manga",
            status = tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING,
            lastChapterSource = manga.source,
            lastChapterNumber = 3.0,
            lastChapterUrl = null,
            lastChapterLabel = "Chapter 3",
            lastProgressAt = 10L,
            createdAt = 1L,
            updatedAt = 10L,
        )
        val source = LocalTrackedWorkSource(
            workId = work.id,
            source = manga.source,
            url = manga.url,
            title = manga.title,
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 10L,
        )
        val chapters = listOf(
            chapter(1L, "/manga/ch-1", 1.0),
            chapter(2L, "/manga/ch-2", 2.0),
            chapter(3L, "/manga/ch-3", 3.0),
            chapter(4L, "/manga/ch-4", 4.0),
        )
        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(work))
        coEvery { repository.getSourceProgressForWork(work.id) } returns emptyList()
        coEvery { repository.getSources(work.id) } returns listOf(source)
        coEvery { mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/manga/ch-3", manga.id) } returns chapters[2]
        coEvery { chapterRepository.getChapterByMangaId(manga.id) } returns chapters
        coEvery { links.awaitBySourceUrl(manga.source, manga.url) } returns null
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).synchronize()

        coVerify {
            chapterRepository.updateAll(
                match { updates ->
                    updates.map { it.id } == listOf(1L, 2L, 3L) && updates.all { it.read == true }
                },
            )
            repository.recordSourceProgress(
                match {
                    it.workId == work.id &&
                        it.source == manga.source &&
                        it.chapterNumber == 3.0 &&
                        it.chapterUrl == "/manga/ch-3"
                },
            )
        }
    }

    @Test
    fun `synchronize replays pending tracker progress through a consecutive source offset`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val manga = Manga.create().copy(id = 42L, source = 9L, url = "/manga")
        val work = LocalTrackedWork(
            id = "work",
            title = "Manga",
            normalizedTitle = "manga",
            status = tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING,
            lastChapterSource = manga.source,
            lastChapterNumber = 3.0,
            lastChapterUrl = null,
            lastChapterLabel = "Chapter 3",
            lastProgressAt = 10L,
            createdAt = 1L,
            updatedAt = 10L,
        )
        val source = LocalTrackedWorkSource(
            workId = work.id,
            source = manga.source,
            url = manga.url,
            title = manga.title,
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 10L,
        )
        val chapters = listOf(
            chapter(8L, "/manga/ch-8", 8.0),
            chapter(9L, "/manga/ch-9", 9.0),
            chapter(10L, "/manga/ch-10", 10.0),
            chapter(11L, "/manga/ch-11", 11.0),
        )
        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(work))
        coEvery { repository.getSourceProgressForWork(work.id) } returns emptyList()
        coEvery { repository.getSources(work.id) } returns listOf(source)
        coEvery { mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/manga/ch-10", manga.id) } returns chapters[2]
        coEvery { chapterRepository.getChapterByMangaId(manga.id) } returns chapters
        coEvery { links.awaitBySourceUrl(manga.source, manga.url) } returns null
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).synchronize()

        coVerify {
            chapterRepository.updateAll(
                match { updates -> updates.map { it.id } == listOf(8L, 9L, 10L) && updates.all { it.read == true } },
            )
            repository.recordProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = 10.0,
                chapterUrl = "/manga/ch-10",
                chapterLabel = "Chapter 10.0",
                progressAt = 10L,
            )
            repository.recordSourceProgress(
                match { it.workId == work.id && it.chapterNumber == 10.0 && it.chapterUrl == "/manga/ch-10" },
            )
        }
    }

    private fun chapter(id: Long, url: String, number: Double): Chapter =
        Chapter.create().copy(
            id = id,
            mangaId = 42L,
            url = url,
            name = "Chapter $number",
            chapterNumber = number,
            read = false,
        )

    private data class RealRepositories(
        val localTracker: LocalTrackerRepositoryImpl,
        val manga: MangaRepositoryImpl,
        val chapter: ChapterRepositoryImpl,
    )

    private fun realRepositories(): RealRepositories {
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
        return RealRepositories(
            localTracker = LocalTrackerRepositoryImpl(handler),
            manga = MangaRepositoryImpl(handler),
            chapter = ChapterRepositoryImpl(handler),
        )
    }
}
