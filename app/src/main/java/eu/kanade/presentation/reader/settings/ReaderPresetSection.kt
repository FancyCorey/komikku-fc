package eu.kanade.presentation.reader.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource

// KMK v0.8.25 -->
/**
 * The first row under "For this series" in the reader settings, in the same chip format as reading mode and orientation: one
 * chip for the usual settings and one per saved preset (the series' current one is selected), then the actions that save,
 * update or delete a preset. A preset is applied when the reader opens and the usual settings return when it closes, and it
 * follows the series to its linked versions.
 */
@Composable
internal fun ReaderPresetSection(screenModel: ReaderSettingsScreenModel) {
    val manga by screenModel.mangaFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val presets = remember(version) { screenModel.presets.presets() }
    var assigned by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(manga, version) { assigned = manga?.let { screenModel.presets.presetNameFor(it) } }

    fun use(name: String?) {
        val current = manga ?: return
        scope.launch {
            screenModel.presets.endSession()
            screenModel.presets.assign(current, name)
            screenModel.presets.beginFor(current)
            version++
        }
    }

    SettingsChipRow(KMR.strings.reader_presets_label) {
        FilterChip(
            selected = assigned == null,
            onClick = { use(null) },
            label = { Text(stringResource(KMR.strings.reader_presets_none)) },
        )
        presets.forEach { preset ->
            FilterChip(
                selected = assigned == preset.name,
                onClick = { use(preset.name) },
                label = { Text(preset.name) },
            )
        }
        AssistChip(
            onClick = { naming = true },
            label = { Text(stringResource(KMR.strings.reader_presets_save_chip)) },
        )
        assigned?.let { name ->
            AssistChip(
                onClick = {
                    screenModel.presets.saveAs(name)
                    version++
                },
                label = { Text(stringResource(KMR.strings.reader_presets_update_named, name)) },
            )
            AssistChip(
                onClick = { confirmDelete = true },
                label = { Text(stringResource(KMR.strings.reader_presets_delete_named, name)) },
            )
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(KMR.strings.reader_presets_save_new)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(KMR.strings.reader_presets_new_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        // read the settings as they are on screen before the series' own preset is put away
                        val values = screenModel.presets.currentValues()
                        val name = newName.trim()
                        naming = false
                        newName = ""
                        scope.launch {
                            screenModel.presets.saveAs(name, values)
                            use(name)
                        }
                    },
                ) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }

    val toDelete = assigned
    if (confirmDelete && toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(KMR.strings.reader_presets_delete_confirm, toDelete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            screenModel.presets.endSession()
                            screenModel.presets.delete(toDelete)
                            version++
                        }
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
// KMK <--
