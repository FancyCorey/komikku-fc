package exh.recs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.isTabletUi
import exh.recs.ForYouDataSaving
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
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
        // KMK v0.8.24: on tablets, like Komikku's own Settings, the list stays on the left and the chosen page opens on
        // the right, so switching between pages needs no Back presses.
        if (isTabletUi()) {
            TwoPaneContent()
        } else {
            val navigator = LocalNavigator.currentOrThrow
            HubList(twoPane = false, onNavigateUp = navigator::pop)
        }
    }

    @Composable
    private fun TwoPaneContent() {
        val parentNavigator = LocalNavigator.currentOrThrow
        Navigator(
            screen = RecommendationSettingsQuickAccessDestination.entries.first().toScreen(),
            onBackPressed = null,
        ) { innerNavigator ->
            val insets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
            TwoPanelBox(
                modifier = Modifier
                    .windowInsetsPadding(insets)
                    .consumeWindowInsets(insets),
                startContent = {
                    HubList(twoPane = true, onNavigateUp = parentNavigator::pop)
                },
                endContent = {
                    // A page with nothing behind it in the right pane leaves Recommendation Settings on back.
                    val pop: () -> Unit = {
                        if (innerNavigator.canPop) innerNavigator.pop() else parentNavigator.pop()
                    }
                    CompositionLocalProvider(LocalBackPress provides pop) {
                        DefaultNavigatorScreenTransition(navigator = innerNavigator)
                    }
                },
            )
        }
    }

    @Composable
    private fun HubList(twoPane: Boolean, onNavigateUp: () -> Unit) {
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
            RecommendationSettingsQuickAccessDestination.FocusModes to
                stringResource(KMR.strings.rec_hub_focus_modes_link_summary),
            RecommendationSettingsQuickAccessDestination.FindNewSources to
                if (state.nonInstalledSuggestions.isEmpty()) {
                    stringResource(KMR.strings.rec_hub_find_sources_summary)
                } else {
                    pluralStringResource(KMR.plurals.rec_hub_suggestions_count, count = state.nonInstalledSuggestions.size, state.nonInstalledSuggestions.size)
                },
            RecommendationSettingsQuickAccessDestination.SourceEvaluation to
                stringResource(KMR.strings.rec_hub_check_sources_summary),
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
                    navigateUp = onNavigateUp,
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
            containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.surface,
        ) { contentPadding ->
            // KMK v0.8.24: the open page (tablet) or the page opened last (phone) keeps its filled icon
            var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
            val selected = if (twoPane) {
                navigator.items.firstOrNull()?.let { root ->
                    RecommendationSettingsQuickAccessDestination.entries.firstOrNull { it.matches(root) }
                }
            } else {
                null
            }
            LazyColumn(contentPadding = contentPadding) {
                items(RecommendationSettingsQuickAccessDestination.entries, key = { "rec-settings-hub-${it.name}" }) { destination ->
                    val isSelected = destination == selected
                    val rowModifier = if (twoPane) {
                        Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                    } else {
                        Modifier
                    }
                    CompositionLocalProvider(
                        LocalContentColor provides if (isSelected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            LocalContentColor.current
                        },
                    ) {
                        HubRow(
                            destination = destination,
                            summary = summaries.getValue(destination),
                            selected = isSelected || (!twoPane && destination.name == lastOpened),
                            modifier = rowModifier,
                            onClick = {
                                lastOpened = destination.name
                                if (twoPane) {
                                    navigator.replaceAll(destination.toScreen())
                                } else {
                                    navigator.push(destination.toScreen())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A settings-list row whose icon fills in when it is pressed and stays filled while it is the selected page (KMK v0.8.24).
 * The change is animated: the outline cross-fades into the filled icon, the colour follows, and the icon pops slightly.
 */
@Composable
private fun HubRow(
    destination: RecommendationSettingsQuickAccessDestination,
    summary: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val filled = pressed || selected
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "hubIconTint",
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 1.15f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f),
        label = "hubIconScale",
    )
    eu.kanade.presentation.more.settings.widget.BasePreferenceWidget(
        modifier = modifier.pointerInput(Unit) {
            // Observe only (never consumes), so the row's own click and ripple still work.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        },
        title = stringResource(destination.hubTitleRes()),
        subcomponent = {
            Text(
                text = summary,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .secondaryItemAlpha(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 10,
            )
        },
        icon = {
            androidx.compose.animation.Crossfade(targetState = filled, label = "hubIconFill") { isFilled ->
                Icon(
                    imageVector = destination.icon(filled = isFilled),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }
        },
        onClick = onClick,
    )
}

/** Same paler list background as Komikku's two-pane Settings (SettingsMainScreen.getPalerSurface). */
@Composable
private fun getPalerSurface(): androidx.compose.ui.graphics.Color {
    val surface = MaterialTheme.colorScheme.surface
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return remember(surface, dark) {
        val arr = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(surface.toArgb(), arr)
        arr[2] = if (dark) arr[2] - 0.05f else (arr[2] + 0.02f)
        arr[2] = arr[2].coerceIn(0f, 1f)
        androidx.compose.ui.graphics.Color.hsl(arr[0], arr[1], arr[2])
    }
}
// KMK <--
