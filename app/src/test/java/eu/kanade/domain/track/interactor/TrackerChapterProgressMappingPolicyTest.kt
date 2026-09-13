package eu.kanade.domain.track.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.tracker.model.TrackerChapterProgressMappingPolicy

class TrackerChapterProgressMappingPolicyTest {

    @Test
    fun `numeric chapter matching remains the first choice`() {
        val chapters = listOf(chapter(1, 8.0), chapter(2, 9.0), chapter(3, 274.0))

        assertEquals(274.0, TrackerChapterProgressMappingPolicy.resolve(chapters, 274.0)?.chapterNumber)
    }

    @Test
    fun `consecutive offset chapters map tracker progress by reading position`() {
        val chapters = (8L..12L).map { chapter(it, it.toDouble()) }

        assertEquals(8.0, TrackerChapterProgressMappingPolicy.resolve(chapters, 1.0)?.chapterNumber)
        assertEquals(12.0, TrackerChapterProgressMappingPolicy.resolve(chapters, 5.0)?.chapterNumber)
    }

    @Test
    fun `reading position fallback can be disabled without disabling numeric matching`() {
        val chapters = listOf(chapter(1, 8.0), chapter(2, 9.0), chapter(3, 274.0))

        assertNull(
            TrackerChapterProgressMappingPolicy.resolve(
                chapters = chapters,
                trackerProgress = 2.0,
                allowReadingOrderFallback = false,
            ),
        )
        assertEquals(
            274.0,
            TrackerChapterProgressMappingPolicy.resolve(
                chapters = chapters,
                trackerProgress = 274.0,
                allowReadingOrderFallback = false,
            )?.chapterNumber,
        )
    }

    @Test
    fun `duplicate scanlator rows do not count as separate reading positions`() {
        val chapters = listOf(
            chapter(1, 8.0, "/alpha/8"),
            chapter(2, 8.0, "/beta/8"),
            chapter(3, 9.0),
        )

        assertEquals(9.0, TrackerChapterProgressMappingPolicy.resolve(chapters, 2.0)?.chapterNumber)
    }

    @Test
    fun `gaps and fractional tracker progress do not invent an offset`() {
        val gapped = listOf(chapter(1, 8.0), chapter(2, 10.0), chapter(3, 11.0))
        val consecutive = listOf(chapter(1, 8.0), chapter(2, 9.0))

        assertNull(TrackerChapterProgressMappingPolicy.resolve(gapped, 2.0))
        assertNull(TrackerChapterProgressMappingPolicy.resolve(consecutive, 1.5))
    }

    @Test
    fun `a gap after the requested position does not invalidate its consecutive prefix`() {
        val chapters = listOf(chapter(1, 8.0), chapter(2, 9.0), chapter(3, 11.0))

        assertEquals(9.0, TrackerChapterProgressMappingPolicy.resolve(chapters, 2.0)?.chapterNumber)
    }

    private fun chapter(id: Long, number: Double, url: String = "/chapter/$id") =
        Chapter.create().copy(
            id = id,
            mangaId = 1L,
            url = url,
            name = "Chapter $number",
            chapterNumber = number,
            sourceOrder = id,
        )
}
