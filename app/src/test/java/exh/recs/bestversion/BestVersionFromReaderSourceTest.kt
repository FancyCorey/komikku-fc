package exh.recs.bestversion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: the reader's source chooser and Best Version are one flow. From the chooser, Compare versions opens Best Version
 * above the reader; each version offers Read from this version (nothing is migrated from a reading session), the pick goes
 * back through the same result as the in-app search, and Back returns to the reader.
 */
class BestVersionFromReaderSourceTest {

    private val reader = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText()
    private val main = File("src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt").readText()
    private val screen = File("src/main/java/exh/recs/bestversion/BestVersionCompareScreen.kt").readText()
    private val dialog = File("src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt").readText()
    private val strings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()

    @Test
    fun `the reader opens Best Version above itself and takes the pick back through the search result`() {
        val compare = reader.substringAfter("private fun compareAlternateSourceVersions()").substringBefore("private fun shareChapter()")
        assertTrue(compare.contains("alternateSourceSearchLauncher.launch("))
        assertTrue(compare.contains("action = MainActivity.INTENT_BEST_VERSION"))
        assertTrue(compare.contains("putExtra(Constants.MANGA_EXTRA, manga.id)"))
        assertTrue(compare.contains("putExtra(MainActivity.EXTRA_ALTERNATE_SOURCE_RETURN_SELECTION, true)"))
        assertTrue(reader.contains("onCompareVersions = ::compareAlternateSourceVersions"))
    }

    @Test
    fun `the main activity hosts Best Version for that manga in return-selection mode`() {
        assertTrue(main.contains("const val INTENT_BEST_VERSION = \"eu.kanade.tachiyomi.BEST_VERSION\""))
        val branch = main.substringAfter("INTENT_BEST_VERSION -> {").substringBefore("INTENT_SEARCH -> {")
        assertTrue(branch.contains("originMangaId = mangaId"))
        assertTrue(branch.contains("returnSelection = intent.getBooleanExtra(EXTRA_ALTERNATE_SOURCE_RETURN_SELECTION, false)"))
        assertTrue(branch.contains("if (mangaId > 0L)"), "a missing manga id opens nothing")
    }

    @Test
    fun `from the reader each other version offers Read from this version and nothing can be migrated`() {
        assertTrue(screen.contains("internal val returnSelection: Boolean = false"))
        val compare = screen.substringAfter("if (onReadFromHere != null) {\n                        // KMK v0.8.25").substringBefore("item(key = \"bottom_spacer\")")
        assertTrue(compare.contains("if (!isOrigin) {"), "the current version cannot be read from as an alternate")
        assertTrue(compare.contains("best_version_read_from_here_action"))
        // the select-best / keep-current button exists only in the normal flow
        assertEquals(1, Regex("best_version_select_as_best").findAll(screen).count())
        assertTrue(compare.substringAfter("} else {").contains("best_version_select_as_best"))
    }

    @Test
    fun `the pick is handed back like a search selection and Back returns to the reader`() {
        val finish = screen.substringAfter("private fun finishWithSelection(").substringBefore("// KMK --> v0.8.20-fix5")
        assertTrue(finish.contains("MainActivity.EXTRA_ALTERNATE_SOURCE_MANGA_ID, mangaId"))
        assertTrue(finish.contains("android.app.Activity.RESULT_OK"))
        assertTrue(finish.contains("activity.finish()"))
        assertTrue(screen.contains("androidx.activity.compose.BackHandler(enabled = returnSelection) { finishToReader() }"))
        assertTrue(screen.contains("navigateUp = { if (returnSelection) finishToReader() else navigator.pop() }"))
        assertFalse(screen.contains("navigateUp = navigator::pop"))
    }

    @Test
    fun `the chooser shows Compare versions only when the reader provides it, and the wording says nothing is migrated`() {
        assertTrue(dialog.contains("onCompareVersions: (() -> Unit)? = null"))
        assertTrue(dialog.contains("compareAction = onCompareVersions"))
        assertTrue(strings.contains("name=\"alternate_source_reader_compare_versions\">Compare versions<"))
        assertTrue(strings.contains("name=\"best_version_read_from_here_action\">Read from this version<"))
        assertTrue(strings.substringAfter("best_version_pick_for_reader_hint\">").substringBefore("</string>").contains("Nothing is migrated"))
    }
}
