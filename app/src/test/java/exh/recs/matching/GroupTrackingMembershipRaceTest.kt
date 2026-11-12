package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.RecordLocalTrackedChapterProgress
import eu.kanade.domain.track.service.TrackPreferences
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupTrackingMembershipRaceTest {
    private class Fixture {
        val repository = mockk<LocalTrackerRepository>(relaxed = true)
        val links = mockk<GetCrossSourceMangaLinks>()
        val identity = mockk<CrossSourceIdentityAuthorizationResolver>()
        val origin = Manga.create().copy(id = 1L, source = 1L, url = "/origin", ogTitle = "Origin")
        val target = Manga.create().copy(id = 2L, source = 2L, url = "/target", ogTitle = "Target")
        val chapter = Chapter.create().copy(id = 22L, mangaId = 2L, url = "/target/4", name = "4", chapterNumber = 4.0)
        var members = listOf(
            CrossSourceMangaLink(1L, origin.url, "group", "Origin", 1L, 1L),
            CrossSourceMangaLink(2L, target.url, "group", "Target", 1L, 1L),
        )
        var targetWork = "shared"
        val recorded = mutableListOf<LocalTrackedWorkSourceProgress>()
        val store = FakePreferenceStore()
        val trackPreferences = TrackPreferences(store)
        val work = LocalTrackedWork(
            id = "shared", title = "Origin", normalizedTitle = "origin", status = LocalTrackedWorkStatus.READING,
            lastChapterSource = null, lastChapterNumber = null, lastChapterUrl = null,
            lastChapterLabel = null, lastProgressAt = null, createdAt = 1L, updatedAt = 1L,
        )
        val lifecycle = GroupTrackingLifecycle(links, mockk(relaxed = true), lazy { mockk() }, lazy { repository })
        val propagator = ConfirmedGroupLocalTrackingPropagator(
            repository, SourcePreferences(store), trackPreferences,
            getHistoryOverride = mockk(relaxed = true), getChapterOverride = mockk(relaxed = true),
            getChaptersByMangaIdOverride = mockk(relaxed = true), ratingActionsOverride = mockk(relaxed = true),
            getLinksOverride = links, identityResolverOverride = identity,
        )
        val recorder = RecordLocalTrackedChapterProgress(
            repository,
            trackPreferences,
            links,
            identity,
            alternateSourceBridgeRepository = mockk(relaxed = true),
            mangaRepository = mockk<tachiyomi.domain.manga.repository.MangaRepository>(relaxed = true).also { mangaRepository ->
                coEvery { mangaRepository.getMangaByUrlAndSourceId(any(), any()) } returns null
            },
            chapterRepository = mockk(relaxed = true),
        )

        init {
            trackPreferences.autoCreateLocalTrackingFromRating().set(true)
            coEvery { links.awaitBySourceUrl(any(), any()) } answers {
                members.find { it.source == firstArg<Long>() && it.url == secondArg<String>() }
            }
            coEvery { links.awaitByGroupId(any()) } answers { members }
            // Independent user confirmations survive removal. Only current links delimit membership.
            coEvery { identity.confirmedGroupMembers(any(), any(), any()) } answers { thirdArg() }
            coEvery { identity.isRejected(any(), any(), any(), any()) } returns false
            coEvery { repository.getWorkIdBySourceUrl(1L, origin.url) } returns "shared"
            coEvery { repository.getWorkIdBySourceUrl(2L, target.url) } answers { targetWork }
            coEvery { repository.getWork(any()) } answers { work.copy(id = firstArg()) }
            coEvery { repository.recordSourceProgress(any()) } answers { recorded += firstArg<LocalTrackedWorkSourceProgress>() }
            coEvery { repository.getAllWorksAsFlow() } returns flow {
                emit(listOf(work, work.copy(id = targetWork)).distinctBy { it.id })
            }
        }

        suspend fun splitAndUnlink() = GroupTrackingMembershipCoordinator.withMembershipLock {
            lifecycle.separate(setOf(target.source to target.url))
            targetWork = "separated"
            members = members.filterNot { it.source == target.source }
        }
    }

    @Test
    fun `stale automatic targets wait for suspended removal and cannot reconsolidate retained confirmations`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.repository.separateSources(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val removing = launch { f.splitAndUnlink() }
        entered.await()
        val automatic = async { f.propagator.propagateIfAnyTracked(listOf(f.origin, f.target), enabled = true) }
        runCurrent()
        assertFalse(automatic.isCompleted)
        coVerify(exactly = 0) { f.repository.getWorkIdBySourceUrl(any(), any()) }
        release.complete(Unit)
        removing.join()
        assertTrue(automatic.await())
        coVerify(exactly = 0) { f.repository.consolidateWork(any(), any()) }
        coVerify(exactly = 0) { f.repository.upsertSource(any()) }
        assertEquals("separated", f.targetWork)
    }

    @Test
    fun `stale rating create targets cannot attach removed source`() = runTest {
        val f = Fixture()
        coEvery { f.repository.getWorkIdBySourceUrl(any(), any()) } returns null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.repository.separateSources(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val removing = launch { f.splitAndUnlink() }
        entered.await()
        val creating = async { f.propagator.ensureTrackedForRating(listOf(f.origin, f.target)) }
        runCurrent()
        assertFalse(creating.isCompleted)
        release.complete(Unit)
        removing.join()
        assertTrue(creating.await())
        coVerify(exactly = 1) { f.repository.upsertSource(match { it.source == f.origin.source }) }
        coVerify(exactly = 0) { f.repository.upsertSource(match { it.source == f.target.source }) }
    }

    @Test
    fun `reading queued behind split records into new work without reattaching`() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.repository.separateSources(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val removing = launch { f.splitAndUnlink() }
        entered.await()
        val reading = launch { f.recorder.await(f.target, f.chapter, 42L) }
        runCurrent()
        assertFalse(reading.isCompleted)
        release.complete(Unit)
        removing.join()
        reading.join()
        assertEquals(listOf("separated"), f.recorded.map { it.workId })
        coVerify(exactly = 1) { f.repository.recordProgress("separated", 2L, 4.0, "/target/4", "4", 42L) }
        coVerify(exactly = 0) { f.repository.upsertSource(any()) }
    }

    @Test
    fun `split waits for reading after lookup so source progress is available to atomic separation`() = runTest {
        val f = Fixture()
        val lookedUp = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.repository.getWork("shared") } coAnswers {
            lookedUp.complete(Unit)
            release.await()
            f.work
        }
        coEvery { f.repository.separateSources(any(), any()) } coAnswers {
            assertEquals(listOf(4.0), f.recorded.map { it.chapterNumber })
            // Models the data API copying current source rows, not an app-owned snapshot.
            f.recorded.replaceAll { it.copy(workId = "separated") }
        }
        val reading = launch { f.recorder.await(f.target, f.chapter, 42L) }
        lookedUp.await()
        val removing = launch { f.splitAndUnlink() }
        runCurrent()
        coVerify(exactly = 0) { f.repository.separateSources(any(), any()) }
        release.complete(Unit)
        reading.join()
        removing.join()
        assertEquals(listOf("separated"), f.recorded.map { it.workId })
    }

    @Test
    fun `scoped and global replay resolve fresh works after suspended split`() = runTest {
        for (scoped in listOf(true, false)) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.separateSources(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            val removing = launch { f.splitAndUnlink() }
            entered.await()
            val replay = launch {
                if (scoped) f.recorder.synchronizeFor(f.target) else f.recorder.synchronize()
            }
            runCurrent()
            assertFalse(replay.isCompleted)
            coVerify(exactly = 0) { f.repository.getSourceProgressForWork(any()) }
            release.complete(Unit)
            removing.join()
            replay.join()
            coVerify(exactly = 1) { f.repository.getSourceProgressForWork("separated") }
            if (scoped) coVerify(exactly = 0) { f.repository.getSourceProgressForWork("shared") }
            coVerify(exactly = 0) { f.repository.upsertSource(any()) }
        }
    }

    @Test
    fun `split waits for an in flight consolidation then separates its result`() = runTest {
        val f = Fixture()
        f.targetWork = "other"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.repository.getWork("other") } returns f.work.copy(id = "other", createdAt = 2L)
        coEvery { f.repository.consolidateWork("shared", "other") } coAnswers {
            entered.complete(Unit)
            release.await()
            f.targetWork = "shared"
        }
        coEvery { f.repository.separateSources(any(), any()) } coAnswers { assertEquals("shared", f.targetWork) }
        val automatic = async { f.propagator.propagateIfAnyTracked(listOf(f.origin, f.target), enabled = true) }
        entered.await()
        val removing = launch { f.splitAndUnlink() }
        runCurrent()
        coVerify(exactly = 0) { f.repository.separateSources(any(), any()) }
        release.complete(Unit)
        assertTrue(automatic.await())
        removing.join()
        assertEquals("separated", f.targetWork)
        assertEquals(listOf(1L), f.members.map { it.source })
    }

    @Test
    fun `failed split leaves links and releases coordinator for subsequent reading`() = runTest {
        val f = Fixture()
        coEvery { f.repository.separateSources(any(), any()) } throws IllegalStateException("disk")
        val failure = runCatching { f.splitAndUnlink() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(2, f.members.size)
        f.recorder.await(f.target, f.chapter, 42L)
        assertEquals(listOf("shared"), f.recorded.map { it.workId })
    }
}
