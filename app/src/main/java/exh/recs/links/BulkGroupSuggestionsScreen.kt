package exh.recs.links

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import exh.recs.links.LibraryGroupSuggestionPlanner.Confidence
import exh.recs.links.LibraryGroupSuggestionPlanner.GroupNames
import exh.recs.links.LibraryGroupSuggestionPlanner.Suggestion
import exh.recs.links.LibraryGroupSuggestionPlanner.Target
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.coroutines.resume

// KMK v0.8.25 -->
/**
 * "Group library automatically": every ungrouped library title next to the group it most likely belongs to, found by name the
 * way Migrate finds a title's equivalent. Exact matches are pre-checked, close ones are left for the user to choose, and
 * nothing is grouped until Group selected. Grouping goes through the same path as the group manager, so each title also
 * receives the group's rating, tracking, chapter progress and tracker entry.
 */
class BulkGroupSuggestionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel(tag = "bulk_group_suggestions") { LinkGroupManagementScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()
        val accepted = remember { mutableStateMapOf<Long, Boolean>() }
        val chosen = remember { mutableStateMapOf<Long, Target>() }
        var changing by remember { mutableStateOf<Suggestion?>(null) }
        var working by remember { mutableStateOf(false) }
        var grouped by remember { mutableStateOf<Int?>(null) }
        var failed by remember { mutableStateOf(false) }

