package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: the Source evaluation screen has two pages, Check sources and Diagnostics and recovery, so the diagnostics are one
 * tap away instead of at the bottom of a long page, and an explainer says what each of the two checks is for.
 */
class SourceEvaluationPagesSourceTest {

    private val screen = File("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt").readText()
    private val strings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()

    @Test
    fun `there are two pages in a tab row, and the diagnostics tab shows how many problems there are`() {
        assertTrue(screen.contains("private const val PAGE_CHECK = 0"))
        assertTrue(screen.contains("private const val PAGE_DIAGNOSTICS = 1"))
        assertEquals(1, Regex("PrimaryTabRow\\(selectedTabIndex = pageIndex\\)").findAll(screen).count())
        assertTrue(screen.contains("KMR.strings.source_evaluation_page_check"))
        assertTrue(screen.contains("state.runtimeHealthIssues.size + state.unsafeSources.size + state.blockedPackages.size"))
        assertTrue(screen.contains("Badge { Text(diagnosticsIssueCount.toString()) }"))
    }

    @Test
    fun `the diagnostics header and the management actions are only on the diagnostics page`() {
        val diagnostics = screen.substringAfter("if (pageIndex == PAGE_DIAGNOSTICS) {\n").substringBefore("item(key = \"bottom_spacer\")")
        listOf("diagnostics_header", "safety_diagnostics", "runtime_health_warning", "copy_diagnostics", "management_header", "management_reset_disliked")
            .forEach { assertTrue(diagnostics.contains("item(key = \"$it\")"), "$it is not on the diagnostics page") }

        val check = screen.substringAfter("if (pageIndex == PAGE_CHECK) {").substringBefore("end of the Check sources page")
        listOf("run_header", "start_button", "eval_header", "installer_header", "reassess_header", "rec_quality_section", "two_checks")
            .forEach { assertTrue(check.contains("item(key = \"$it\")"), "$it is not on the check page") }
        assertFalse(check.contains("item(key = \"diagnostics_header\")"))
        assertFalse(check.contains("item(key = \"management_header\")"))
    }

    @Test
    fun `errors, progress and the summary show on both pages`() {
        val before = screen.substringAfter("state.screenError?.let { errorKey ->").substringBefore("if (pageIndex == PAGE_CHECK) {")
        listOf("screen_error", "leftover_ext_warning", "repo_unavailable", "progress", "summary")
            .forEach { assertTrue(before.contains("item(key = \"$it\")"), "$it is hidden on a page") }
    }

    @Test
    fun `a search result for the diagnostics header opens the diagnostics page`() {
        assertTrue(screen.contains("if (anchor == \"diagnostics_header\") PAGE_DIAGNOSTICS else PAGE_CHECK"))
        assertTrue(screen.contains("if (pageIndex == PAGE_DIAGNOSTICS) return@remember listOf(\"diagnostics_header\")"))
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
