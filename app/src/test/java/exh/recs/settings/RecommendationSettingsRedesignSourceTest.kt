package exh.recs.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.24 Recommendation Settings redesign (design rules: private/workspaces/releases/0.8.24/settings-ux/DESIGN.md):
 * six task pages from one hub, the old "Advanced and troubleshooting" list split without losing or duplicating a control.
 */
class RecommendationSettingsRedesignSourceTest {

    private fun source(path: String): String = File(path).also {
        assertTrue(it.isFile, "expected source file at $path")
    }.readText()

    private val diagnostics get() = source("src/main/java/exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt")

    private val pageSections = listOf(
        RecommendationDiagnosticsSection.SPEED_AND_DATA,
        RecommendationDiagnosticsSection.LINKED_VERSIONS,
        RecommendationDiagnosticsSection.FOCUS_MODES,
        RecommendationDiagnosticsSection.TROUBLESHOOTING,
    )

    private val gatedKeys: List<String> get() =
        Regex("""if \(section\.includes\("([a-z_]+)"\)\) \{\s+item\(key""").findAll(diagnostics).map { it.groupValues[1] }.toList()

    @Test
    fun `every list item belongs to exactly one task page`() {
        val keys = gatedKeys
        assertTrue(keys.size >= 30, "expected the gated item list to be found, was ${keys.size}")
        assertEquals(keys.size, keys.toSet().size, "item keys must be unique")
        keys.forEach { key ->
            val owners = pageSections.filter { it.includes(key) }
            assertEquals(1, owners.size, "$key must belong to exactly one page, was ${owners.map { it.name }}")
        }
    }

    @Test
    fun `the full list still shows every item for a deep link that asks for all`() {
        gatedKeys.forEach { assertTrue(RecommendationDiagnosticsSection.ALL.includes(it)) }
    }

    @Test
    fun `languages moved to the taste page and left the split list`() {
        val taste = source("src/main/java/exh/recs/settings/RecommendationTasteTagsSettingsScreen.kt")
        assertTrue(taste.contains("item(key = \"recommendation_languages_content\")"))
        assertTrue(taste.contains("screenModel::toggleRecommendationLanguage"))
        assertFalse(diagnostics.contains("item(key = \"recommendation_languages_content\")"))
    }

    @Test
    fun `hub has six rows with current values and no tab row on detail pages`() {
        val hub = source("src/main/java/exh/recs/settings/RecommendationSettingsIndexScreen.kt")
        assertEquals(7, RecommendationSettingsQuickAccessDestination.entries.size)
        assertTrue(hub.contains("summaries.getValue(destination)"))
        assertTrue(hub.contains("filled = pressed"), "icon fills in while a row is pressed")
        listOf(
            "RecommendationSourcePrioritySettingsScreen.kt",
            "RecommendationTasteTagsSettingsScreen.kt",
            "RecommendationNonInstalledDiscoverySettingsScreen.kt",
            "RecommendationDiagnosticsSettingsScreen.kt",
            "FindNewSourcesScreen.kt",
        ).forEach { assertFalse(source("src/main/java/exh/recs/settings/$it").contains("QuickAccessRow(")) }
    }

    @Test
    fun `find new sources reaches both former pages`() {
        val screen = source("src/main/java/exh/recs/settings/FindNewSourcesScreen.kt")
        assertTrue(screen.contains("SourceEvaluationScreen()"))
        assertTrue(screen.contains("RecommendationNonInstalledDiscoverySettingsScreen()"))
    }

    @Test
    fun `source loading problems live on troubleshooting and the hub, and the evaluation banner is neutral`() {
        val hub = source("src/main/java/exh/recs/settings/RecommendationSettingsIndexScreen.kt")
        val eval = source("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt")
        assertTrue(RecommendationDiagnosticsSection.TROUBLESHOOTING.includes("runtime_health_issues"))
        assertTrue(diagnostics.contains("rec_hub_no_issues_title"), "a clear state when nothing is wrong")
        assertTrue(hub.contains("rememberSourceHealthIssues()"))
        assertTrue(hub.contains("rec_hub_troubleshooting_summary_issues"))
        val banner = eval.substringAfter("runtime_health_warning\")").substringBefore("copy_diagnostics")
        assertFalse(banner.contains("colorScheme.error"), "the always-visible banner must not use the error colour")
    }

    @Test
    fun `an anchor-only deep link opens the task page that owns the anchor`() {
        val all = RecommendationDiagnosticsSection.ALL
        assertEquals(RecommendationDiagnosticsSection.SPEED_AND_DATA, RecommendationDiagnosticsSection.resolve(all, "data_saving_content"))
        assertEquals(RecommendationDiagnosticsSection.LINKED_VERSIONS, RecommendationDiagnosticsSection.resolve(all, "best_version_sample_size"))
        assertEquals(RecommendationDiagnosticsSection.LINKED_VERSIONS, RecommendationDiagnosticsSection.resolve(all, "quality_signal_history_entry"))
        assertEquals(all, RecommendationDiagnosticsSection.resolve(all, "no_such_anchor"))
        assertEquals(all, RecommendationDiagnosticsSection.resolve(all, null))
        assertEquals(
            RecommendationDiagnosticsSection.FOCUS_MODES,
            RecommendationDiagnosticsSection.resolve(RecommendationDiagnosticsSection.FOCUS_MODES, "data_saving_content"),
        )
    }

    @Test
    fun `settings added in this release are recorded in Action History`() {
        val model = source("src/main/java/exh/recs/settings/RecommendationsSettingsScreenModel.kt")
        listOf("RECENT_READING", "DATA_SAVING").forEach {
            assertTrue(model.contains("PreferenceJournalActionType.$it"), "$it must be journalled")
        }
        val find = source("src/main/java/exh/recs/settings/FindNewSourcesScreen.kt")
        assertTrue(find.contains("PreferenceJournalActionType.AUTO_RECHECK"))
        assertFalse(find.contains("Pref.set(it)"), "automatic check writes must go through the journal")
        val registry = source("src/main/java/exh/util/ActionHistoryRegistry.kt")
        listOf("RECENT_READING", "DATA_SAVING", "AUTO_RECHECK").forEach { assertTrue(registry.contains("PreferenceJournalActionType.$it")) }
    }

    @Test
    fun `check my sources shows results before setup, recovery and reset sections`() {
        val eval = source("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt")
        val list = eval.substringAfter("LazyColumn(")
        val results = list.indexOf("item(key = \"eval_header\")")
        listOf("installer_header", "reassess_header", "diagnostics_header", "management_header").forEach { key ->
            val at = list.indexOf("item(key = \"$key\")")
            assertTrue(at > results, "$key must come after the results list")
        }
        assertTrue(list.indexOf("item(key = \"start_button\")") < results, "the run action stays above the results")
        val keys = eval.substringAfter("val itemKeysInOrder").substringBefore("ScrollToAnchorEffect(")
        assertTrue(keys.indexOf("add(\"eval_sort\")") in 0 until keys.indexOf("add(\"installer_header\")"), "anchor keys mirror the new order")
    }
}
