package eu.kanade.domain.track.interactor

import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.matching.ConfirmedMangaGroupTargets
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class ShareTrackerBindingWithLinkedVersionsTest {

    private fun track(id: Long, mangaId: Long, trackerId: Long = 10L, remoteId: Long = 20L) = Track(
        id = id, mangaId = mangaId, trackerId = trackerId, remoteId = remoteId, libraryId = 5L, title = "T",
        lastChapterRead = 4.0, totalChapters = 0L, status = 1L, score = 0.0, remoteUrl = "u",
        startDate = 0L, finishDate = 0L, private = false,
    )

    @Test
    fun `only versions without an entry on that tracker get the shared link`() {
        val bound = track(1, 1)
        val rows = TrackerBindingSharePolicy.newBindings(
            bound,
            listOf(1L, 2L, 3L, 4L, 2L),
            mapOf(
                3L to listOf(track(30, 3, remoteId = 99L)),
                4L to listOf(track(40, 4, trackerId = 11L)),
            ),
        )
        assertEquals(listOf(2L, 4L), rows.map { it.mangaId })
        rows.forEach {
            assertEquals(20L, it.remoteId)
            assertEquals(10L, it.trackerId)
            assertEquals(0L, it.id)
        }
    }

    private fun harness(enabled: Boolean): Pair<ShareTrackerBindingWithLinkedVersions, InsertTrack> {
        val prefs = mockk<SourcePreferences>()
        every { prefs.confirmedTrackedVersionLocalTrackingPropagationEnabled().get() } returns enabled
        val origin = mockk<Manga>(relaxed = true).also { every { it.id } returns 1L }
        val sibling = mockk<Manga>(relaxed = true).also { every { it.id } returns 2L }
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(1L) } returns origin
        val targets = mockk<ConfirmedMangaGroupTargets>()
        coEvery { targets.await(origin) } returns listOf(origin, sibling)
        val getTracks = mockk<GetTracks>()
        coEvery { getTracks.await(listOf(2L)) } returns emptyMap()
        val insert = mockk<InsertTrack>()
        coEvery { insert.await(any()) } returns Unit
        return ShareTrackerBindingWithLinkedVersions({ prefs }, { getManga }, { targets }, { getTracks }, { insert }) to insert
    }

    @Test
    fun `with Track linked versions together on, the link is shared`() = runTest {
        val (share, insert) = harness(enabled = true)
        share.await(track(1, 1))
        coVerify(exactly = 1) { insert.await(match { it.mangaId == 2L && it.remoteId == 20L }) }
    }

    @Test
    fun `with the setting off nothing is shared`() = runTest {
        val (share, insert) = harness(enabled = false)
        share.await(track(1, 1))
        coVerify(exactly = 0) { insert.await(any()) }
    }
}
