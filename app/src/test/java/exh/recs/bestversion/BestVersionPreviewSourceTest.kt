package exh.recs.bestversion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: looking at a version in the reader from Best Version / Compare versions must leave nothing behind. The reader
 * already has one "record nothing" path (incognito); a preview switches that path on for this reading only.
 */
class BestVersionPreviewSourceTest {

    private val activity = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText()
    private val viewModel = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt").readText()
    private val screen = File("src/main/java/exh/recs/bestversion/BestVersionCompareScreen.kt").readText()
    private val dialog = File("src/main/java/eu/kanade/presentation/reader/ReaderScheduleDialog.kt").readText()

    @Test
    fun `a preview intent is opt in and carries one extra`() {
        assertTrue(activity.contains("preview: Boolean = false"))
        assertTrue(activity.contains("const val EXTRA_PREVIEW = \"preview\""))
        assertTrue(activity.contains("if (preview) putExtra(EXTRA_PREVIEW, true)"))
    }

    @Test
    fun `the reader treats a preview like incognito`() {
        assertTrue(viewModel.contains("preview || getIncognitoState.await(manga.source)"))
        // the incognito path is what stops history, progress and tracker updates
        assertTrue(viewModel.contains("if (route.incognitoMode) return@let"))
        assertTrue(viewModel.contains("if (!route.incognitoMode && page.status !is Page.State.Error)"))
    }

    @Test
    fun `every Open in reader from Best Version is a preview`() {
        assertEquals(2, Regex("ReaderActivity\\.newIntent\\(context, manga\\.id, localChapter\\.id, preview = true\\)").findAll(screen).count())
        assertTrue(!Regex("ReaderActivity\\.newIntent\\(context, manga\\.id, localChapter\\.id\\)").containsMatchIn(screen))
    }

    @Test
    fun `adding the first reading-schedule window while the schedule is off turns it on`() {
        assertTrue(dialog.contains("if (initialEnabled != null && !enabled && windows.isEmpty()) enabled = true"))
    }
}
