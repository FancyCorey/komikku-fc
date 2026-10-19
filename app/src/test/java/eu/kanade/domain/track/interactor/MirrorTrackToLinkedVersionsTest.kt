package eu.kanade.domain.track.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.io.File

class MirrorTrackToLinkedVersionsTest {

    private fun track(id: Long, mangaId: Long, chapter: Double, remoteId: Long = 20L) = Track(
        id = id, mangaId = mangaId, trackerId = 10L, remoteId = remoteId, libraryId = null, title = "T",
        lastChapterRead = chapter, totalChapters = 0L, status = 1L, score = 0.0, remoteUrl = "u",
        startDate = 0L, finishDate = 0L, private = false,
    )

    private fun harness(siblings: List<Track>): Pair<MirrorTrackToLinkedVersions, InsertTrack> {
        val getTracks = mockk<GetTracks>()
        val insertTrack = mockk<InsertTrack>()
        coEvery { getTracks.awaitByRemoteId(10L, 20L) } returns siblings
        coEvery { insertTrack.await(any()) } returns Unit
        return MirrorTrackToLinkedVersions({ getTracks }, { insertTrack }) to insertTrack
    }

    @Test
    fun `a refresh or manual edit is followed by other versions, even downwards`() = runTest {
        val updated = track(1, 1, chapter = 5.0)
        val (mirror, insert) = harness(listOf(updated, track(2, 2, chapter = 9.0)))
        mirror.await(updated, authoritative = true)
        coVerify(exactly = 1) { insert.await(match { it.id == 2L && it.lastChapterRead == 5.0 }) }
    }

    // KMK v0.8.24: privacy change on one version -> the other version's stored copy -> an edit made from that version
    @Test
    fun `a later edit from another version keeps the privacy set on the first`() = runTest {
        for (makePrivate in listOf(true, false)) {
            val versionA = track(1, 1, chapter = 5.0).copy(private = makePrivate)
            val versionB = track(2, 2, chapter = 5.0).copy(private = !makePrivate)
            val stored = mutableListOf<Track>()
            val getTracks = mockk<GetTracks>()
            val insertTrack = mockk<InsertTrack>()
            coEvery { getTracks.awaitByRemoteId(10L, 20L) } returns listOf(versionA, versionB)
            coEvery { insertTrack.await(any()) } answers { stored += firstArg<Track>() }

            // setRemotePrivate on A -> updateRemote -> authoritative mirror
            MirrorTrackToLinkedVersions({ getTracks }, { insertTrack }).await(versionA, authoritative = true)
            val storedB = stored.single { it.id == 2L }

            // A later score edit from B starts from B's stored row; the record sent to the tracker carries its flag
            val sentFromB = storedB.copy(score = 7.0)
            assertTrue(sentFromB.private == makePrivate, "B's next update must not revert privacy to ${!makePrivate}")
        }
    }

    @Test
    fun `the reading path never lowers another version`() = runTest {
        val updated = track(1, 1, chapter = 5.0)
        val (mirror, insert) = harness(listOf(track(2, 2, chapter = 9.0)))
        mirror.await(updated)
        coVerify(exactly = 0) { insert.await(any()) }
    }

    @Test
    fun `an unbound entry or a lookup failure writes nothing and does not throw`() = runTest {
        val (mirror, insert) = harness(emptyList())
        mirror.await(track(1, 1, chapter = 5.0, remoteId = 0L), authoritative = true)
        coVerify(exactly = 0) { insert.await(any()) }

        val failing = mockk<GetTracks>()
        coEvery { failing.awaitByRemoteId(any(), any()) } throws IllegalStateException("db")
        MirrorTrackToLinkedVersions({ failing }, { insert }).await(track(1, 1, chapter = 5.0), authoritative = true)
        coVerify(exactly = 0) { insert.await(any()) }
    }

    @Test
    fun `every way of changing tracker progress shares the entry with other versions`() {
        fun src(path: String) = File(path).readText()
        assertTrue(src("src/main/java/eu/kanade/domain/track/interactor/TrackChapter.kt").contains("MirrorTrackToLinkedVersions("))
        assertTrue(
            src("src/main/java/eu/kanade/domain/track/interactor/RefreshTracks.kt")
                .contains("MirrorTrackToLinkedVersions({ getTracks }, { insertTrack }).await(updatedTrack, authoritative = true)"),
        )
        assertTrue(src("src/main/java/eu/kanade/domain/track/interactor/SyncChapterProgressWithTrack.kt").contains("MirrorTrackToLinkedVersions("))
        assertTrue(src("src/main/java/eu/kanade/tachiyomi/data/track/BaseTracker.kt").contains(".await(it, authoritative = true)"))
        val mirror = src("src/main/java/eu/kanade/domain/track/interactor/MirrorTrackToLinkedVersions.kt")
        assertTrue(!mirror.contains(".update(") && !mirror.contains(".refresh("), "mirroring must not call the tracker")
    }
}
