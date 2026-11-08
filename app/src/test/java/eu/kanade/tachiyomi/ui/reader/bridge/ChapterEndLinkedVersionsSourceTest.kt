package eu.kanade.tachiyomi.ui.reader.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK v0.8.25 -->
/**
 * The last chapter of a series answers "is there more?" before it asks for a choice: a Next row when a linked version has the
 * next chapter, today's screen otherwise, and a list that shows each version's latest chapter. These pin how that is wired.
 */
class ChapterEndLinkedVersionsSourceTest {

    private fun source(path: String) = File(path).readText().replace("\r\n", "\n")

    private val viewModel = source("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
    private val transition = source("src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt")
    private val transitionView = source("src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt")

    @Test
    fun `both viewers ask for the answer when the last chapter's end screen is built`() {
        assertTrue(source("src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt").contains("observeLinkedVersionsAhead("))
        assertTrue(source("src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt").contains("observeLinkedVersionsAhead("))
    }

    @Test
    fun `the check only runs on the last chapter, for the setting that offers it, and not while saving data`() {
        assertTrue(transitionView.contains("takeIf { it.to == null }"))
        val check = viewModel.substringAfter("fun checkLinkedVersionsAhead(").substringBefore("fun openLinkedVersionsAhead")
        assertTrue(check.contains("alternateSourceOfferAtEnd().get()"))
        assertTrue(check.contains("ForYouDataSavingPolicy.isActiveNow"))
    }

    @Test
    fun `an answer for one chapter is never shown on another and is dropped when the route changes`() {
        assertTrue(transitionView.contains("it.finishedChapterId == finishedId"))
        val invalidate = viewModel.substringAfter("private fun invalidateAlternateSourceCandidateStateForRouteChange()")
            .substringBefore("alternateSourceCandidateGeneration++")
        assertTrue(invalidate.contains("mutableLinkedVersionsAhead.value = null"))
        assertTrue(invalidate.contains("aheadCheckJob?.cancel()"))
    }

    @Test
    fun `the Next row replaces only the no next chapter card and only when something is ahead`() {
        val branch = transition.substringAfter("if (nextFromLinkedVersion != null && onNextFromLinkedVersion != null)")
            .substringBefore("// KMK <--")
        assertTrue(branch.contains("NextFromLinkedVersionRow("))
        assertTrue(branch.contains("NoChapterNotification("), "today's card stays for every other state")
        assertTrue(transition.contains("nextFromLinkedVersion.takeIf { offerAtEnd }"))
    }

    @Test
    fun `continuing from the end of a series starts at the chapter after the finished one`() {
        assertTrue(viewModel.contains(".nextChapterAfter(discovery.chapters, originNumber)"))
        assertTrue(viewModel.contains("selection.request.followingPrimaryChapterId == null"))
    }

    @Test
    fun `a linked version's latest chapter and how far ahead it is appear on its row, ahead ones first`() {
        val dialog = source("src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt")
        assertTrue(dialog.contains("row.latestChapter"))
        assertTrue(dialog.contains("alternate_source_reader_chapters_ahead"))
        assertTrue(viewModel.contains("orderAheadFirst("))
    }

    @Test
    fun `no second comparison screen and no new explanatory text`() {
        val strings = source("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml")
        val added = Regex("""name="(alternate_source_reader_chapters_ahead|alternate_source_reader_sources_count)">([^<]*)<""")
            .findAll(strings).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            mapOf(
                "alternate_source_reader_chapters_ahead" to "%1\$d ahead",
                "alternate_source_reader_sources_count" to "%1\$d sources",
            ),
            added,
        )
        assertFalse(added.values.any { it.contains("preview", ignoreCase = true) })
    }
}
// KMK <--
