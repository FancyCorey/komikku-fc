package exh.recs.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import exh.recs.evaluation.SourceAutoRecheckPolicy
import exh.recs.evaluation.SourceAutoRecheckScheduler
import exh.recs.evaluation.SourceEvaluationScreen
import exh.util.PreferenceJournalActionType
import exh.util.PreferenceUndoJournal
import exh.util.PreferenceUndoRecorder
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

// KMK v0.8.24 -->
/**
 * "Find new sources": the single place for judging the sources you have against your taste and discovering ones you do not.
 * It replaces two separate top-level pages (Source Evaluation and Sources to Try), which stay as the detail pages
 * behind the two rows below with unchanged controls. The Automatic check section re-checks stale sources on a schedule
 * the user sets; it is off until turned on and never installs anything (see [SourceAutoRecheckPolicy]).
 */
class FindNewSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val navigateUp = rememberRecommendationSettingsNavigateUp(navigator)
        val context = LocalContext.current
        val screenModel = navigator.rememberNavigatorScreenModel { RecommendationsSettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val preferences = remember { Injekt.get<SourcePreferences>() }
        val autoEnabledPref = remember { preferences.recommendationAutoRecheckEnabled() }
        val intervalPref = remember { preferences.recommendationAutoRecheckIntervalDays() }
        val unmeteredPref = remember { preferences.recommendationAutoRecheckUnmeteredOnly() }
        val batteryPref = remember { preferences.recommendationAutoRecheckBatteryGuard() }
        val applyPref = remember { preferences.recommendationAutoRecheckApplyToForYou() }
        val lastRunPref = remember { preferences.recommendationAutoRecheckLastRunMillis() }
        val autoEnabled by autoEnabledPref.collectAsState()
        val intervalDays by intervalPref.collectAsState()
        val unmeteredOnly by unmeteredPref.collectAsState()
        val batteryGuard by batteryPref.collectAsState()
        val applyToForYou by applyPref.collectAsState()
        val lastRun by lastRunPref.collectAsState()
        var showIntervalDialog by rememberSaveable { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.rec_hub_find_sources),
                    navigateUp = navigateUp,
                    scrollBehavior = scrollBehavior,
                    actions = {
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
                item(key = "check_my_sources") {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.rec_hub_check_sources),
                        subtitle = stringResource(KMR.strings.rec_hub_check_sources_summary),
                        onPreferenceClick = { navigator.push(SourceEvaluationScreen()) },
                    )
                }
                item(key = "suggested_sources") {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.rec_hub_suggested_sources),
                        subtitle = if (state.nonInstalledSuggestions.isEmpty()) {
                            stringResource(KMR.strings.rec_hub_suggested_sources_summary)
                        } else {
                            pluralStringResource(
                                KMR.plurals.rec_hub_suggestions_count,
                                count = state.nonInstalledSuggestions.size,
                                state.nonInstalledSuggestions.size,
                            ) + ". " + stringResource(KMR.strings.rec_hub_suggested_sources_summary)
                        },
                        onPreferenceClick = { navigator.push(RecommendationNonInstalledDiscoverySettingsScreen()) },
                    )
                }

                item(key = "auto_recheck_header") {
                    SectionHeader(stringResource(KMR.strings.rec_auto_recheck_header))
                }
                item(key = "auto_recheck_enabled") {
                    SwitchPreferenceWidget(
                        title = stringResource(KMR.strings.rec_auto_recheck),
                        subtitle = stringResource(KMR.strings.rec_auto_recheck_summary),
                        checked = autoEnabled,
                        onCheckedChanged = {
                            journalled(preferences, "autoRecheckEnabled", autoEnabledPref, it)
                            SourceAutoRecheckScheduler.sync(context, preferences)
                        },
                    )
                }
                item(key = "auto_recheck_interval") {
                    val every = pluralStringResource(KMR.plurals.rec_auto_recheck_every_days, count = intervalDays, intervalDays)
                    val nextDue = SourceAutoRecheckPolicy.nextDueMillis(lastRun, intervalDays)
                    DimmedWhenOff(enabled = autoEnabled) {
                        TextPreferenceWidget(
                            title = stringResource(KMR.strings.rec_auto_recheck_interval),
                            subtitle = when {
                                !autoEnabled -> stringResource(KMR.strings.rec_auto_recheck_needs_switch)
                                nextDue == null -> stringResource(KMR.strings.rec_auto_recheck_interval_first, every)
                                else -> stringResource(
                                    KMR.strings.rec_auto_recheck_interval_next,
                                    every,
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nextDue)),
                                )
                            },
                            onPreferenceClick = if (autoEnabled) ({ showIntervalDialog = true }) else null,
                        )
                    }
                }
                item(key = "auto_recheck_unmetered") {
                    DimmedWhenOff(enabled = autoEnabled) {
                        SwitchPreferenceWidget(
                            title = stringResource(KMR.strings.rec_auto_recheck_unmetered),
                            subtitle = if (autoEnabled) {
                                stringResource(KMR.strings.rec_auto_recheck_unmetered_summary)
                            } else {
                                stringResource(KMR.strings.rec_auto_recheck_needs_switch)
                            },
                            checked = unmeteredOnly,
                            onCheckedChanged = {
                                if (autoEnabled) {
                                    journalled(preferences, "autoRecheckUnmeteredOnly", unmeteredPref, it)
                                    SourceAutoRecheckScheduler.sync(context, preferences)
                                }
                            },
                        )
                    }
                }
                item(key = "auto_recheck_apply") {
                    DimmedWhenOff(enabled = autoEnabled) {
                        SwitchPreferenceWidget(
                            title = stringResource(KMR.strings.rec_auto_recheck_apply),
                            subtitle = if (autoEnabled) {
                                stringResource(KMR.strings.rec_auto_recheck_apply_summary)
                            } else {
                                stringResource(KMR.strings.rec_auto_recheck_needs_switch)
                            },
                            checked = applyToForYou,
                            onCheckedChanged = { if (autoEnabled) journalled(preferences, "autoRecheckApplyToForYou", applyPref, it) },
                        )
                    }
                }
                item(key = "auto_recheck_battery") {
                    DimmedWhenOff(enabled = autoEnabled) {
                        SwitchPreferenceWidget(
                            title = stringResource(KMR.strings.rec_auto_recheck_battery),
                            subtitle = if (autoEnabled) {
                                stringResource(KMR.strings.rec_auto_recheck_battery_summary)
                            } else {
                                stringResource(KMR.strings.rec_auto_recheck_needs_switch)
                            },
                            checked = batteryGuard,
                            onCheckedChanged = {
                                if (autoEnabled) {
                                    journalled(preferences, "autoRecheckBatteryGuard", batteryPref, it)
                                    SourceAutoRecheckScheduler.sync(context, preferences)
                                }
                            },
                        )
                    }
                }
            }
        }

        if (showIntervalDialog) {
            AutoRecheckIntervalDialog(
                currentDays = intervalDays,
                onDismiss = { showIntervalDialog = false },
                onConfirm = { days ->
                    journalled(preferences, "autoRecheckIntervalDays", intervalPref, SourceAutoRecheckPolicy.coerceIntervalDays(days))
                    SourceAutoRecheckScheduler.sync(context, preferences)
                    showIntervalDialog = false
                },
            )
        }
    }
}

