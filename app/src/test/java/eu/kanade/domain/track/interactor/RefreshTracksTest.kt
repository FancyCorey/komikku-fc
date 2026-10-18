package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class RefreshTracksTest {

    @Test
    fun `refresh isolates tracker failures and reconciles local progress once after the batch`() = runTest {
        val successfulTracker = mockk<BaseTracker>()
        val failingTracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val syncProgress = mockk<SyncChapterProgressWithTrack>(relaxed = true)
        val localSync = mockk<SyncLocalTrackingFromExternal>(relaxed = true)
        val successfulTrack = domainTrack(trackerId = 10L)
        val failingTrack = domainTrack(trackerId = 20L)

        every { successfulTracker.id } returns 10L
        every { successfulTracker.isLoggedIn } returns true
        every { failingTracker.id } returns 20L
        every { failingTracker.isLoggedIn } returns true
        every { trackerManager.get(10L) } returns successfulTracker
        every { trackerManager.get(20L) } returns failingTracker
        coEvery { getTracks.await(42L) } returns listOf(successfulTrack, failingTrack)
        coEvery { successfulTracker.refresh(any()) } returns successfulTrack.toDbTrack()
        coEvery { failingTracker.refresh(any()) } throws IllegalStateException("offline")
        coEvery { syncProgress.sync(42L, successfulTrack, successfulTracker) } returns null
        coEvery { localSync.sync(successfulTrack, successfulTracker) } throws IllegalStateException("local sync failed")

        val failures = RefreshTracks(getTracks, trackerManager, insertTrack, syncProgress, localSync).await(
            mangaId = 42L,
            enhancedTrackersOnly = false,
        )

        assertEquals(
            listOf(20L),
            failures.map { it.first?.id },
            failures.joinToString { it.second.toString() },
        )
        assertSame(failingTracker, failures.single().first)
        coVerify(exactly = 1) { insertTrack.await(successfulTrack) }
        coVerify(exactly = 0) { insertTrack.await(failingTrack) }
        coVerify(exactly = 1) { syncProgress.sync(42L, successfulTrack, successfulTracker) }
        coVerify(exactly = 1) { localSync.sync(successfulTrack, successfulTracker) }
        coVerify(exactly = 1) { localSync.synchronize() }
    }

    // KMK --> v0.8.24: manual pull with the sync-from-trackers setting off.
    @Test
    fun `upload-only refresh refreshes the remote record and uploads without remote-to-local steps`() = runTest {
        val tracker = mockk<BaseTracker>()
        val trackerManager = mockk<TrackerManager>()
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val syncProgress = mockk<SyncChapterProgressWithTrack>(relaxed = true)
        val localSync = mockk<SyncLocalTrackingFromExternal>(relaxed = true)
        val track = domainTrack(trackerId = 10L)
        every { tracker.id } returns 10L
        every { tracker.isLoggedIn } returns true
        every { trackerManager.get(10L) } returns tracker
        coEvery { getTracks.await(42L) } returns listOf(track)
        coEvery { tracker.refresh(any()) } returns track.toDbTrack()
        coEvery { syncProgress.sync(42L, track, tracker, true, false) } returns null

        val failures = RefreshTracks(getTracks, trackerManager, insertTrack, syncProgress, localSync).await(
            mangaId = 42L,
            enhancedTrackersOnly = false,
            uploadOnly = true,
        )

        assertEquals(emptyList<Long>(), failures.map { it.first?.id })
        coVerify(exactly = 1) { insertTrack.await(track) }
        coVerify(exactly = 1) { syncProgress.sync(42L, track, tracker, true, false) }
        coVerify(exactly = 0) { syncProgress.sync(42L, track, tracker, true, true) }
        coVerify(exactly = 0) { syncProgress.await(any(), any(), any()) }
        coVerify(exactly = 0) { localSync.sync(any(), any()) }
    }
    // KMK <--

    private fun domainTrack(trackerId: Long) = Track(
        id = trackerId,
        mangaId = 42L,
        trackerId = trackerId,
        remoteId = trackerId + 100L,
        libraryId = null,
        title = "Tracked title",
        lastChapterRead = 4.0,
        totalChapters = 12L,
        status = 1L,
        score = 0.0,
        remoteUrl = "https://example.test/$trackerId",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )
}
