package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class SyncChapterProgressWithTrackTest {

    @Test
    fun `failed refresh upload queues local high water and preserves local state`() = runTest {
        val updateChapter = mockk<UpdateChapter>(relaxed = true)
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val getChapters = mockk<GetChaptersByMangaId>()
        val tracker = mockk<BaseTracker>(moreInterfaces = arrayOf(EnhancedTracker::class))
        val queued = mutableListOf<Pair<Long, Double>>()
        val chapters = (1L..9L).map { number ->
            Chapter.create().copy(
                id = number,
                mangaId = 1L,
                url = "/chapter/$number",
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                sourceOrder = 10L - number,
                read = true,
            )
        }
        val nextChapter = Chapter.create().copy(
            id = 10L,
            mangaId = 1L,
            url = "/chapter/10",
            name = "Chapter 10",
            chapterNumber = 10.0,
            sourceOrder = 0L,
            read = true,
        )
        coEvery { getChapters.await(1L) } returnsMany listOf(chapters, chapters + nextChapter)
        coEvery { tracker.update(any()) } throws IllegalStateException("offline")

        val interactor = SyncChapterProgressWithTrack(
            updateChapter,
            insertTrack,
            getChapters,
            TrackPreferences(FakePreferenceStore()),
            enqueueUploadRetry = { id, progress -> queued += id to progress },
        )
        val result = interactor.sync(1L, track(lastChapterRead = 3.0), tracker)
        interactor.sync(1L, track(lastChapterRead = 3.0), tracker)

        assertNull(result)
        assertEquals(listOf(1L to 9.0, 1L to 10.0), queued)
        coVerify(exactly = 0) { insertTrack.await(any()) }
        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
    }

    @Test
    fun `cancellation during refresh upload propagates without queueing`() = runTest {
        val tracker = mockk<Tracker>()
        val queued = mutableListOf<Pair<Long, Double>>()
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1L) } returns listOf(
            Chapter.create().copy(
                id = 4L,
                mangaId = 1L,
                url = "/chapter/4",
                name = "Chapter 4",
                chapterNumber = 4.0,
                sourceOrder = 1L,
                read = true,
            ),
        )
        coEvery { tracker.update(any()) } throws kotlinx.coroutines.CancellationException("cancelled")

        val interactor = SyncChapterProgressWithTrack(
            mockk(relaxed = true),
            mockk(relaxed = true),
            getChapters,
            TrackPreferences(FakePreferenceStore()),
            enqueueUploadRetry = { id, progress -> queued += id to progress },
        )

        var cancellation: kotlinx.coroutines.CancellationException? = null
        try {
            interactor.sync(1L, track(lastChapterRead = 3.0), tracker)
        } catch (error: kotlinx.coroutines.CancellationException) {
            cancellation = error
        }
        org.junit.jupiter.api.Assertions.assertNotNull(cancellation)
        assertEquals(emptyList<Pair<Long, Double>>(), queued)
    }

    @Test
    fun `external progress marks a consecutive source that begins above chapter one`() = runTest {
        val updateChapter = mockk<UpdateChapter>(relaxed = true)
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val getChapters = mockk<GetChaptersByMangaId>()
        val tracker = mockk<Tracker>(relaxed = true)
        every { tracker.hasNotStartedReading(any()) } returns false
        val chapters = (8L..11L).map { number ->
            Chapter.create().copy(
                id = number,
                mangaId = 1L,
                url = "/chapter/$number",
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                sourceOrder = 12L - number,
            )
        }
        coEvery { getChapters.await(1L) } returns chapters

        SyncChapterProgressWithTrack(
            updateChapter,
            insertTrack,
            getChapters,
            TrackPreferences(FakePreferenceStore()),
        ).sync(
            mangaId = 1L,
            remoteTrack = track(lastChapterRead = 3.0),
            tracker = tracker,
        )

        coVerify {
            updateChapter.awaitAll(
                match { updates -> updates.map { it.id } == listOf(8L, 9L, 10L) && updates.all { it.read == true } },
            )
        }
    }

    @Test
    fun `disabled reading-order matching leaves offset chapters unread`() = runTest {
        val updateChapter = mockk<UpdateChapter>(relaxed = true)
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val getChapters = mockk<GetChaptersByMangaId>()
        val tracker = mockk<Tracker>(relaxed = true)
        val preferences = TrackPreferences(FakePreferenceStore()).also {
            it.matchTrackerProgressByReadingOrder().set(false)
        }
        every { tracker.hasNotStartedReading(any()) } returns false
        coEvery { getChapters.await(1L) } returns (8L..11L).map { number ->
            Chapter.create().copy(
                id = number,
                mangaId = 1L,
                url = "/chapter/$number",
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                sourceOrder = 12L - number,
            )
        }

        SyncChapterProgressWithTrack(updateChapter, insertTrack, getChapters, preferences).sync(
            mangaId = 1L,
            remoteTrack = track(lastChapterRead = 3.0),
            tracker = tracker,
        )

        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
    }

    // KMK --> v0.8.24: a manual pull uploads newer local progress even when the sync-from-trackers setting is off.
    private fun numberedChapters(count: Int, readThrough: Int) = (1..count).map { number ->
        Chapter.create().copy(
            id = number.toLong(),
            mangaId = 1L,
            url = "/chapter/$number",
            name = "Chapter $number",
            chapterNumber = number.toDouble(),
            sourceOrder = (count - number).toLong(),
            read = number <= readThrough,
        )
    }

    @Test
    fun `upload-only sync uploads newer local progress without marking chapters from the remote record`() = runTest {
        val updateChapter = mockk<UpdateChapter>(relaxed = true)
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val getChapters = mockk<GetChaptersByMangaId>()
        val tracker = mockk<Tracker>(relaxed = true)
        every { tracker.hasNotStartedReading(any()) } returns false
        coEvery { getChapters.await(1L) } returns numberedChapters(count = 9, readThrough = 9)

        val result = SyncChapterProgressWithTrack(
            updateChapter,
            insertTrack,
            getChapters,
            TrackPreferences(FakePreferenceStore()),
        ).sync(1L, track(lastChapterRead = 3.0), tracker, applyRemoteToLocal = false)

        assertNull(result)
        coVerify(exactly = 1) { tracker.update(match { it.last_chapter_read == 9.0 }) }
        coVerify(exactly = 1) { insertTrack.await(match { it.lastChapterRead == 9.0 }) }
        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
    }

    @Test
    fun `upload-only sync never regresses a remote that is ahead and leaves local chapters alone`() = runTest {
        suspend fun run(applyRemoteToLocal: Boolean): Pair<UpdateChapter, Tracker> {
            val updateChapter = mockk<UpdateChapter>(relaxed = true)
            val tracker = mockk<Tracker>(relaxed = true)
            every { tracker.hasNotStartedReading(any()) } returns false
            val getChapters = mockk<GetChaptersByMangaId>()
            coEvery { getChapters.await(1L) } returns numberedChapters(count = 9, readThrough = 3)
            SyncChapterProgressWithTrack(
                updateChapter,
                mockk<InsertTrack>(relaxed = true),
                getChapters,
                TrackPreferences(FakePreferenceStore()),
            ).sync(1L, track(lastChapterRead = 9.0), tracker, applyRemoteToLocal = applyRemoteToLocal)
            return updateChapter to tracker
        }

        val (uploadOnlyUpdate, uploadOnlyTracker) = run(applyRemoteToLocal = false)
        coVerify(exactly = 0) { uploadOnlyTracker.update(any()) }
        coVerify(exactly = 0) { uploadOnlyUpdate.awaitAll(any()) }

        // Control: with remote-to-local enabled the same fixture does mark chapters 4..9 read.
        val (fullUpdate, fullTracker) = run(applyRemoteToLocal = true)
        coVerify(exactly = 0) { fullTracker.update(any()) }
        coVerify(exactly = 1) { fullUpdate.awaitAll(match { updates -> updates.map { it.id } == (4L..9L).toList() }) }
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
