package exh.recs.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import exh.util.GroupUndoJournal
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

class LinkGroupManagementScreen(private val focusedGroupId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel(tag = "link_group_management_${focusedGroupId.orEmpty()}") {
            LinkGroupManagementScreenModel(focusedGroupId = focusedGroupId)
        }
        val state by model.state.collectAsState()
        var dialog by remember { mutableStateOf<GroupDialog?>(null) }
        var undoId by rememberSaveable { mutableStateOf<String?>(null) }
        var operationFailed by rememberSaveable { mutableStateOf(false) }
        Scaffold(topBar = { scroll ->
            AppBar(title = stringResource(KMR.strings.link_group_management_title), navigateUp = navigator::pop, scrollBehavior = scroll)
        }) { padding ->
            when (val s = state) {
                LinkGroupManagementScreenModel.State.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LinkGroupManagementScreenModel.State.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(KMR.strings.link_group_management_error), color = MaterialTheme.colorScheme.error) }
                is LinkGroupManagementScreenModel.State.Success -> {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { dialog = GroupDialog.Create(s.addCandidates) }) { Text(stringResource(KMR.strings.link_group_management_create_group)) }
                            undoId?.let { id ->
                                TextButton(onClick = {
                                    model.undo(id) { outcome ->
                                        undoId = null
                                        operationFailed = outcome.result != exh.util.GroupUndoResult.RESTORED
                                    }
                                }) { Text(stringResource(KMR.strings.link_group_management_undo)) }
                            }
                        }
                        if (operationFailed) Text(stringResource(KMR.strings.link_group_management_operation_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
                        if (s.groups.isEmpty()) Text(stringResource(KMR.strings.link_group_management_empty), modifier = Modifier.padding(MaterialTheme.padding.medium))
                        LazyColumn {
                            items(s.groups, key = { it.groupId }) { group ->
                                GroupCard(
                                    group,
                                    s.allGroups,
                                    onAdd = { dialog = GroupDialog.Add(group.groupId, s.addCandidates) },
                                    onMerge = { dialog = GroupDialog.Merge(group, s.allGroups.filter { it.groupId != group.groupId }) },
                                    onUngroup = { dialog = GroupDialog.ConfirmUngroup(group) },
                                    onMove = { member -> dialog = GroupDialog.Move(member, s.allGroups.filter { it.groupId != group.groupId }) },
                                    onRemove = { member ->
                                        model.ungroupMember(member.link.source, member.link.url) { result ->
                                            operationFailed = !result.succeeded
                                            undoId = result.undoEntryId
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        dialog?.let { active ->
            GroupManagerDialog(active, model, onDismiss = { dialog = null }, onResult = { result ->
                operationFailed = !result.succeeded
                undoId = result.undoEntryId
                dialog = null
            })
        }
    }

    @Composable
    private fun GroupManagerDialog(dialog: GroupDialog, model: LinkGroupManagementScreenModel, onDismiss: () -> Unit, onResult: (LinkGroupMutationResult) -> Unit) {
        var selected by remember(dialog) { mutableStateOf(emptySet<Pair<Long, String>>()) }
        val title = when (dialog) {
            is GroupDialog.Create -> stringResource(KMR.strings.link_group_management_create_group)
            is GroupDialog.Add -> stringResource(KMR.strings.link_group_management_add_to_group)
            is GroupDialog.Merge -> stringResource(KMR.strings.link_group_management_merge_groups)
            is GroupDialog.Move -> stringResource(KMR.strings.link_group_management_move_member)
            is GroupDialog.ConfirmUngroup -> stringResource(KMR.strings.link_group_management_ungroup)
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
            Column {
                when (dialog) {
                    is GroupDialog.Create -> dialog.candidates.forEach { candidate -> SelectionRow(candidate, (candidate.manga.source to candidate.manga.url) in selected) { selected = selected.toggle(candidate.manga.source to candidate.manga.url) } }
                    is GroupDialog.Add -> dialog.candidates.forEach { candidate -> SelectionRow(candidate, (candidate.manga.source to candidate.manga.url) in selected) { selected = selected.toggle(candidate.manga.source to candidate.manga.url) } }
                    is GroupDialog.Merge -> dialog.destinations.forEach { group -> TextButton(onClick = { selected = setOf(-group.groupId.hashCode().toLong() to group.groupId) }) { Text(group.primaryTitle) } }
                    is GroupDialog.Move -> dialog.destinations.forEach { group -> TextButton(onClick = { selected = setOf(-group.groupId.hashCode().toLong() to group.groupId) }) { Text(group.primaryTitle) } }
                    is GroupDialog.ConfirmUngroup -> Text(stringResource(KMR.strings.link_group_management_ungroup_confirm))
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                when (dialog) {
                    is GroupDialog.Create -> model.createGroup(dialog.candidates.map { it.manga }.filter { (it.source to it.url) in selected }, onResult)
                    is GroupDialog.Add -> model.addMangaToGroup(dialog.groupId, dialog.candidates.map { it.manga }.filter { (it.source to it.url) in selected }, onResult)
                    is GroupDialog.Merge -> selected.firstOrNull()?.second?.let { model.mergeGroups(dialog.group.groupId, it, onResult) }
                    is GroupDialog.Move -> selected.firstOrNull()?.second?.let { model.moveMember(dialog.member.link.source, dialog.member.link.url, it, onResult) }
                    is GroupDialog.ConfirmUngroup -> model.ungroupGroup(dialog.group.groupId, onResult)
                }
            }) { Text(stringResource(KMR.strings.link_group_management_done)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.link_group_management_cancel)) } })
    }

    @Composable
    private fun SelectionRow(candidate: LinkGroupCandidate, checked: Boolean, onToggle: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = { onToggle() })
            MangaCover.Book(data = candidate.manga.asMangaCover(), modifier = Modifier.width(32.dp).height(46.dp))
            Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                Text(candidate.manga.title)
                Text(
                    if (exh.util.rememberEvaluationModeEnabled()) exh.util.EvaluationModeFormatter.sourceLabel(candidate.manga.source) else candidate.sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun GroupCard(group: LinkGroup, groups: List<LinkGroup>, onAdd: () -> Unit, onMerge: () -> Unit, onUngroup: () -> Unit, onMove: (LinkGroupMember) -> Unit, onRemove: (LinkGroupMember) -> Unit) {
        var expanded by rememberSaveable(group.groupId) { mutableStateOf(false) }
        val toggleAction = stringResource(
            if (expanded) KMR.strings.accessibility_collapse_link_group else KMR.strings.accessibility_expand_link_group,
        )
        val expandedState = stringResource(
            if (expanded) KMR.strings.accessibility_state_expanded else KMR.strings.accessibility_state_collapsed,
        )
        Card(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall)) {
            Column(Modifier.padding(MaterialTheme.padding.small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    group.primary?.manga?.let { manga -> MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.width(54.dp).height(76.dp)) }
                    Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                        Text(group.primaryTitle, style = MaterialTheme.typography.titleSmall)
                        Text(pluralStringResource(KMR.plurals.link_group_management_source_count, group.links.size, group.links.size), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "−" else "+",
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = toggleAction
                                stateDescription = expandedState
                            },
                        )
                    }
                }
                if (expanded) {
                    TextButton(onClick = onAdd) { Text(stringResource(KMR.strings.link_group_management_add_to_group)) }
                    if (groups.size > 1) TextButton(onClick = onMerge) { Text(stringResource(KMR.strings.link_group_management_merge_groups)) }
                    TextButton(onClick = onUngroup) { Text(stringResource(KMR.strings.link_group_management_ungroup)) }
                    group.members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            member.manga?.let { MangaCover.Book(data = it.asMangaCover(), modifier = Modifier.width(36.dp).height(52.dp)) }
                            Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                                Text(member.manga?.title ?: member.link.title)
                                Text(
                                    if (exh.util.rememberEvaluationModeEnabled()) exh.util.EvaluationModeFormatter.sourceLabel(member.link.source) else member.sourceName,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onMove(member) }) { Text(stringResource(KMR.strings.link_group_management_move_member)) }
                            TextButton(onClick = { onRemove(member) }) { Text(stringResource(KMR.strings.link_group_management_remove_link)) }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface GroupDialog {
    data class Create(val candidates: List<LinkGroupCandidate>) : GroupDialog
    data class Add(val groupId: String, val candidates: List<LinkGroupCandidate>) : GroupDialog
    data class Merge(val group: LinkGroup, val destinations: List<LinkGroup>) : GroupDialog
    data class Move(val member: LinkGroupMember, val destinations: List<LinkGroup>) : GroupDialog
    data class ConfirmUngroup(val group: LinkGroup) : GroupDialog
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
