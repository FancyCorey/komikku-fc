package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class SyncChapterProgressWithTrackTest {

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
