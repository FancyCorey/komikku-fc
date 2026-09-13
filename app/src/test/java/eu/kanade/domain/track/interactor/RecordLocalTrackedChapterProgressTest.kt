package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.model.SManga
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.AlternateSourceBridge
import tachiyomi.domain.taste.model.AlternateSourceBridgeKey
import tachiyomi.domain.taste.model.AlternateSourceBridgeMapping
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingKey
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingRelation
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingState
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import tachiyomi.domain.taste.repository.AlternateSourceBridgeRepository
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

class RecordLocalTrackedChapterProgressTest {

    @Test
    fun `refresh shares local tracker progress without a rating group and reads duplicate translations`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val savedWork = work("shared-work", "Shared").copy(
            lastChapterSource = 1L,
            lastChapterNumber = 4.1,
            lastChapterUrl = "/origin/ch-4.1",
            lastChapterLabel = "Chapter 4.1",
            lastProgressAt = 20L,
            startDate = 10L,
        )
        val sources = listOf(
            source(savedWork.id, 1L, "/origin", "Origin"),
            source(savedWork.id, 2L, "/target", "Target"),
        )
        val target = manga(2L, "/target")
        var chapters = listOf(
            chapter(2L, "/target/ch-1").copy(id = 21L, chapterNumber = 1.0),
            chapter(2L, "/target/ch-4.1-alpha").copy(id = 22L, chapterNumber = 4.1),
            chapter(2L, "/target/ch-4.1-gamma").copy(id = 23L, chapterNumber = 4.1),
            chapter(2L, "/target/ch-4.2").copy(id = 24L, chapterNumber = 4.2),
        )
        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(savedWork))
        coEvery { repository.getSources(savedWork.id) } returns sources
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns savedWork.id
        coEvery { repository.getWork(savedWork.id) } returns savedWork
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        coEvery { chapterRepository.getChapterByMangaId(2L) } answers { chapters }
        coEvery { chapterRepository.getChapterByUrlAndMangaId(any(), 2L) } answers {
            chapters.firstOrNull { it.url == firstArg<String>() }
        }
        coEvery { chapterRepository.updateAll(any()) } answers {
            val updates = firstArg<List<tachiyomi.domain.chapter.model.ChapterUpdate>>()
            chapters = chapters.map { chapter ->
                if (updates.any { it.id == chapter.id && it.read == true }) chapter.copy(read = true) else chapter
            }
        }
        val interactor = RecordLocalTrackedChapterProgress(
            repository,
            TrackPreferences(FakePreferenceStore()),
            getCrossSourceMangaLinks = mockk(relaxed = true),
            identityAuthorizationResolver = mockk(relaxed = true),
            alternateSourceBridgeRepository = mockk(relaxed = true),
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        )

        interactor.synchronize()
        assertEquals(listOf(21L, 22L, 23L), chapters.filter { it.read }.map { it.id })
        interactor.synchronize()
        coVerify(exactly = 1) { chapterRepository.updateAll(any()) }

        chapters = listOf(
            chapters.first().copy(read = false),
            chapters[1].copy(read = false, chapterNumber = 3.2),
            chapters.last(),
        )
        interactor.synchronize()
        assertEquals(listOf(21L, 22L), chapters.filter { it.read }.map { it.id })

        chapters = chapters.map { it.copy(read = false) }
        coEvery { repository.getSources(savedWork.id) } returns sources.map {
            if (it.source == 2L) it.copy(inheritanceOptedOut = true) else it
        }
        coEvery { repository.getSourceProgressForWork(savedWork.id) } returns listOf(
            LocalTrackedWorkSourceProgress(
                workId = savedWork.id,
                source = 2L,
                url = "/target",
                chapterNumber = 3.2,
                chapterUrl = chapters[1].url,
                chapterLabel = chapters[1].name,
                progressAt = 20L,
                inheritedFromSource = 1L,
                inheritedFromUrl = "/origin",
                updatedAt = 20L,
            ),
        )
        interactor.synchronize()
        assertEquals(emptyList<Long>(), chapters.filter { it.read }.map { it.id })
    }

    @Test
    fun `status and start date updates retain the newly read chapter in stored work`() = runTest {
        for (initialStatus in listOf(LocalTrackedWorkStatus.PLANNED, LocalTrackedWorkStatus.READING)) {
            val repository = mockk<LocalTrackerRepository>(relaxed = true)
            var storedWork = work("origin-work", "Origin").copy(
                status = initialStatus,
                lastChapterNumber = 1.0,
                startDate = null,
            )
            coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns storedWork.id
            coEvery { repository.getWork("origin-work") } answers { storedWork }
            coEvery { repository.upsertWork(any()) } answers { storedWork = firstArg() }
            coEvery { repository.recordProgress(any(), any(), any(), any(), any(), any()) } answers {
                storedWork = storedWork.copy(
                    lastChapterSource = arg(1),
                    lastChapterNumber = arg(2),
                    lastChapterUrl = arg(3),
                    lastChapterLabel = arg(4),
                    lastProgressAt = arg(5),
                    updatedAt = arg(5),
                )
            }
            val preferences = TrackPreferences(FakePreferenceStore()).also {
                it.autoInheritLocalProgress().set(false)
            }
            val interactor = RecordLocalTrackedChapterProgress(
                repository,
                preferences,
                getCrossSourceMangaLinks = mockk(relaxed = true),
                identityAuthorizationResolver = mockk(relaxed = true),
                alternateSourceBridgeRepository = mockk(relaxed = true),
                mangaRepository = mockk(relaxed = true),
                chapterRepository = mockk(relaxed = true),
            )
            val origin = manga(1L, "/origin")
            val fractionalChapter = chapter(1L, "/origin/ch-4.1").copy(
                name = "Chapter 4.1",
                chapterNumber = 4.1,
            )

            interactor.await(origin, fractionalChapter, 20L)

            assertEquals(4.1, storedWork.lastChapterNumber)
            assertEquals(fractionalChapter.url, storedWork.lastChapterUrl)
            assertEquals(20L, storedWork.lastProgressAt)
            assertEquals(20L, storedWork.startDate)
            assertEquals(LocalTrackedWorkStatus.READING, storedWork.status)

            interactor.await(origin, fractionalChapter.copy(chapterNumber = 5.2, url = "/origin/ch-5.2"), 21L)
            assertEquals(5.2, storedWork.lastChapterNumber)
            assertEquals(20L, storedWork.startDate)
        }
    }

    @Test
    fun `synchronize replays persisted progress to confirmed versions`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val originWork = work("origin-work", "Origin").copy(
            lastChapterSource = 1L,
            lastChapterNumber = 12.0,
            lastChapterUrl = "/origin/ch-12",
            lastChapterLabel = "Chapter 12",
            lastProgressAt = 20L,
        )
        val originSource = source("origin-work", 1L, "/origin", "Origin")
        val target = manga(2L, "/target")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val targetWork = work("target-work", "Target").copy(status = LocalTrackedWorkStatus.PLANNED)
        val targetSource = LocalTrackedWorkSource(
            workId = "target-work",
            source = 2L,
            url = "/target",
            title = "Target",
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val originProgress = LocalTrackedWorkSourceProgress(
            workId = "origin-work",
            source = 1L,
            url = "/origin",
            chapterNumber = 12.0,
            chapterUrl = "/origin/ch-12",
            chapterLabel = "Chapter 12",
            progressAt = 20L,
            updatedAt = 20L,
        )
        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(originWork))
        coEvery { repository.getSourceProgressForWork("origin-work") } returns listOf(originProgress)
        coEvery { repository.getSources("origin-work") } returns listOf(originSource)
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).synchronize()

        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "target-work" && it.chapterUrl == "/target/ch-13" &&
                        it.inheritedFromSource == 1L && it.inheritedFromUrl == "/origin"
                },
            )
        }
    }

    @Test
    fun `disabled preference records origin only`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val chapter = chapter(1L, "/origin/ch-12")
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, chapter, 20L)

        coVerify(exactly = 1) { repository.recordProgress("origin-work", 1L, 12.0, "/origin/ch-12", "Chapter 12", 20L) }
        coVerify(exactly = 1) { repository.recordSourceProgress(any()) }
        coVerify(exactly = 0) { repository.getWorkIdBySourceUrl(2L, any()) }
    }

    @Test
    fun `reading an unattached confirmed sibling records progress in its existing work`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sibling = manga(2L, "/sibling")
        val siblingChapter = chapter(2L, "/sibling/ch-12")
        val work = work("shared-work", "Shared")

        coEvery { repository.getWorkIdBySourceUrl(2L, "/sibling") } returns null
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "shared-work"
        coEvery { repository.getWork("shared-work") } returns work
        coEvery { linksInteractor.awaitBySourceUrl(2L, "/sibling") } returns
            CrossSourceMangaLink(2L, "/sibling", "group", "Sibling", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/sibling", "group", "Sibling", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(2L, "/sibling", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/sibling", "group", "Sibling", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/origin", 1L) } returns null

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(sibling, siblingChapter, 20L)

        coVerify {
            repository.upsertSource(
                match {
                    it.workId == "shared-work" && it.source == 2L && it.url == "/sibling" &&
                        it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED
                },
            )
        }
        coVerify {
            repository.recordProgress(
                "shared-work",
                2L,
                12.0,
                "/sibling/ch-12",
                "Chapter 12",
                20L,
            )
        }
    }

    @Test
    fun `progress resumes active local statuses while preserving completed metadata`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val chapter = chapter(1L, "/origin/ch-12")
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)

        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWork("origin-work") } returnsMany listOf(
            work("origin-work", "Origin").copy(
                status = LocalTrackedWorkStatus.PLANNED,
                finishDate = 10L,
            ),
            work("origin-work", "Origin").copy(status = LocalTrackedWorkStatus.COMPLETED),
        )

        val interactor = RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        )

        interactor.await(origin, chapter, 20L)
        interactor.await(origin, chapter, 21L)

        coVerify {
            repository.upsertWork(
                match {
                    it.status == LocalTrackedWorkStatus.READING && it.startDate == 20L && it.finishDate == null
                },
            )
        }
        coVerify(exactly = 1) {
            repository.upsertWork(match { it.status == LocalTrackedWorkStatus.COMPLETED })
        }
    }

    @Test
    fun `reading the semantic final chapter of completed manga completes local tracking`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val origin = manga(1L, "/origin").copy(ogStatus = SManga.COMPLETED.toLong())
        val chapter8 = chapter(1L, "/origin/ch-8").copy(name = "Chapter 8", chapterNumber = 8.0)
        val chapter10 = chapter(1L, "/origin/ch-10").copy(name = "Chapter 10", chapterNumber = 10.0)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(false)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)

        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { chapterRepository.getChapterByMangaId(1L) } returns listOf(chapter8, chapter10)

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, chapter10, 20L)

        coVerify {
            repository.upsertWork(
                match {
                    it.status == LocalTrackedWorkStatus.COMPLETED && it.finishDate == 20L &&
                        it.startDate == 20L
                },
            )
        }
    }

    @Test
    fun `progress policy clears stale finish date when inherited work resumes`() {
        val target = work("target-work", "Target").copy(
            status = LocalTrackedWorkStatus.ON_HOLD,
            finishDate = 10L,
        )

        val updated = tachiyomi.domain.tracker.model.LocalTrackedProgressInheritancePolicy.apply(
            target = target,
            update = tachiyomi.domain.tracker.model.MappedLocalTrackedProgress(
                targetSource = 2L,
                targetUrl = "/target",
                targetChapterUrl = "/target/ch-13",
                targetLabel = "Chapter 13",
                chapterNumber = 13.0,
                progressAt = 20L,
            ),
        )

        assertEquals(LocalTrackedWorkStatus.READING, updated?.status)
        assertEquals(null, updated?.finishDate)
    }

    @Test
    fun `stale same chapter progress does not advance aggregate local state`() {
        val target = work("target-work", "Target").copy(
            status = LocalTrackedWorkStatus.ON_HOLD,
            lastChapterNumber = 12.0,
            lastProgressAt = 30L,
        )

        assertEquals(
            false,
            tachiyomi.domain.tracker.model.LocalTrackedProgressInheritancePolicy.acceptsAggregateProgress(
                target = target,
                chapterNumber = 12.0,
                progressAt = 20L,
            ),
        )
    }

    @Test
    fun `enabled confirmed mapped target receives target chapter identity and provenance`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val targetWork = work("target-work", "Target")
        val targetSource = LocalTrackedWorkSource(
            workId = "target-work",
            source = 2L,
            url = "/target",
            title = "Target",
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitBySourceUrl(2L, "/target") } returns null
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify {
            repository.recordSourceProgress(
                LocalTrackedWorkSourceProgress(
                    workId = "target-work",
                    source = 2L,
                    url = "/target",
                    chapterNumber = 13.0,
                    chapterUrl = "/target/ch-13",
                    chapterLabel = "Chapter 13",
                    progressAt = 20L,
                    inheritedFromSource = 1L,
                    inheritedFromUrl = "/origin",
                    updatedAt = 20L,
                ),
            )
        }
        coVerify {
            repository.upsertWork(
                match {
                    it.id == "target-work" && it.lastChapterUrl == "/target/ch-13" && it.lastChapterNumber == 13.0 &&
                        it.status == LocalTrackedWorkStatus.READING && it.startDate == 20L
                },
            )
        }
        assertEquals("target-work", targetWork.id)
    }

    @Test
    fun `missing confirmed mapped target chapter does not fall back to numeric matching`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val numericFallbackChapter = chapter(2L, "/target/ch-12")
        val targetWork = work("target-work", "Target")
        val targetSource = source("target-work", 2L, "/target", "Target")

        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns null
        coEvery { chapterRepository.getChapterByMangaId(2L) } returns listOf(numericFallbackChapter)

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify(exactly = 1) { repository.recordSourceProgress(match { it.source == 1L }) }
        coVerify(exactly = 0) { repository.recordSourceProgress(match { it.source == 2L }) }
        coVerify(exactly = 0) { repository.upsertWork(match { it.id == "target-work" }) }
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `enabled confirmed mapped target creates missing local work`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val originWork = work("origin-work", "Origin")
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWork("origin-work") } returns originWork
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns null
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify {
            repository.upsertSource(
                match {
                    it.source == 2L && it.url == "/target" &&
                        it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED
                },
            )
        }
        coVerify(exactly = 2) {
            repository.upsertWork(match { it.title == target.title })
        }
        coVerify {
            repository.recordSourceProgress(
                match {
                    it.source == 2L && it.url == "/target" &&
                        it.chapterNumber == 13.0 && it.inheritedFromSource == 1L
                },
            )
        }
    }

    @Test
    fun `same work-level chapter still fills missing target source progress`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val originWork = work("origin-work", "Origin")
        val targetWork = work("target-work", "Target").copy(
            status = LocalTrackedWorkStatus.PLANNED,
            lastChapterNumber = 13.0,
            finishDate = 10L,
        )
        val targetSource = LocalTrackedWorkSource(
            workId = "target-work",
            source = 2L,
            url = "/target",
            title = "Target",
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("origin-work") } returns originWork
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { repository.getSourceProgress("target-work", 2L, "/target") } returns null
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "target-work" && it.source == 2L &&
                        it.url == "/target" && it.chapterUrl == "/target/ch-13" &&
                        it.inheritedFromSource == 1L
                },
            )
        }
        coVerify {
            repository.upsertWork(
                match {
                    it.id == "target-work" && it.status == LocalTrackedWorkStatus.READING &&
                        it.startDate == 20L && it.finishDate == null
                },
            )
        }
    }

    @Test
    fun `synchronize replays newer aggregate progress when source row is stale`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originWork = work("origin-work", "Origin").copy(
            lastChapterSource = 1L,
            lastChapterNumber = 37.0,
            lastChapterUrl = "/origin/ch-37",
            lastChapterLabel = "Chapter 37",
            lastProgressAt = 40L,
        )
        val targetWork = work("target-work", "Target").copy(
            lastChapterSource = 2L,
            lastChapterNumber = 37.0,
            lastChapterUrl = "/target/ch-37",
            lastChapterLabel = "Chapter 37",
            lastProgressAt = 40L,
        )
        val originSource = source("origin-work", 1L, "/origin", "Origin")
        val targetSource = source("target-work", 2L, "/target", "Target")
        val staleOriginProgress = sourceProgress("origin-work", 1L, "/origin", 36.0, "/origin/ch-36", 30L)
        val staleTargetProgress = sourceProgress("target-work", 2L, "/target", 36.0, "/target/ch-36", 30L)
        val targetChapter = chapter(2L, "/target/ch-37").copy(name = "Chapter 37", chapterNumber = 37.0)

        coEvery { repository.getAllWorksAsFlow() } returns flowOf(listOf(originWork, targetWork))
        coEvery { repository.getSourceProgressForWork("origin-work") } returns listOf(staleOriginProgress)
        coEvery { repository.getSourceProgressForWork("target-work") } returns listOf(staleTargetProgress)
        coEvery { repository.getSources("origin-work") } returns listOf(originSource)
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSourceProgress("target-work", 2L, "/target") } returns staleTargetProgress
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitBySourceUrl(2L, "/target") } returns null
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns emptyList()
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByMangaId(2L) } returns listOf(targetChapter)

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).synchronize()

        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "origin-work" && it.source == 1L && it.chapterNumber == 37.0 &&
                        it.chapterUrl == "/origin/ch-37" && it.progressAt == 40L
                },
            )
        }
        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "target-work" && it.source == 2L && it.chapterNumber == 37.0 &&
                        it.chapterUrl == "/target/ch-37" && it.inheritedFromSource == 1L
                },
            )
        }
    }

    @Test
    fun `confirmed version is reattached to an existing local work`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val originWork = work("origin-work", "Origin")
        val targetWork = work("target-work", "Target")
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("origin-work") } returns originWork
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns emptyList()
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify {
            repository.upsertSource(
                match {
                    it.workId == "target-work" && it.source == 2L &&
                        it.url == "/target" &&
                        it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED
                },
            )
        }
        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "target-work" && it.source == 2L &&
                        it.chapterNumber == 13.0 && it.inheritedFromSource == 1L
                },
            )
        }
    }

    @Test
    fun `enabled propagation respects target opt out`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-13").copy(name = "Chapter 13", chapterNumber = 13.0)
        val targetWork = work("target-work", "Target")
        val targetSource = LocalTrackedWorkSource(
            workId = "target-work",
            source = 2L,
            url = "/target",
            title = "Target",
            confidence = 100,
            confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
            inheritanceOptedOut = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { repository.getWork("target-work") } returns targetWork
        coEvery { repository.getSources("target-work") } returns listOf(targetSource)
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns listOf(mapping())
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByUrlAndMangaId("/target/ch-13", 2L) } returns targetChapter

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify(exactly = 1) { repository.recordSourceProgress(match { it.source == 1L }) }
        coVerify(exactly = 0) { repository.upsertWork(match { it.id == "target-work" }) }
        coVerify(exactly = 0) { repository.upsertSourceProgress(match { it.source == 2L }) }
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `unconfirmed group member is excluded before target lookup`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val originChapter = chapter(1L, "/origin/ch-12")
        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
        )

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify(exactly = 1) { repository.recordSourceProgress(match { it.source == 1L }) }
        coVerify(exactly = 0) { repository.getWorkIdBySourceUrl(2L, any()) }
        coVerify(exactly = 0) { repository.upsertWork(match { it.id == "target-work" }) }
    }

    @Test
    fun `confirmed group exact chapter number falls back when mapping is not confirmed`() = runTest {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.autoInheritLocalProgress().set(true)
        }
        val linksInteractor = mockk<GetCrossSourceMangaLinks>()
        val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        val bridgeRepository = mockk<AlternateSourceBridgeRepository>()
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/target")
        val originChapter = chapter(1L, "/origin/ch-12")
        val targetChapter = chapter(2L, "/target/ch-12")

        coEvery { repository.getWorkIdBySourceUrl(1L, "/origin") } returns "origin-work"
        coEvery { repository.getWork("origin-work") } returns work("origin-work", "Origin")
        coEvery { repository.getWorkIdBySourceUrl(2L, "/target") } returns "target-work"
        coEvery { repository.getWork("target-work") } returns work("target-work", "Target")
        coEvery { linksInteractor.awaitBySourceUrl(1L, "/origin") } returns
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L)
        coEvery { linksInteractor.awaitByGroupId("group") } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { identityResolver.confirmedGroupMembers(1L, "/origin", any()) } returns listOf(
            CrossSourceMangaLink(1L, "/origin", "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, "/target", "group", "Target", 1L, 1L),
        )
        coEvery { bridgeRepository.getAllBridges() } returns listOf(bridge())
        coEvery { bridgeRepository.getAllMappings() } returns emptyList()
        coEvery { mangaRepository.getMangaByUrlAndSourceId("/target", 2L) } returns target
        coEvery { chapterRepository.getChapterByMangaId(2L) } returns listOf(targetChapter)

        RecordLocalTrackedChapterProgress(
            repository,
            preferences,
            getCrossSourceMangaLinks = linksInteractor,
            identityAuthorizationResolver = identityResolver,
            alternateSourceBridgeRepository = bridgeRepository,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
        ).await(origin, originChapter, 20L)

        coVerify {
            repository.recordSourceProgress(
                match {
                    it.workId == "target-work" && it.source == 2L &&
                        it.chapterUrl == "/target/ch-12" && it.chapterNumber == 12.0 &&
                        it.inheritedFromSource == 1L
                },
            )
        }
    }

    private fun manga(source: Long, url: String) = Manga.create().copy(id = source, source = source, url = url, ogTitle = url)

    private fun source(workId: String, source: Long, url: String, title: String) = LocalTrackedWorkSource(
        workId = workId,
        source = source,
        url = url,
        title = title,
        confidence = 100,
        confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun sourceProgress(
        workId: String,
        source: Long,
        url: String,
        chapterNumber: Double,
        chapterUrl: String,
        progressAt: Long,
    ) = LocalTrackedWorkSourceProgress(
        workId = workId,
        source = source,
        url = url,
        chapterNumber = chapterNumber,
        chapterUrl = chapterUrl,
        chapterLabel = "Chapter ${chapterNumber.toInt()}",
        progressAt = progressAt,
        updatedAt = progressAt,
    )

    private fun chapter(mangaId: Long, url: String) = Chapter.create().copy(
        id = mangaId,
        mangaId = mangaId,
        url = url,
        name = "Chapter 12",
        chapterNumber = 12.0,
    )

    private fun work(id: String, title: String) = LocalTrackedWork(
        id = id,
        title = title,
        normalizedTitle = title.lowercase(),
        status = LocalTrackedWorkStatus.READING,
        lastChapterSource = null,
        lastChapterNumber = null,
        lastChapterUrl = null,
        lastChapterLabel = null,
        lastProgressAt = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun bridge() = AlternateSourceBridge(
        key = AlternateSourceBridgeKey(
            CrossSourceRecordKey(1L, "/origin"),
            CrossSourceRecordKey(2L, "/target"),
        ),
        version = 2,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun mapping() = AlternateSourceBridgeMapping(
        key = AlternateSourceBridgeMappingKey(bridge().key, "00000000-0000-4000-8000-000000000001"),
        primaryChapterUrl = "/origin/ch-12",
        alternateChapterUrl = "/target/ch-13",
        relation = AlternateSourceBridgeMappingRelation.OFFSET,
        state = AlternateSourceBridgeMappingState.CONFIRMED,
        offsetMilli = 1L,
        version = 2,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
