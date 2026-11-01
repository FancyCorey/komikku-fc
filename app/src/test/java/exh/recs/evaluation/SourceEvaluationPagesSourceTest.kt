package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: the three jobs the Source evaluation screen used to hold are split by where they belong:
 * Find new sources (CHECK), Your taste (SOURCE_MATCH) and Troubleshooting (DIAGNOSTICS plus Reset and clear). There is no
 * tab row; each entry point opens a screen with one job.
 */
class SourceEvaluationPagesSourceTest {

    private val screen = File("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt").readText()
    private val strings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()
    private val troubleshooting = File("src/main/java/exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt").readText()
    private val taste = File("src/main/java/exh/recs/settings/RecommendationTasteTagsSettingsScreen.kt").readText()
    private val find = File("src/main/java/exh/recs/settings/FindNewSourcesScreen.kt").readText()

    @Test
    fun `the screen takes a mode and has no tab row`() {
        assertTrue(screen.contains("enum class SourceEvaluationMode { CHECK, SOURCE_MATCH, DIAGNOSTICS }"))
        assertTrue(screen.contains("val mode: SourceEvaluationMode = SourceEvaluationMode.CHECK"))
        assertFalse(screen.contains("PrimaryTabRow"))
        assertFalse(screen.contains("BadgedBox"))
    }

    @Test
    fun `each entry point opens its own mode`() {
        assertTrue(find.contains("navigator.push(SourceEvaluationScreen())"), "Find new sources opens the check")
        assertTrue(taste.contains("SourceEvaluationMode.SOURCE_MATCH"), "Your taste opens Source match")
        assertEquals(2, Regex("SourceEvaluationMode.DIAGNOSTICS").findAll(troubleshooting).count(), "both Troubleshooting rows open diagnostics")
    }

    @Test
    fun `the three pages show only their own sections`() {
        val diagnostics = screen.substringAfter("if (pageIndex == PAGE_DIAGNOSTICS) {\n").substringBefore("item(key = \"bottom_spacer\")")
        listOf("diagnostics_header", "safety_diagnostics", "runtime_health_warning", "copy_diagnostics")
            .forEach { assertTrue(diagnostics.contains("item(key = \"$it\")"), "$it is not on the diagnostics page") }

        val match = screen.substringAfter("if (pageIndex == PAGE_SOURCE_MATCH) {").substringBefore("// KMK v0.8.25: end of the Source match page")
        listOf("source_match_header", "rec_quality_section", "source_match_empty")
            .forEach { assertTrue(match.contains("item(key = \"$it\")"), "$it is not on the Source match page") }

        val check = screen.substringAfter("if (pageIndex == PAGE_CHECK) {").substringBefore("end of the Check sources page")
        listOf("run_header", "start_button", "eval_header", "installer_header", "reassess_header", "two_checks")
            .forEach { assertTrue(check.contains("item(key = \"$it\")"), "$it is not on the check page") }
        assertFalse(check.contains("item(key = \"rec_quality_section\")"))
        assertFalse(check.contains("item(key = \"diagnostics_header\")"))
    }

    @Test
    fun `the management buttons are gone from this screen and live in Troubleshooting as described rows`() {
        listOf("management_header", "management_reset_disliked", "toggleManagementSection", "requestManagementAction")
            .forEach { assertFalse(screen.contains(it), "$it must not remain on the evaluation screen") }
        assertTrue(troubleshooting.contains("ResetAndClearActions.Action.entries.forEach"))
        assertTrue(troubleshooting.contains("KMR.strings.reset_confirm_undo"), "each reset names itself and says it can't be undone")
        // the old five actions had two that did the same thing; there are four now
        assertEquals(4, ResetActionNames.size)
    }

    @Test
    fun `errors, progress and the summary show on every page`() {
        val before = screen.substringAfter("state.screenError?.let { errorKey ->").substringBefore("if (pageIndex == PAGE_CHECK) {")
        listOf("screen_error", "leftover_ext_warning", "repo_unavailable", "progress", "summary")
            .forEach { assertTrue(before.contains("item(key = \"$it\")"), "$it is hidden on a page") }
    }

    @Test
    fun `a search result for the diagnostics header opens the diagnostics page`() {
        assertTrue(screen.contains("anchor == \"diagnostics_header\" || mode == SourceEvaluationMode.DIAGNOSTICS -> PAGE_DIAGNOSTICS"))
        assertTrue(screen.contains("if (pageIndex == PAGE_DIAGNOSTICS) return@remember listOf(\"diagnostics_header\")"))
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

    private companion object {
        val ResetActionNames = listOf("FORGET_DISLIKED_SOURCES", "SHOW_HIDDEN_SUGGESTIONS", "CLEAR_NOT_INTERESTED", "RESET_RATINGS_BASELINE")
            .filter { File("src/main/java/exh/recs/settings/ResetAndClearActions.kt").readText().contains(it) }
    }
}
