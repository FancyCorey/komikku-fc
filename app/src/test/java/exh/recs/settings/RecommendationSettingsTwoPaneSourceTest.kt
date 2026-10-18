package exh.recs.settings

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.24: on tablets Recommendation Settings keeps its list on the left and the chosen page on the right, like
 * Komikku's own Settings, so switching pages needs no Back presses.
 */
class RecommendationSettingsTwoPaneSourceTest {
    private fun read(path: String) = File(path).readText()

    @Test
    fun `tablets get the list on the left and the page on the right`() {
        val hub = read("src/main/java/exh/recs/settings/RecommendationSettingsIndexScreen.kt")
        assertTrue(hub.contains("if (isTabletUi()) {"))
        assertTrue(hub.contains("TwoPanelBox("))
        assertTrue(hub.contains("navigator.replaceAll(destination.toScreen())"), "the left list switches the right page in place")
        assertTrue(hub.contains("if (innerNavigator.canPop) innerNavigator.pop() else parentNavigator.pop()"))
    }

    @Test
    fun `pages leave recommendation settings when nothing is behind them in the right pane`() {
        listOf(
            "src/main/java/exh/recs/settings/FindNewSourcesScreen.kt",
            "src/main/java/exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt",
            "src/main/java/exh/recs/settings/RecommendationNonInstalledDiscoverySettingsScreen.kt",
            "src/main/java/exh/recs/settings/RecommendationSourcePrioritySettingsScreen.kt",
            "src/main/java/exh/recs/settings/RecommendationTasteTagsSettingsScreen.kt",
            "src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt",
        ).forEach { path ->
            assertTrue(read(path).contains("rememberRecommendationSettingsNavigateUp(navigator)"), path)
        }
    }

    @Test
    fun `two-pane settings opens recommendation settings full screen`() {
        val main = read("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt")
        assertTrue(main.contains("if (twoPane && screen === RecommendationSettingsIndexScreen)"))
    }
}
