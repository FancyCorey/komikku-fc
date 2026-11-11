package exh.recs.links

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
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
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
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
        // KMK v0.8.25: the link is saved but sharing did not finish; this group can be retried
        var sharingIncompleteGroup by rememberSaveable { mutableStateOf<String?>(null) }
        // KMK v0.8.25: "Not the same manga" decisions that stopped an action, and the action to repeat if the user says they are
        var conflict by remember { mutableStateOf<Pair<List<LinkGroupConflict>, () -> Unit>?>(null) }

        fun handle(result: LinkGroupMutationResult, repeat: () -> Unit) {
            if (result.conflicts.isNotEmpty()) {
                conflict = result.conflicts to repeat
                operationFailed = false
                return
            }
            operationFailed = !result.succeeded
            undoId = result.undoEntryId
            sharingIncompleteGroup = result.groupId.takeIf { result.succeeded && result.sharing == LinkGroupSharing.INCOMPLETE }
        }

        Scaffold(topBar = { scroll ->
            AppBar(title = stringResource(KMR.strings.link_group_management_title), navigateUp = navigator::pop, scrollBehavior = scroll)
        }) { padding ->
            when (val s = state) {
                LinkGroupManagementScreenModel.State.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LinkGroupManagementScreenModel.State.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(KMR.strings.link_group_management_error), color = MaterialTheme.colorScheme.error) }
                is LinkGroupManagementScreenModel.State.Success -> {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        // KMK v0.8.25: find the right group for every ungrouped library title at once
                        if (focusedGroupId == null) {
                            TextButton(
                                onClick = { navigator.push(BulkGroupSuggestionsScreen()) },
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                            ) { Text(stringResource(KMR.strings.bulk_group_open)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { dialog = GroupDialog.Create(s.addCandidates) }) { Text(stringResource(KMR.strings.link_group_management_create_group)) }
                            undoId?.let { id ->
                                TextButton(onClick = {
                                    model.undo(id) { outcome ->
                                        undoId = null
                                        sharingIncompleteGroup = null
                                        operationFailed = outcome.result != exh.util.GroupUndoResult.RESTORED
                                    }
                                }) { Text(stringResource(KMR.strings.link_group_management_undo)) }
                            }
                        }
                        if (operationFailed) Text(stringResource(KMR.strings.link_group_management_operation_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
                        sharingIncompleteGroup?.let { groupId ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(KMR.strings.link_group_sharing_incomplete),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    model.syncGroup(groupId) { result -> handle(result) { model.syncGroup(groupId) { handle(it) {} } } }
                                }) { Text(stringResource(MR.strings.action_retry)) }
                            }
                        }
                        if (s.groups.isEmpty()) Text(stringResource(KMR.strings.link_group_management_empty), modifier = Modifier.padding(MaterialTheme.padding.medium))
                        LazyColumn {
                            items(s.groups, key = { it.groupId }) { group ->
                                GroupCard(
                                    group,
                                    s.allGroups,
                                    onAdd = { dialog = GroupDialog.Add(group.groupId, s.addCandidates) },
                                    onSync = {
                                        fun run() {
                                            model.syncGroup(group.groupId) { result -> handle(result, ::run) }
                                        }
                                        run()
                                    },
                                    onMerge = { dialog = GroupDialog.Merge(group, s.allGroups.filter { it.groupId != group.groupId }) },
                                    onUngroup = { dialog = GroupDialog.ConfirmUngroup(group) },
                                    onMove = { member -> dialog = GroupDialog.Move(member, s.allGroups.filter { it.groupId != group.groupId }) },
                                    onRemove = { member ->
                                        model.ungroupMember(member.link.source, member.link.url) { result -> handle(result) {} }
                                    },
                                    onSetPrimary = { member ->
                                        model.setPrimaryVersion(group.groupId, member.link.source, member.link.url) { result -> handle(result) {} }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        dialog?.let { active ->
            GroupManagerDialog(active, model, onDismiss = { dialog = null }, onResult = { result, repeat ->
                handle(result, repeat)
                dialog = null
            })
        }
        conflict?.let { (pairs, repeat) ->
            ConflictDialog(
                pairs,
                onSameManga = {
                    conflict = null
                    model.confirmSameManga(pairs, repeat)
                },
                onKeepApart = { conflict = null },
            )
        }
    }

    @Composable
    private fun ConflictDialog(conflicts: List<LinkGroupConflict>, onSameManga: () -> Unit, onKeepApart: () -> Unit) {
        AlertDialog(
            onDismissRequest = onKeepApart,
            title = { Text(stringResource(KMR.strings.link_group_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(KMR.strings.link_group_conflict_message))
                    LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = MaterialTheme.padding.small)) {
                        items(conflicts) { item ->
                            Text(
                                stringResource(KMR.strings.link_group_conflict_pair, item.firstTitle, item.secondTitle),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onSameManga) { Text(stringResource(KMR.strings.identity_review_confirm)) } },
            dismissButton = { TextButton(onClick = onKeepApart) { Text(stringResource(KMR.strings.link_group_conflict_keep_apart)) } },
        )
    }

    @Composable
    private fun GroupManagerDialog(
        dialog: GroupDialog,
        model: LinkGroupManagementScreenModel,
        onDismiss: () -> Unit,
        onResult: (LinkGroupMutationResult, () -> Unit) -> Unit,
    ) {
        var selected by remember(dialog) { mutableStateOf(emptySet<Pair<Long, String>>()) }
        var destination by remember(dialog) { mutableStateOf<String?>(null) }
        val title = when (dialog) {
            is GroupDialog.Create -> stringResource(KMR.strings.link_group_management_create_group)
            is GroupDialog.Add -> stringResource(KMR.strings.link_group_management_add_to_group)
            is GroupDialog.Merge -> stringResource(KMR.strings.link_group_management_merge_groups)
            is GroupDialog.Move -> stringResource(KMR.strings.link_group_management_move_member)
            is GroupDialog.ConfirmUngroup -> stringResource(KMR.strings.link_group_management_ungroup)
        }
        // KMK v0.8.25: the action button says what it does and only works once a valid choice is made
        val confirmLabel = when (dialog) {
            is GroupDialog.Merge -> stringResource(KMR.strings.link_group_management_merge_groups)
            is GroupDialog.Move -> stringResource(KMR.strings.link_group_management_move_member)
            is GroupDialog.ConfirmUngroup -> stringResource(KMR.strings.link_group_management_ungroup)
            is GroupDialog.Create -> stringResource(KMR.strings.link_group_management_create_group)
            is GroupDialog.Add -> stringResource(KMR.strings.link_group_management_add_to_group)
        }
        val canConfirm = when (dialog) {
            is GroupDialog.Create -> selected.size >= 2
            is GroupDialog.Add -> selected.isNotEmpty()
            is GroupDialog.Merge, is GroupDialog.Move -> destination != null
            is GroupDialog.ConfirmUngroup -> true
        }
        lateinit var confirm: () -> Unit
        confirm = {
            val repeat = { confirm() }
            when (dialog) {
                is GroupDialog.Create -> model.createGroup(dialog.candidates.map { it.manga }.filter { (it.source to it.url) in selected }) { onResult(it, repeat) }
                is GroupDialog.Add -> model.addMangaToGroup(dialog.groupId, dialog.candidates.map { it.manga }.filter { (it.source to it.url) in selected }) { onResult(it, repeat) }
                is GroupDialog.Merge -> destination?.let { model.mergeGroups(dialog.group.groupId, it) { result -> onResult(result, repeat) } }
                is GroupDialog.Move -> destination?.let { model.moveMember(dialog.member.link.source, dialog.member.link.url, it) { result -> onResult(result, repeat) } }
                is GroupDialog.ConfirmUngroup -> model.ungroupGroup(dialog.group.groupId) { onResult(it, repeat) }
            }
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
            when (dialog) {
                is GroupDialog.Create -> CandidateList(dialog.candidates, selected) { selected = selected.toggle(it) }
                is GroupDialog.Add -> CandidateList(dialog.candidates, selected) { selected = selected.toggle(it) }
                is GroupDialog.Merge -> LinkGroupDestinationList(dialog.destinations, destination) { destination = it }
                is GroupDialog.Move -> LinkGroupDestinationList(dialog.destinations, destination) { destination = it }
                is GroupDialog.ConfirmUngroup -> Text(stringResource(KMR.strings.link_group_management_ungroup_confirm))
            }
        }, confirmButton = {
            TextButton(onClick = { confirm() }, enabled = canConfirm) { Text(confirmLabel) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.link_group_management_cancel)) } })
    }

    @Composable
    private fun CandidateList(candidates: List<LinkGroupCandidate>, selected: Set<Pair<Long, String>>, onToggle: (Pair<Long, String>) -> Unit) {
        LazyColumn(Modifier.heightIn(max = DIALOG_LIST_MAX_HEIGHT)) {
            items(candidates, key = { "${it.manga.source}|${it.manga.url}" }) { candidate ->
                val key = candidate.manga.source to candidate.manga.url
                SelectionRow(candidate, key in selected) { onToggle(key) }
            }
        }
    }

    @Composable
    private fun SelectionRow(candidate: LinkGroupCandidate, checked: Boolean, onToggle: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .toggleable(value = checked, role = Role.Checkbox) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, onCheckedChange = null)
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
    private fun GroupCard(
        group: LinkGroup,
        groups: List<LinkGroup>,
        onAdd: () -> Unit,
        onSync: () -> Unit,
        onMerge: () -> Unit,
        onUngroup: () -> Unit,
        onMove: (LinkGroupMember) -> Unit,
        onRemove: (LinkGroupMember) -> Unit,
        onSetPrimary: (LinkGroupMember) -> Unit,
    ) {
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
                    // KMK v0.8.25: the group's own actions live in a menu so its titles stay the main content
                    OverflowMenu(stringResource(KMR.strings.link_group_management_group_actions)) { close ->
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_add_to_group)) }, onClick = {
                            close()
                            onAdd()
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_sync)) }, onClick = {
                            close()
                            onSync()
                        })
                        if (groups.size > 1) {
                            DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_merge_groups)) }, onClick = {
                                close()
                                onMerge()
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_ungroup)) }, onClick = {
                            close()
                            onUngroup()
                        })
                    }
                }
                if (expanded) {
                    group.members.forEach { member ->
                        val name = member.manga?.title?.takeIf { it.isNotBlank() } ?: member.link.title
                        val isPrimary = member == group.primary
                        Row(Modifier.fillMaxWidth().padding(top = MaterialTheme.padding.extraSmall), verticalAlignment = Alignment.CenterVertically) {
                            member.manga?.let { MangaCover.Book(data = it.asMangaCover(), modifier = Modifier.width(36.dp).height(52.dp)) }
                            Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                                Text(name)
                                Text(
                                    if (exh.util.rememberEvaluationModeEnabled()) exh.util.EvaluationModeFormatter.sourceLabel(member.link.source) else member.sourceName,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // KMK v0.8.25: the version that gives the group its title and cover
                            if (isPrimary) {
                                Text(
                                    text = stringResource(KMR.strings.linked_version_list_primary_marker),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            OverflowMenu(stringResource(KMR.strings.link_group_management_member_actions, name)) { close ->
                                if (!isPrimary && group.members.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(KMR.strings.linked_version_list_set_primary)) },
                                        onClick = {
                                            close()
                                            onSetPrimary(member)
                                        },
                                    )
                                }
                                if (groups.size > 1) {
                                    DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_move_member)) }, onClick = {
                                        close()
                                        onMove(member)
                                    })
                                }
                                DropdownMenuItem(text = { Text(stringResource(KMR.strings.link_group_management_remove_link)) }, onClick = {
                                    close()
                                    onRemove(member)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OverflowMenu(description: String, items: @Composable (close: () -> Unit) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = description)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items { open = false }
            }
        }
    }
}

private val DIALOG_LIST_MAX_HEIGHT = 360.dp

/** KMK v0.8.25: one destination group per row, with its cover and name; the chosen one is marked and announced as selected. */
@Composable
internal fun LinkGroupDestinationList(destinations: List<LinkGroup>, chosen: String?, onChoose: (String) -> Unit) {
    LazyColumn(Modifier.heightIn(max = DIALOG_LIST_MAX_HEIGHT)) {
        items(destinations, key = { it.groupId }) { group ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .selectable(selected = group.groupId == chosen, role = Role.RadioButton) { onChoose(group.groupId) }
                    .padding(vertical = MaterialTheme.padding.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = group.groupId == chosen, onClick = null)
                group.primary?.manga?.let { manga ->
                    MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.padding(start = MaterialTheme.padding.small).width(32.dp).height(46.dp))
                }
                Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                    Text(group.primaryTitle)
                    Text(
                        pluralStringResource(KMR.plurals.link_group_management_source_count, group.links.size, group.links.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
