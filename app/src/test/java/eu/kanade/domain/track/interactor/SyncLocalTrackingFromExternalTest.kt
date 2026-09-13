package eu.kanade.domain.track.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

class SyncLocalTrackingFromExternalTest {

    @Test
    fun `external completion advances a non-terminal local work without overwriting manual terminal choices`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>()
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoSyncLocalTrackingFromTrackers().set(true)
        }
        val manga = manga(1L, 1L, "/tracker")
        val work = LocalTrackedWork(
            id = "local-work",
            title = manga.title,
            normalizedTitle = manga.title.lowercase(),
            status = LocalTrackedWorkStatus.READING,
            lastChapterSource = null,
            lastChapterNumber = null,
            lastChapterUrl = null,
            lastChapterLabel = null,
            lastProgressAt = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val tracker = mockk<Tracker>()
        every { tracker.getCompletionStatus() } returns 3L
        every { tracker.getReadingStatus() } returns 1L
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(manga.source, manga.url) } returns work.id
        coEvery { repository.getWork(work.id) } returns work
        coEvery { chapters.await(manga.id) } returns emptyList()

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(
            Track(
                id = 1L,
                mangaId = manga.id,
                trackerId = 10L,
                remoteId = 20L,
                libraryId = null,
                title = manga.title,
                lastChapterRead = 0.0,
                totalChapters = 0L,
                status = 3L,
                score = 0.0,
                remoteUrl = "https://tracker.example/manga",
                startDate = 0L,
                finishDate = 0L,
                private = false,
            ),
            tracker,
        )

        coVerify {
            repository.upsertWork(match { it.id == work.id && it.status == LocalTrackedWorkStatus.COMPLETED })
            repository.recordPendingProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = 0.0,
                chapterLabel = "Chapter 0.0",
                progressAt = any(),
            )
        }
    }

    @Test
    fun `unavailable chapter rows use the monotonic pending progress boundary`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>()
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoSyncLocalTrackingFromTrackers().set(true)
        }
        val manga = manga(1L, 1L, "/tracker")
        val work = LocalTrackedWork(
            id = "local-work",
            title = manga.title,
            normalizedTitle = manga.title.lowercase(),
            status = LocalTrackedWorkStatus.READING,
            lastChapterSource = manga.source,
            lastChapterNumber = 12.0,
            lastChapterUrl = "/tracker/ch-12",
            lastChapterLabel = "Chapter 12",
            lastProgressAt = 20L,
            createdAt = 1L,
            updatedAt = 20L,
        )
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(manga.source, manga.url) } returns work.id
        coEvery { repository.getWork(work.id) } returns work
        coEvery { chapters.await(manga.id) } returns emptyList()

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(track(manga, 3.0))

        coVerify {
            repository.recordPendingProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = 3.0,
                chapterLabel = "Chapter 3.0",
                progressAt = any(),
            )
        }
        coVerify(exactly = 0) { repository.upsertWork(match { it.lastChapterUrl == null }) }
    }

    @Test
    fun `refresh attaches confirmed tracker source to existing local work`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>()
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val chapterReadSync = mockk<RecordLocalTrackedChapterProgress>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoSyncLocalTrackingFromTrackers().set(true)
        }
        val manga = manga(1L, 1L, "/tracker")
        val sibling = manga(2L, 2L, "/local")
        val work = LocalTrackedWork(
            id = "local-work",
            title = sibling.title,
            normalizedTitle = sibling.title.lowercase(),
            status = LocalTrackedWorkStatus.READING,
            lastChapterSource = 2L,
            lastChapterNumber = 4.0,
            lastChapterUrl = "/local/ch-4",
            lastChapterLabel = "Chapter 4",
            lastProgressAt = 10L,
            createdAt = 1L,
            updatedAt = 10L,
        )
        val chapter = Chapter.create().copy(
            id = 11L,
            mangaId = manga.id,
            url = "/tracker/ch-7",
            name = "Chapter 7",
            chapterNumber = 7.0,
        )
        val originLink = CrossSourceMangaLink(1L, "/tracker", "group", manga.title, 1L, 1L)
        val siblingLink = CrossSourceMangaLink(2L, "/local", "group", sibling.title, 1L, 1L)

        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(1L, "/tracker") } returns null
        coEvery { repository.getWorkIdBySourceUrl(2L, "/local") } returns work.id
        coEvery { repository.getWork(work.id) } returns work
        coEvery { links.awaitBySourceUrl(1L, "/tracker") } returns originLink
        coEvery { links.awaitByGroupId("group") } returns listOf(originLink, siblingLink)
        coEvery { identityResolver.confirmedGroupMembers(1L, "/tracker", any()) } returns
            listOf(originLink, siblingLink)
        coEvery { chapters.await(manga.id) } returns listOf(chapter)

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            recordLocalTrackedChapterProgress = chapterReadSync,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(
            Track(
                id = 1L,
                mangaId = manga.id,
                trackerId = 10L,
                remoteId = 20L,
                libraryId = null,
                title = manga.title,
                lastChapterRead = 7.0,
                totalChapters = 7L,
                status = 1L,
                score = 0.0,
                remoteUrl = "https://tracker.example/manga",
                startDate = 0L,
                finishDate = 0L,
                private = false,
            ),
        )

        coVerify {
            repository.upsertSource(
                match {
                    it.workId == work.id &&
                        it.source == manga.source &&
                        it.url == manga.url &&
                        it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED
                },
            )
            repository.recordSourceProgress(
                match { it.workId == work.id && it.source == manga.source && it.chapterNumber == 7.0 },
            )
            chapterReadSync.synchronize()
        }
    }

    @Test
    fun `refresh maps tracker position when a source begins at a later chapter number`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>()
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val chapterReadSync = mockk<RecordLocalTrackedChapterProgress>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoSyncLocalTrackingFromTrackers().set(true)
        }
        val manga = manga(1L, 1L, "/tracker")
        val work = LocalTrackedWork(
            id = "local-work",
            title = manga.title,
            normalizedTitle = manga.title.lowercase(),
            status = LocalTrackedWorkStatus.READING,
            lastChapterSource = null,
            lastChapterNumber = null,
            lastChapterUrl = null,
            lastChapterLabel = null,
            lastProgressAt = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val sourceChapters = (8L..11L).map { number ->
            Chapter.create().copy(
                id = number,
                mangaId = manga.id,
                url = "/tracker/ch-$number",
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                sourceOrder = number,
            )
        }
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(manga.source, manga.url) } returns work.id
        coEvery { repository.getWork(work.id) } returns work
        coEvery { chapters.await(manga.id) } returns sourceChapters

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            recordLocalTrackedChapterProgress = chapterReadSync,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(
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

        coVerify {
            repository.recordProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = 10.0,
                chapterUrl = "/tracker/ch-10",
                chapterLabel = "Chapter 10",
                progressAt = any(),
            )
            repository.recordSourceProgress(
                match { it.workId == work.id && it.chapterNumber == 10.0 && it.chapterUrl == "/tracker/ch-10" },
            )
            chapterReadSync.synchronize()
        }
    }

    @Test
    fun `refresh keeps offset progress pending when reading-order matching is disabled`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>()
        val links = mockk<GetCrossSourceMangaLinks>(relaxed = true)
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.matchTrackerProgressByReadingOrder().set(false)
        }
        val manga = manga(1L, 1L, "/tracker")
        val work = LocalTrackedWork(
            id = "local-work",
            title = manga.title,
            normalizedTitle = manga.title.lowercase(),
            status = LocalTrackedWorkStatus.READING,
            lastChapterSource = null,
            lastChapterNumber = null,
            lastChapterUrl = null,
            lastChapterLabel = null,
            lastProgressAt = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(manga.source, manga.url) } returns work.id
        coEvery { repository.getWork(work.id) } returns work
        coEvery { chapters.await(manga.id) } returns (8L..11L).map { number ->
            Chapter.create().copy(
                id = number,
                mangaId = manga.id,
                url = "/tracker/ch-$number",
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                sourceOrder = number,
            )
        }

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(track(manga, 3.0))

        coVerify(exactly = 0) { repository.recordProgress(any(), any(), any(), any(), any(), any()) }
        coVerify {
            repository.recordPendingProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = 3.0,
                chapterLabel = "Chapter 3.0",
                progressAt = any(),
            )
        }
    }

    @Test
    fun `refresh does not attach a confirmed source when linked-version propagation is disabled`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapters = mockk<GetChaptersByMangaId>(relaxed = true)
        val links = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore())
        val sourcePreferences = SourcePreferences(FakePreferenceStore()).also {
            it.confirmedTrackedVersionLocalTrackingPropagationEnabled().set(false)
        }
        val manga = manga(1L, 1L, "/tracker")

        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        coEvery { repository.getWorkIdBySourceUrl(manga.source, manga.url) } returns null

        SyncLocalTrackingFromExternal(
            localTrackerRepository = repository,
            mangaRepository = mangaRepository,
            getChaptersByMangaId = chapters,
            trackPreferences = preferences,
            sourcePreferences = sourcePreferences,
            getCrossSourceMangaLinks = links,
            identityAuthorizationResolver = identityResolver,
        ).sync(track(manga, 5.0))

        coVerify(exactly = 0) { links.awaitBySourceUrl(any(), any()) }
        coVerify(exactly = 0) { repository.upsertSource(any()) }
        coVerify(exactly = 0) { repository.recordProgress(any(), any(), any(), any(), any(), any()) }
    }

    private fun manga(id: Long, source: Long, url: String) = Manga.create().copy(
        id = id,
        source = source,
        url = url,
        ogTitle = url,
    )

    private fun track(manga: Manga, lastChapterRead: Double) = Track(
        id = 1L,
        mangaId = manga.id,
        trackerId = 10L,
        remoteId = 20L,
        libraryId = null,
        title = manga.title,
        lastChapterRead = lastChapterRead,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "https://tracker.example/manga",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )
}
