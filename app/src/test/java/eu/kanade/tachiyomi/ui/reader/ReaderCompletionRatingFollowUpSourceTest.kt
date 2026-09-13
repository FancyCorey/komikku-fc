package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Keeps completion-rating navigation alive long enough to launch the other-version follow-up. */
class ReaderCompletionRatingFollowUpSourceTest {

    @Test
    fun `other-version activity launches before completion dialog dismissal`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText()
        val handler = source.substringAfter("                is ReaderViewModel.Dialog.ChapterCompletionRatingGroupOffer ->")
            .substringBefore("// KMK <--")
        val launch = handler.indexOf("startActivity(")
        val dismiss = handler.indexOf("onDismissRequest()")

        assertTrue(launch >= 0, "completion rating follow-up must launch the existing MainActivity route")
        assertTrue(dismiss > launch, "dialog dismissal must happen after the follow-up launch")
        assertTrue(handler.contains("OPEN_CROSS_EXTENSION_MATCH_FOR_RATING"))
    }

    @Test
    fun `reader uses one configurable follow-up path for all rating values`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt").readText()

        assertTrue(source.contains("chapterCompletionRatingOtherVersionsPromptEnabled"))
        assertTrue(source.contains("MangaRating.LOVE"))
        assertTrue(source.contains("MangaRating.LIKE"))
        assertTrue(source.contains("MangaRating.DISLIKE"))
        assertTrue(source.contains("MangaRating.NOT_INTERESTED"))
        assertTrue(source.contains("ChapterCompletionRatingGroupOffer"))
    }

    @Test
    fun `main activity preserves the selected rating and uses the alternate-version route`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt").readText()
        val handler = source.substringAfter("Constants.OPEN_CROSS_EXTENSION_MATCH_FOR_RATING ->")
            .substringBefore("Constants.OPEN_OCR_SEARCH")

        assertTrue(handler.contains("CROSS_EXTENSION_MATCH_MANGA_ID_EXTRA"))
        assertTrue(handler.contains("CROSS_EXTENSION_MATCH_RATING_EXTRA"))
        assertTrue(handler.contains("MangaRating.fromValue"))
        assertTrue(handler.contains("CrossExtensionMatchMode.Rating(rating)"))
        assertTrue(handler.contains("CrossExtensionMatchScreen.fromMode"))
    }

    @Test
    fun `alternate-version failure keeps the action recoverable`() {
        val source = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()

        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue(source.contains("throw e"))
        assertTrue(source.contains("CrossSourceIdentityMutationResult.FAILED"))
        assertTrue(source.contains("if (completed) onComplete()"))
    }

    @Test
    fun `successful alternate-version apply returns to the reader flow`() {
        val source = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreen.kt").readText()

        assertTrue(source.contains("screenModel.applyRating { navigator.pop() }"))
    }
}
