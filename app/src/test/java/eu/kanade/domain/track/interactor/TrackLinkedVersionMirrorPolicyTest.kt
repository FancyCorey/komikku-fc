package eu.kanade.domain.track.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

class TrackLinkedVersionMirrorPolicyTest {

    private fun track(id: Long, mangaId: Long, remoteId: Long = 20L, chapter: Double = 4.0, status: Long = 1L) = Track(
        id = id, mangaId = mangaId, trackerId = 10L, remoteId = remoteId, libraryId = 7L + id, title = "T$id",
        lastChapterRead = chapter, totalChapters = 0L, status = status, score = 0.0, remoteUrl = "u$id",
        startDate = 0L, finishDate = 0L, private = false,
    )

    @Test
    fun `the sibling takes the remote progress and status but keeps its own identity`() {
        val sibling = track(id = 2, mangaId = 2, chapter = 4.0)
        val updated = track(id = 1, mangaId = 1, chapter = 9.0, status = 2L).copy(score = 8.0, totalChapters = 50L)
        val mirrored = TrackLinkedVersionMirrorPolicy.mirror(sibling, updated)!!
        assertEquals(9.0, mirrored.lastChapterRead)
        assertEquals(2L, mirrored.status)
        assertEquals(8.0, mirrored.score)
        assertEquals(50L, mirrored.totalChapters)
        assertEquals(2L, mirrored.id)
        assertEquals(2L, mirrored.mangaId)
        assertEquals(sibling.libraryId, mirrored.libraryId)
        assertEquals("T2", mirrored.title)
    }

    @Test
    fun `never lowers progress, never crosses remote entries, and skips no-op writes`() {
        assertNull(TrackLinkedVersionMirrorPolicy.mirror(track(2, 2, chapter = 10.0), track(1, 1, chapter = 9.0)))
        assertNull(TrackLinkedVersionMirrorPolicy.mirror(track(2, 2, remoteId = 21L), track(1, 1, chapter = 9.0)))
        assertNull(TrackLinkedVersionMirrorPolicy.mirror(track(2, 2, remoteId = 0L), track(1, 1, remoteId = 0L, chapter = 9.0)))
        assertNull(TrackLinkedVersionMirrorPolicy.mirror(track(2, 2, chapter = 9.0), track(1, 1, chapter = 9.0)))
    }
}
