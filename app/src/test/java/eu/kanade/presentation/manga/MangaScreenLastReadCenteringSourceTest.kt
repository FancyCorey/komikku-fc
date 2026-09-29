package eu.kanade.presentation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MangaScreenLastReadCenteringSourceTest {

    private val source by lazy {
        File("src/main/java/eu/kanade/presentation/manga/MangaScreen.kt").readText()
    }

    @Test
    fun `shared helper jumps before centering`() {
        val helper = source.substringAfter("internal suspend fun LazyListState.positionLastReadChapter(")
            .substringBefore("\n/** Owns the in-flight jump")
        val directToRevalidation = helper.substringAfter("scrollToItem(absoluteIndex)")
            .substringBefore("val refreshedIndex")

        assertTrue(helper.contains("scrollToItem(absoluteIndex)"))
        assertTrue(helper.indexOf("scrollToItem(absoluteIndex)") < helper.indexOf("afterDirectScroll()"))
        assertTrue(helper.indexOf("afterDirectScroll()") < helper.indexOf("scrollBy(delta)"))
        assertTrue(
            !directToRevalidation.contains("withFrameNanos"),
            "the direct jump must revalidate its target before yielding a frame",
        )
        assertTrue(!helper.contains("animateScrollToItem"))
        assertTrue(!helper.contains("animateScrollBy("))
        assertTrue(helper.contains("scrollBy(delta)"))
        assertTrue(helper.contains("withFrameNanos { }"))
        assertTrue(helper.contains("currentIndexOfChapter(chapterId)"))
        assertTrue(helper.contains("target?.key == chapterKey"))
        assertTrue(helper.contains("for (attempt in 0..1)"))
        assertTrue(helper.contains("LastReadChapterTargetPolicy.centeringScrollDelta("))
        assertTrue(helper.contains("?: return false"))
        assertTrue(helper.contains("return true"))
    }

    @Test
    fun `portrait and tablet callers share the cancellable latest-state controller`() {
        assertEquals(2, Regex("jumpController\\.jump\\(").findAll(source).count())
        assertEquals(
            2,
            Regex("chapterCount = \\{ latestChapterCount\\.value \\}").findAll(source).count(),
        )
        val controller = source.substringAfter("internal class LastReadChapterJumpController(")
            .substringBefore("\n@Composable\ninternal fun rememberLastReadChapterJumpController")
        assertTrue(controller.contains("job?.cancel()"))
        assertTrue(controller.contains("if (!positioned) onUnavailable()"))
        assertTrue(controller.contains("afterDirectScroll = afterDirectScroll"))
        assertTrue(source.contains("onDispose { controller.cancel() }"))
    }
}
