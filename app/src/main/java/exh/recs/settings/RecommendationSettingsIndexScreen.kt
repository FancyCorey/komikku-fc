package exh.recs.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import exh.recs.ForYouDataSaving
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.presentation.util.Screen as KomikkuScreen

// KMK v0.8.24 -->
/**
 * Front door of Recommendation Settings: six task pages, one navigation system (this list; the repeating tab row
 * that used to sit on every detail page is gone). Every row shows its current value so a setting can be checked
 * without opening a page. Rows reuse the original Komikku [TextPreferenceWidget] (flat row, primary-tinted line
 * icon, title, one-line summary); pressing a row briefly fills its icon. Design rules:
 * private/workspaces/releases/0.8.24/settings-ux/DESIGN.md.
 *
 * Earlier versions fixed the hub at five rows (v0.8.14); the user approved six task-based pages on 2026-10-17, so that
 * limit no longer applies. Old destinations still exist: Source Evaluation and Sources to Try sit under
 * [FindNewSourcesScreen]; "Advanced and troubleshooting" is split by [RecommendationDiagnosticsSection].
 */
object RecommendationSettingsIndexScreen : KomikkuScreen() {
    @Suppress("unused")
    private fun readResolve(): Any = RecommendationSettingsIndexScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // Navigator-scoped and shared with the detail screens, so the summaries match what they show.
        val screenModel = navigator.rememberNavigatorScreenModel { RecommendationsSettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val recentReading = stringResource(if (state.recentReadingEnabled) KMR.strings.rec_hub_on else KMR.strings.rec_hub_off)
        val enabledSources = (state.orderedSources.size - state.disabledSourceIds.size).coerceAtLeast(0)
        val dataSaving = stringResource(
            when (state.dataSaving) {
                ForYouDataSaving.AUTO -> KMR.strings.rec_hub_data_saving_auto
                ForYouDataSaving.ALWAYS -> KMR.strings.rec_hub_data_saving_always
                ForYouDataSaving.NEVER -> KMR.strings.rec_hub_data_saving_never
            },
        )
        val healthIssues by rememberSourceHealthIssues()
        val troubleshootingSummary = if (healthIssues.isEmpty()) {
            stringResource(KMR.strings.rec_hub_troubleshooting_summary)
        } else {
            stringResource(
                KMR.strings.rec_hub_troubleshooting_summary_issues,
                pluralStringResource(KMR.plurals.rec_hub_issues_count, count = healthIssues.size, healthIssues.size),
            )
        }
        val summaries = mapOf(
            RecommendationSettingsQuickAccessDestination.YourTaste to stringResource(
                KMR.strings.rec_hub_taste_summary,
                recentReading,
                pluralStringResource(KMR.plurals.rec_settings_summary_languages, count = state.recommendationLanguages.size, state.recommendationLanguages.size),
            ),
            RecommendationSettingsQuickAccessDestination.ForYouFeed to
                pluralStringResource(KMR.plurals.rec_hub_sources_count, count = enabledSources, enabledSources),
            RecommendationSettingsQuickAccessDestination.FindNewSources to
                if (state.nonInstalledSuggestions.isEmpty()) {
                    stringResource(KMR.strings.rec_hub_find_sources_summary)
                } else {
                    pluralStringResource(KMR.plurals.rec_hub_suggestions_count, count = state.nonInstalledSuggestions.size, state.nonInstalledSuggestions.size)
                },
            RecommendationSettingsQuickAccessDestination.SpeedAndData to
                stringResource(KMR.strings.rec_hub_speed_summary, dataSaving),
            RecommendationSettingsQuickAccessDestination.LinkedVersions to
                stringResource(KMR.strings.rec_hub_linked_versions_summary),
            RecommendationSettingsQuickAccessDestination.Troubleshooting to troubleshootingSummary,
        )

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.taste_settings_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        // KMK v0.8.9: same icon/semantics as main Settings search's app-bar action.
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(KMR.strings.rec_settings_search),
                                    icon = Icons.Outlined.Search,
                                    onClick = { navigator.push(RecommendationSettingsSearchScreen()) },
                                ),
                            ),
                        )
                    },
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(RecommendationSettingsQuickAccessDestination.entries, key = { "rec-settings-hub-${it.name}" }) { destination ->
                    HubRow(
                        destination = destination,
                        summary = summaries.getValue(destination),
                        onClick = { navigator.push(destination.toScreen()) },
                    )
                }
            }
        }
    }
}

/** A settings-list row whose icon fills in while it is pressed (outlined at rest). Ripple comes from the row itself. */
@Composable
private fun HubRow(
    destination: RecommendationSettingsQuickAccessDestination,
    summary: String,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    TextPreferenceWidget(
        modifier = Modifier.pointerInput(Unit) {
            // Observe only (never consumes), so the row's own click and ripple still work.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        },
        title = stringResource(destination.hubTitleRes()),
        subtitle = summary,
        icon = destination.icon(filled = pressed),
        onPreferenceClick = onClick,
    )
}
// KMK <--