/** Writes [newValue] and records it in Action History, so the change can be undone like other recommendation settings. */
private fun <T> journalled(preferences: SourcePreferences, identityKey: String, preference: Preference<T>, newValue: T) {
    val entry = PreferenceUndoRecorder.buildPreferenceEntry(
        preferences,
        PreferenceJournalActionType.AUTO_RECHECK,
        identityKey,
        preference,
        preference.get(),
        newValue,
    )
    preference.set(newValue)
    entry?.let { PreferenceUndoJournal.record(it) }
}

/** Dims a row that cannot be used yet. Its summary carries the reason, so the dimming is never the only signal. */
@Composable
private fun DimmedWhenOff(enabled: Boolean, content: @Composable () -> Unit) {
    Column(modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) { content() }
}

@Composable
private fun AutoRecheckIntervalDialog(
    currentDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val startsCustom = !SourceAutoRecheckPolicy.isPreset(currentDays)
    var selectedPreset by rememberSaveable { mutableStateOf(if (startsCustom) null else currentDays) }
    var customText by rememberSaveable { mutableStateOf(currentDays.toString()) }
    val customDays = customText.toIntOrNull()
    val customValid = customDays != null && customDays in SourceAutoRecheckPolicy.MIN_INTERVAL_DAYS..SourceAutoRecheckPolicy.MAX_INTERVAL_DAYS
    val chosen = selectedPreset ?: customDays

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.rec_auto_recheck_interval)) },
        text = {
            Column {
                Text(
                    text = stringResource(KMR.strings.rec_auto_recheck_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                )
                SourceAutoRecheckPolicy.PRESET_DAYS.forEach { days ->
                    IntervalOptionRow(
                        label = pluralStringResource(KMR.plurals.rec_auto_recheck_every_days, count = days, days),
                        selected = selectedPreset == days,
                        onSelect = { selectedPreset = days },
                    )
                }
                IntervalOptionRow(
                    label = stringResource(KMR.strings.rec_auto_recheck_custom),
                    selected = selectedPreset == null,
                    onSelect = { selectedPreset = null },
                )
                if (selectedPreset == null) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(KMR.strings.rec_auto_recheck_custom_days)) },
                        singleLine = true,
                        isError = !customValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.padding.small),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
        confirmButton = {
            TextButton(
                enabled = selectedPreset != null || customValid,
                onClick = { chosen?.let(onConfirm) },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
    )
}

@Composable
private fun IntervalOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
// KMK <--
