package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

class DelayedTrackingUpdateJobTest {

    @Test
    fun `satisfied queued target is removed without starting another tracker update`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val getTracks = mockk<GetTracks>()
        val trackChapter = mockk<TrackChapter>(relaxed = true)
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        every { delayedStore.getItems() } returns listOf(
            DelayedTrackingStore.DelayedTrackingItem(trackId = 1L, lastChapterRead = 8F),
        )
        coEvery { getTracks.awaitOne(1L) } returns track(lastChapterRead = 9.0)

        DelayedTrackingUpdateJob(context, mockk<WorkerParameters>(relaxed = true))
            .processQueuedItems(getTracks, trackChapter, delayedStore)

        coVerify { delayedStore.removeIfAtMost(1L, 9.0) }
        coVerify(exactly = 0) { trackChapter.await(any(), any(), any(), any()) }
    }

    @Test
    fun `target above persisted progress remains queued and goes through existing retry owner`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val getTracks = mockk<GetTracks>()
        val trackChapter = mockk<TrackChapter>(relaxed = true)
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)
        every { delayedStore.getItems() } returns listOf(
            DelayedTrackingStore.DelayedTrackingItem(trackId = 1L, lastChapterRead = 9F),
        )
        coEvery { getTracks.awaitOne(1L) } returns track(lastChapterRead = 7.0)

        DelayedTrackingUpdateJob(context, mockk<WorkerParameters>(relaxed = true))
            .processQueuedItems(getTracks, trackChapter, delayedStore)

        coVerify { trackChapter.await(context, 42L, 9.0, setupJobOnFailure = false) }
        coVerify(exactly = 0) { delayedStore.removeIfAtMost(any(), any()) }
    }

    private fun track(lastChapterRead: Double) = Track(
        id = 1L,
        mangaId = 42L,
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
