package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: the three jobs the Source evaluation screen used to hold now live where they belong, on the page itself and not
 * behind a row that opens another screen: trying sources (Find new sources), Source match (Your taste), and the diagnostics
 * plus Reset and clear (Troubleshooting). The evaluation screen keeps only the first job.
 */
class SourceEvaluationPagesSourceTest {

    private val screen = File("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt").readText()
    private val strings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()
    private val troubleshooting = File("src/main/java/exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt").readText()
    private val taste = File("src/main/java/exh/recs/settings/RecommendationTasteTagsSettingsScreen.kt").readText()
    private val find = File("src/main/java/exh/recs/settings/FindNewSourcesScreen.kt").readText()

    @Test
    fun `the evaluation screen has no tab row, no modes, and only the try-sources job`() {
        assertFalse(screen.contains("PrimaryTabRow"))
        assertFalse(screen.contains("SourceEvaluationMode"))
        assertFalse(screen.contains("PAGE_DIAGNOSTICS"))
        assertTrue(screen.contains("item(key = \"start_button\")"))
        assertTrue(screen.contains("item(key = \"reassess_header\")"))
        assertFalse(screen.contains("item(key = \"diagnostics_header\")"))
        assertTrue(find.contains("navigator.push(SourceEvaluationScreen())"), "Find new sources opens the try-sources screen")
    }

    @Test
    fun `installer settings and Update results are on Find new sources, not at the bottom of the evaluation page`() {
        assertTrue(find.contains("sourceEvaluationUpdateItems(evalState"))
        assertTrue(find.contains("sourceEvaluationInstallerItems(evalState, evalModel)"))
        assertTrue(find.contains("SourceEvaluationUpdateResultsDialog("))
        assertTrue(find.contains("val anchor: String? = null"), "settings search can scroll to them")
        val content = screen.substringBefore("fun sourceEvaluationInstallerKeys")
        listOf("installer_header", "installer_mode", "shizuku_setup", "reassess_header", "reassess_button", "stale_reassess_complete")
            .forEach { assertFalse(content.contains("item(key = \"$it\")"), "$it must no longer be on the evaluation page") }
        assertTrue(content.contains("item(key = \"start_button\")"), "the run and its results stay")
        val search = File("src/main/java/exh/recs/settings/RecommendationSettingsSearchScreen.kt").readText()
        assertTrue(search.contains("FindNewSourcesScreen(anchor = \"installer_header\")"))
        assertTrue(search.contains("FindNewSourcesScreen(anchor = \"reassess_header\")"))
    }

    @Test
    fun `Troubleshooting shows the diagnostics on the page itself`() {
        assertTrue(troubleshooting.contains("sourceDiagnosticsItems(evalState, evalModel)"))
        assertTrue(troubleshooting.contains("SourceEvaluationDialogs(evalState, evalModel)"), "Details and the other buttons need their dialogs here")
        assertFalse(troubleshooting.contains("SourceEvaluationScreen("), "no row that opens the evaluation screen for diagnostics")
        val diagnostics = screen.substringAfter("sourceDiagnosticsItems(").substringBefore("sourceMatchItems(")
        listOf("safety_diagnostics", "runtime_health_warning", "copy_diagnostics")
            .forEach { assertTrue(diagnostics.contains("item(key = \"$it\")"), "$it is missing from the diagnostics items") }
        assertTrue(diagnostics.contains("TextPreferenceWidget"), "copy and warning are full-width rows with a line each, not small text buttons")
    }

    @Test
    fun `Your taste shows Source match on the page itself`() {
        assertTrue(taste.contains("sourceMatchItems(evalState, recQualityQueue, evalModel)"))
        assertTrue(taste.contains("SourceEvaluationDialogs(evalState, evalModel)"))
        // v0.8.25: the only way Your taste leaves for Try sources before installing is the empty state's own button
        assertEquals(1, Regex("SourceEvaluationScreen\\(").findAll(taste).count())
        assertTrue(taste.contains("sourceMatchItems(evalState, recQualityQueue, evalModel) {\n                        navigator.push(exh.recs.evaluation.SourceEvaluationScreen())"))
        val match = screen.substringAfter("sourceMatchItems(")
        listOf("source_match_header", "rec_quality_section", "source_match_empty")
            .forEach { assertTrue(match.contains("item(key = \"$it\")"), "$it is missing from Source match") }
    }

