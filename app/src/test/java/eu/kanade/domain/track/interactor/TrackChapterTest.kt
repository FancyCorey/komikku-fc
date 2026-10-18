package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class TrackChapterTest {

    @Test
    fun `remote progress already ahead of request is persisted without redundant upload`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val refreshedTrack = track.copy(lastChapterRead = 11.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns refreshedTrack.toDbTrack()
        coEvery { tracker.update(any(), true) } returns refreshedTrack.toDbTrack()
        coEvery { insertTrack.await(any()) } returns Unit

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify(exactly = 0) { tracker.update(any(), true) }
        coVerifyOrder {
            insertTrack.await(match { it.lastChapterRead == 11.0 })
            delayedStore.removeIfAtMost(track.id, 11.0)
        }
        coVerify(exactly = 0) { delayedStore.add(any(), any()) }
        coVerify(exactly = 0) { delayedStore.remove(any()) }
    }

    @Test
    fun `queued target already satisfied remotely is persisted and cleared without upload`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.copy(lastChapterRead = 9.0).toDbTrack()
        every { delayedStore.getTarget(track.id) } returns 8.0
        coEvery { insertTrack.await(any()) } returns Unit

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 6.0,
            setupJobOnFailure = false,
        )

        coVerify(exactly = 0) { tracker.update(any(), true) }
        coVerifyOrder {
            insertTrack.await(match { it.lastChapterRead == 9.0 })
            delayedStore.removeIfAtMost(track.id, 9.0)
        }
    }

    @Test
    fun `lower reader update leaves a higher pending target for conditional cleanup`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { tracker.update(any(), true) } returns track.copy(lastChapterRead = 8.0).toDbTrack()
        coEvery { insertTrack.await(any()) } returns Unit

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify { delayedStore.removeIfAtMost(track.id, 8.0) }
        coVerify(exactly = 0) { delayedStore.remove(any()) }
    }

    @Test
    fun `remote update failure retains delayed target for retry`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { tracker.update(any(), true) } throws IllegalStateException("offline")

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify { delayedStore.add(track.id, 8.0) }
        coVerify(exactly = 0) { insertTrack.await(any()) }
        coVerify(exactly = 0) { delayedStore.remove(any()) }
    }

    @Test
    fun `local insert failure after remote success does not create an upload retry`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { tracker.update(any(), true) } returns track.copy(lastChapterRead = 8.0).toDbTrack()
        coEvery { insertTrack.await(any()) } throws IllegalStateException("local insert failed")

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify { tracker.update(match { it.last_chapter_read == 8.0 }, true) }
        coVerify(exactly = 0) { delayedStore.add(any(), any()) }
        coVerify(exactly = 0) { delayedStore.remove(any()) }
        coVerify(exactly = 0) { delayedStore.removeIfAtMost(any(), any()) }
    }

    // KMK --> v0.8.24: reading one version updates the other versions bound to the same remote entry
    @Test
    fun `reading one version updates other versions bound to the same tracker entry`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val sibling = track(lastChapterRead = 4.0).copy(id = 2L, mangaId = 2L)
        val unrelated = track(lastChapterRead = 1.0).copy(id = 3L, mangaId = 3L, remoteId = 99L)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        coEvery { getTracks.awaitByRemoteId(10L, 20L) } returns listOf(track, sibling)
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { tracker.update(any(), true) } answers { firstArg() }
        coEvery { insertTrack.await(any()) } returns Unit

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify { insertTrack.await(match { it.id == 1L && it.lastChapterRead == 8.0 }) }
        coVerify { insertTrack.await(match { it.id == 2L && it.mangaId == 2L && it.lastChapterRead == 8.0 }) }
        coVerify(exactly = 0) { insertTrack.await(match { it.id == unrelated.id }) }
    }

    @Test
    fun `a failure updating linked versions never affects the main update`() = runTest {
        val track = track(lastChapterRead = 4.0)
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        coEvery { getTracks.await(1L) } returns listOf(track)
        coEvery { getTracks.awaitByRemoteId(any(), any()) } throws IllegalStateException("db")
        every { trackerManager.get(track.trackerId) } returns tracker
        every { tracker.isLoggedIn } returns true
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { tracker.update(any(), true) } answers { firstArg() }
        coEvery { insertTrack.await(any()) } returns Unit

        TrackChapter(getTracks, trackerManager, insertTrack, delayedStore).await(
            context = mockk<Context>(),
            mangaId = 1L,
            chapterNumber = 8.0,
            setupJobOnFailure = false,
        )

        coVerify { insertTrack.await(match { it.id == 1L && it.lastChapterRead == 8.0 }) }
        coVerify(exactly = 0) { delayedStore.add(any(), any()) }
    }
    // KMK <--

    private fun track(lastChapterRead: Double) = Track(
        id = 1L,
        mangaId = 1L,
        trackerId = 10L,
        remoteId = 20L,
        libraryId = null,
        title = "Manga",
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
