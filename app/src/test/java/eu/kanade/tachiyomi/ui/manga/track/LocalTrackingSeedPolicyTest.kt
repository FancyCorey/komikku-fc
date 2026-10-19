package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import java.util.Date

// KMK --> v0.8.24: where new local tracking starts, from reading history and chapters marked read across linked versions
class LocalTrackingSeedPolicyTest {

    private val now = 9_000_000L

    private fun chapter(id: Long, number: Double, read: Boolean, modifiedSeconds: Long = 0L) =
        Chapter.create().copy(id = id, chapterNumber = number, read = read, lastModifiedAt = modifiedSeconds)

    private fun read(chapterId: Long, at: Long) = History(id = chapterId, chapterId = chapterId, readAt = Date(at), readDuration = 0L)

    /** Chapters 1..[count] (ids 100+n for version "A", 200+n for "B"), read through [readThrough]. */
    private fun chapters(base: Long, count: Int, readThrough: Int) =
        (1..count).map { n -> chapter(base + n, n.toDouble(), read = n <= readThrough) }

    private fun version(name: String, history: List<History>, chapters: List<Chapter>) =
        LocalTrackingHistoryProgressPolicy.VersionProgress(name, history, chapters)

    private fun seed(vararg versions: LocalTrackingHistoryProgressPolicy.VersionProgress<String>) =
        LocalTrackingHistoryProgressPolicy.seed(versions.toList(), now)

    @Test
    fun `history at chapter 5 and chapters marked read through 100 start at 100`() {
        val result = seed(version("A", listOf(read(105, at = 1_000L)), chapters(100, 120, readThrough = 100)))!!
        assertEquals(100.0, result.chapter.chapterNumber)
        assertEquals(1_000L, result.firstReadAt, "the start date still comes from the first real read")
    }

    @Test
    fun `marks on a confirmed linked version beyond this version's history win`() {
        val result = seed(
            version("A", listOf(read(105, at = 2_000L)), chapters(100, 20, readThrough = 5)),
            version("B", emptyList(), chapters(200, 120, readThrough = 100)),
        )!!
        assertEquals("B", result.version)
        assertEquals(100.0, result.chapter.chapterNumber)
    }

    @Test
    fun `history only keeps the most recently read chapter`() {
        val result = seed(version("A", listOf(read(103, at = 1_000L), read(107, at = 2_000L)), chapters(100, 20, readThrough = 0)))!!
        assertEquals(7.0, result.chapter.chapterNumber)
        assertEquals(2_000L, result.progressAt)
    }

    @Test
    fun `marks only start at the furthest read chapter`() {
        val result = seed(
            version("A", emptyList(), chapters(100, 20, readThrough = 12)),
            version("B", emptyList(), chapters(200, 20, readThrough = 15)),
        )!!
        assertEquals("B", result.version)
        assertEquals(15.0, result.chapter.chapterNumber)
        assertEquals(now, result.progressAt, "no modification time: now")
        assertNull(result.firstReadAt)
    }

    @Test
    fun `a deliberate reread is kept when the further chapters were read with history`() {
        val history = (1..100).map { n -> read(100L + n, at = n.toLong()) } + read(105, at = 10_000L)
        val result = seed(version("A", history, chapters(100, 120, readThrough = 100)))!!
        assertEquals(5.0, result.chapter.chapterNumber)
    }

    @Test
    fun `unrecognized chapter numbers are never compared`() {
        val unrecognizedLatest = chapter(999, -1.0, read = true)
        val result = seed(version("A", listOf(read(999, at = 1_000L)), chapters(100, 120, readThrough = 100) + unrecognizedLatest))!!
        assertEquals(999L, result.chapter.id, "the history chapter is kept when it cannot be compared")

        val onlyUnrecognizedMarks = seed(version("A", emptyList(), listOf(chapter(160, -1.0, read = true))))
        assertNull(onlyUnrecognizedMarks)
    }

    @Test
    fun `nothing read anywhere has no seed`() {
        assertNull(seed(version("A", emptyList(), chapters(100, 10, readThrough = 0))))
    }

    @Test
    fun `a marked chapter's modification time is used in milliseconds`() {
        val marked = chapters(100, 3, readThrough = 2).map { if (it.id == 102L) it.copy(lastModifiedAt = 50L) else it }
        assertEquals(50_000L, seed(version("A", emptyList(), marked))!!.progressAt)
    }
}
// KMK <--