    @Test
    fun `item keys do not collide on the pages that embed these sections`() {
        // a LazyColumn crashes on duplicate keys; Your taste has its own section header key
        assertTrue(taste.contains("item(key = \"source_match_section\")"))
        val key = Regex("item\\(key = \"source_match_header\"\\)")
        assertEquals(1, key.findAll(screen).count(), "the shared items own this key")
        assertEquals(0, key.findAll(taste).count(), "Your taste must not add a second item with it")
    }

    @Test
    fun `the management buttons are gone from the evaluation screen and live in Troubleshooting as described rows`() {
        listOf("management_header", "management_reset_disliked", "toggleManagementSection", "requestManagementAction")
            .forEach { assertFalse(screen.contains(it), "$it must not remain on the evaluation screen") }
        assertTrue(troubleshooting.contains("ResetAndClearActions.Action.entries.forEach"))
        assertTrue(troubleshooting.contains("KMR.strings.reset_confirm_undo"), "each reset names itself and says it can't be undone")
        val names = listOf("FORGET_DISLIKED_SOURCES", "SHOW_HIDDEN_SUGGESTIONS", "CLEAR_NOT_INTERESTED", "RESET_RATINGS_BASELINE")
            .filter { File("src/main/java/exh/recs/settings/ResetAndClearActions.kt").readText().contains(it) }
        assertEquals(4, names.size, "the old five actions had two that did the same thing; there are four now")
    }

    @Test
    fun `a search result for the diagnostics header opens Troubleshooting`() {
        val search = File("src/main/java/exh/recs/settings/RecommendationSettingsSearchScreen.kt").readText()
        assertTrue(search.contains("RecommendationDiagnosticsSettingsScreen(anchor = \"source_diagnostics_header\")"))
    }

    @Test
    fun `the three reassess buttons are one Update results button with a described choice for each`() {
        assertTrue(screen.contains("KMR.strings.source_evaluation_update_results_button"))
        assertFalse(screen.contains("item(key = \"reassess_updated_button\")"))
        assertFalse(screen.contains("item(key = \"stale_reassess_button\")"))
        listOf("current_tastes_desc", "updated_desc", "outdated_desc").forEach {
            assertTrue(screen.contains("source_evaluation_update_results_$it"), "$it is shown in the dialog")
        }
    }

    @Test
    fun `wording no longer sends people to a screen that does not hold the thing`() {
        assertTrue(strings.contains("name=\"rec_hub_check_sources\">Try sources before installing<"))
        listOf("Open Source evaluation to retry", "check Source evaluation for a more detailed error", "Run Source evaluation to collect it")
            .forEach { assertFalse(strings.contains(it), it) }
        val empty = strings.substringAfter("name=\"source_match_empty\">").substringBefore("</string>")
        assertTrue(empty.contains("uses the results of Try sources before installing"), "the empty state says what is actually missing")
        // v0.8.25: and gives a button that opens that page, instead of describing where it is
        val match = File("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt").readText().substringAfter("item(key = \"source_match_empty\")")
        assertTrue(match.substringBefore("// KMK <--").contains("TextButton(onClick = open) { Text(stringResource(KMR.strings.rec_hub_check_sources)) }"))
    }

    @Test
    fun `the explainer says what each check is for and that the automatic check never installs`() {
        assertTrue(strings.contains("""name="source_evaluation_two_checks_try_title">Try sources before installing<"""))
        assertTrue(strings.contains("""name="source_evaluation_two_checks_installed_title">Check the sources you use<"""))
        val installedBody = strings.substringAfter("source_evaluation_two_checks_installed_body\">").substringBefore("</string>")
        assertTrue(installedBody.contains("never installs anything"))
        val summary = strings.substringAfter("name=\"rec_auto_recheck_summary\">").substringBefore("</string>")
        assertTrue(summary.contains("your ratings have changed a lot"))
        assertTrue(summary.contains("an extension was updated"))
        assertTrue(summary.contains("Never installs or removes anything"))
    }
}