        Scaffold(topBar = { scroll ->
            AppBar(title = stringResource(KMR.strings.bulk_group_title), navigateUp = navigator::pop, scrollBehavior = scroll)
        }) { padding ->
            when (val s = state) {
                LinkGroupManagementScreenModel.State.Loading ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LinkGroupManagementScreenModel.State.Error ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(KMR.strings.link_group_management_error), color = MaterialTheme.colorScheme.error)
                    }
                is LinkGroupManagementScreenModel.State.Success -> {
                    val groupNames = remember(s.allGroups) {
                        s.allGroups.map { group ->
                            GroupNames(
                                groupId = group.groupId,
                                label = group.primaryTitle,
                                names = (
                                    group.links.map { it.title } +
                                        group.members.flatMap { member -> listOfNotNull(member.manga?.title, member.manga?.ogTitle) }
                                    ).toSet(),
                            )
                        }
                    }
                    val suggestions = remember(s.addCandidates, groupNames) {
                        LibraryGroupSuggestionPlanner.plan(s.addCandidates.map { it.manga }, groupNames)
                    }
                    // source names follow Hide technical names, like the rest of the group manager
                    val hideNames = exh.util.rememberEvaluationModeEnabled()
                    val sourceNames = remember(s.addCandidates, hideNames) {
                        s.addCandidates.associate {
                            it.manga.id to if (hideNames) exh.util.EvaluationModeFormatter.sourceLabel(it.manga.source) else it.sourceName
                        }
                    }
                    fun targetOf(suggestion: Suggestion): Target? = chosen[suggestion.manga.id] ?: suggestion.target
                    fun isChecked(suggestion: Suggestion): Boolean =
                        targetOf(suggestion) != null && (accepted[suggestion.manga.id] ?: (suggestion.confidence == Confidence.EXACT))

                    val picks = suggestions.filter(::isChecked).map { it.manga to targetOf(it)!! }
                    val joins = picks.filter { it.second is Target.Group }
                        .groupBy({ (it.second as Target.Group).groupId }, { it.first })
                    val newGroups = picks.filter { it.second is Target.NewGroup }
                        .groupBy({ (it.second as Target.NewGroup).clusterKey }, { it.first })
                    val creatable = newGroups.filterValues { it.size >= 2 }
                    val selectedCount = joins.values.sumOf { it.size } + creatable.values.sumOf { it.size }
                    val leftAlone = newGroups.values.any { it.size < 2 }

                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(
                            stringResource(KMR.strings.bulk_group_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                        )
                        grouped?.let { count ->
                            Text(
                                stringResource(if (failed) KMR.strings.bulk_group_partial else KMR.strings.bulk_group_done, count),
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            )
                        }
                        if (leftAlone) {
                            Text(
                                stringResource(KMR.strings.bulk_group_needs_partner),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            )
                        }
                        if (suggestions.isEmpty()) {
                            Text(
                                stringResource(KMR.strings.bulk_group_none),
                                modifier = Modifier.padding(MaterialTheme.padding.medium),
                            )
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(suggestions, key = { it.manga.id }) { suggestion ->
                                SuggestionRow(
                                    suggestion = suggestion,
                                    sourceName = sourceNames[suggestion.manga.id].orEmpty(),
                                    target = targetOf(suggestion),
                                    checked = isChecked(suggestion),
                                    onToggle = { accepted[suggestion.manga.id] = it },
                                    onChange = { changing = suggestion },
                                )
                            }
                        }
                        Button(
                            enabled = selectedCount > 0 && !working,
                            onClick = {
                                working = true
                                failed = false
                                scope.launch {
                                    var done = 0
                                    for ((groupId, list) in joins) {
                                        val result = awaitMutation { cb -> model.addMangaToGroup(groupId, list, cb) }
                                        if (result.succeeded) done += list.size else failed = true
                                    }
                                    for (list in creatable.values) {
                                        val result = awaitMutation { cb -> model.createGroup(list, cb) }
                                        if (result.succeeded) done += list.size else failed = true
                                    }
                                    accepted.clear()
                                    chosen.clear()
                                    grouped = done
                                    working = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.padding.medium),
                        ) { Text(stringResource(KMR.strings.bulk_group_apply, selectedCount)) }
                    }

                    changing?.let { active ->
                        ChooseGroupDialog(
                            suggestion = active,
                            allGroups = groupNames,
                            onPick = { group ->
                                chosen[active.manga.id] = group
                                accepted[active.manga.id] = true
                                changing = null
                            },
                            onSkip = {
                                accepted[active.manga.id] = false
                                changing = null
                            },
                            onDismiss = { changing = null },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SuggestionRow(
        suggestion: Suggestion,
        sourceName: String,
        target: Target?,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
        onChange: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = target != null, role = Role.Checkbox, onValueChange = onToggle)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = target != null)
            Column(Modifier.weight(1f)) {
                Text(suggestion.manga.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                Text(sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (target) {
                        is Target.Group -> stringResource(KMR.strings.bulk_group_row_joins, target.label)
                        is Target.NewGroup -> stringResource(
                            KMR.strings.bulk_group_row_new,
                            target.partnerTitles.take(2).joinToString(", "),
                        )
                        null -> suggestion.alternatives.firstOrNull()
                            ?.let { stringResource(KMR.strings.bulk_group_row_maybe, it.label) }
                            ?: stringResource(KMR.strings.bulk_group_row_choose)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onChange) { Text(stringResource(KMR.strings.bulk_group_change)) }
        }
    }

    @Composable
    private fun ChooseGroupDialog(
        suggestion: Suggestion,
        allGroups: List<GroupNames>,
        onPick: (Target.Group) -> Unit,
        onSkip: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        val shown = remember(query, allGroups, suggestion) {
            if (query.isBlank()) {
                suggestion.alternatives +
                    listOfNotNull(suggestion.target as? Target.Group).filter { it !in suggestion.alternatives }
            } else {
                val needle = LibraryGroupSuggestionPlanner.normalize(query)
                allGroups.filter { group -> group.names.any { LibraryGroupSuggestionPlanner.normalize(it).contains(needle) } }
                    .map { Target.Group(it.groupId, it.label) }
            }
        }.take(30)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(KMR.strings.bulk_group_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(KMR.strings.bulk_group_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    shown.forEach { group ->
                        Text(
                            group.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(group) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onSkip) { Text(stringResource(KMR.strings.bulk_group_skip)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.link_group_management_cancel)) } },
        )
    }

    private suspend fun awaitMutation(start: ((LinkGroupMutationResult) -> Unit) -> Unit): LinkGroupMutationResult =
        suspendCancellableCoroutine { continuation -> start { result -> continuation.resume(result) } }
}
// KMK <--
