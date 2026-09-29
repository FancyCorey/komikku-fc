package exh.recs.links

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import exh.util.GroupJournalActionType
import exh.util.GroupJournalEntry
import exh.util.GroupLinkSnapshot
import exh.util.GroupPrimarySnapshot
import exh.util.GroupUndoJournal
import exh.util.GroupUndoOutcome
import exh.util.GroupUndoService
import exh.util.RatedLinkKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.RestoreCrossSourceGroupState
import tachiyomi.domain.taste.model.CrossSourceGroupPrimary
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.UUID

@Immutable
data class LinkGroupMember(
    val link: CrossSourceMangaLink,
    val manga: Manga?,
    val sourceName: String,
)

@Immutable
data class LinkGroupCandidate(val manga: Manga, val sourceName: String)

/** A link group is the set of rows sharing one [CrossSourceMangaLink.groupId]. */
@Immutable
data class LinkGroup(
    val groupId: String,
    val links: List<CrossSourceMangaLink>,
    val members: List<LinkGroupMember> = emptyList(),
    val primary: LinkGroupMember? = null,
) {
    val primaryTitle: String
        get() = primary?.manga?.title ?: primary?.link?.title ?: links.firstOrNull()?.title ?: groupId
}

data class LinkGroupMutationResult(
    val succeeded: Boolean,
    val undoEntryId: String? = null,
)

// KMK --> v0.8.0: optional focusedGroupId scopes visible results to a single group. The model still
// reads all groups so a member can be moved without changing or folding any other membership.
class LinkGroupManagementScreenModel(
    private val focusedGroupId: String? = null,
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getCrossSourceGroupPrimary: GetCrossSourceGroupPrimary = Injekt.get(),
    private val restoreCrossSourceGroupState: RestoreCrossSourceGroupState = Injekt.get(),
    private val groupUndoService: GroupUndoService = GroupUndoService(),
) : StateScreenModel<LinkGroupManagementScreenModel.State>(State.Loading) {
    // KMK <--

    init {
        screenModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val links = getCrossSourceMangaLinks.awaitAll()
            val primaries = getCrossSourceGroupPrimary.awaitAll().associateBy { it.groupId }
            val members = links.map { link ->
                val manga = try {
                    mangaRepository.getMangaByUrlAndSourceId(link.url, link.source)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val sourceName = sourceManager.get(link.source)?.name ?: link.source.toString()
                LinkGroupMember(link, manga, sourceName)
            }
            val membersByGroup = members.groupBy { it.link.groupId }
            val allGroups = links
                .groupBy { it.groupId }
                .map { (groupId, groupLinks) ->
                    val groupMembers = membersByGroup[groupId].orEmpty()
                    val primaryRow = primaries[groupId]
                    val primaryMember = primaryRow?.let { row ->
                        groupMembers.firstOrNull { it.link.source == row.source && it.link.url == row.url }
                    } ?: groupMembers.firstOrNull()
                    LinkGroup(groupId, groupLinks, groupMembers, primaryMember)
                }
                .sortedBy { it.primaryTitle.lowercase(Locale.ROOT) }
            val favorites = mangaRepository.getFavorites()
            val groupedKeys = links.map { it.source to it.url }.toSet()
            val candidates = favorites
                .filterNot { (it.source to it.url) in groupedKeys }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
                .map { manga -> LinkGroupCandidate(manga, sourceManager.get(manga.source)?.name ?: manga.source.toString()) }
            val visibleGroups = focusedGroupId?.let { id -> allGroups.filter { it.groupId == id } } ?: allGroups
            mutableState.value = State.Success(visibleGroups, allGroups, candidates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = State.Error(e)
        }
    }

    fun reload() {
        mutableState.value = State.Loading
        screenModelScope.launch { load() }
    }

    /** Adds one currently ungrouped library title to an existing group. */
    fun addMangaToGroup(groupId: String, manga: Manga, onResult: (LinkGroupMutationResult) -> Unit = {}) =
        addMangaToGroup(groupId, listOf(manga), onResult)

    /** Adds every explicitly selected, currently ungrouped title to an existing group atomically. */
    fun addMangaToGroup(groupId: String, manga: List<Manga>, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            if (links.none { it.groupId == groupId }) return@mutate null
            val selected = manga.distinctBy { it.source to it.url }
            if (selected.isEmpty() || selected.any { candidate -> links.any { it.source == candidate.source && it.url == candidate.url } }) return@mutate null
            val now = System.currentTimeMillis()
            val newLinks = selected.map { candidate ->
                CrossSourceMangaLink(candidate.source, candidate.url, groupId, candidate.title, now, now)
            }
            commit(
                actionType = GroupJournalActionType.ADD_MEMBER,
                linkUpserts = newLinks,
                linkDeletes = emptyList(),
                primaryUpserts = emptyList(),
                primaryDeletes = emptyList(),
                linksBefore = links,
                primariesBefore = getCrossSourceGroupPrimary.awaitAll(),
            )
        }
    }

    /** Creates a new group from exactly the distinct, currently ungrouped selected manga. */
    fun createGroup(manga: List<Manga>, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val selected = manga.distinctBy { it.source to it.url }
            if (selected.size < 2) return@mutate null
            val links = getCrossSourceMangaLinks.awaitAll()
            if (selected.any { candidate -> links.any { it.source == candidate.source && it.url == candidate.url } }) {
                return@mutate null
            }
            val groupId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val writes = selected.map { candidate ->
                CrossSourceMangaLink(candidate.source, candidate.url, groupId, candidate.title, now, now)
            }
            val first = selected.first()
            commit(
                actionType = GroupJournalActionType.ADD_MEMBER,
                linkUpserts = writes,
                linkDeletes = emptyList(),
                primaryUpserts = listOf(CrossSourceGroupPrimary(groupId, first.source, first.url, now)),
                primaryDeletes = emptyList(),
                linksBefore = links,
                primariesBefore = getCrossSourceGroupPrimary.awaitAll(),
            )
        }
    }

    /** Moves exactly one member. Other members of both groups keep their current group IDs. */
    fun moveMember(source: Long, url: String, destinationGroupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val member = links.singleOrNull { it.source == source && it.url == url } ?: return@mutate null
            if (member.groupId == destinationGroupId || links.none { it.groupId == destinationGroupId }) return@mutate null
            val primaries = getCrossSourceGroupPrimary.awaitAll()
            val primaryBefore = primaries.associateBy { it.groupId }
            val oldGroupRemaining = links.filter { it.groupId == member.groupId && (it.source != source || it.url != url) }
            val oldPrimary = primaryBefore[member.groupId]
            val primaryUpserts = mutableListOf<CrossSourceGroupPrimary>()
            val primaryDeletes = mutableListOf<String>()
            if (oldPrimary?.source == source && oldPrimary.url == url) {
                val replacement = oldGroupRemaining.firstOrNull()
                if (replacement == null) {
                    primaryDeletes += member.groupId
                } else {
                    primaryUpserts += CrossSourceGroupPrimary(
                        member.groupId,
                        replacement.source,
                        replacement.url,
                        System.currentTimeMillis(),
                    )
                }
            }
            commit(
                actionType = GroupJournalActionType.MOVE_MEMBER,
                linkUpserts = listOf(member.copy(groupId = destinationGroupId, updatedAt = System.currentTimeMillis())),
                linkDeletes = emptyList(),
                primaryUpserts = primaryUpserts,
                primaryDeletes = primaryDeletes,
                linksBefore = links,
                primariesBefore = primaries,
            )
        }
    }

    /** Removes only the requested member; it never unlinks its former group peers. */
    fun ungroupMember(source: Long, url: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val member = links.singleOrNull { it.source == source && it.url == url } ?: return@mutate null
            val primaries = getCrossSourceGroupPrimary.awaitAll()
            val primary = primaries.singleOrNull { it.groupId == member.groupId }
            val remaining = links.filter { it.groupId == member.groupId && (it.source != source || it.url != url) }
            val primaryUpserts = mutableListOf<CrossSourceGroupPrimary>()
            val primaryDeletes = mutableListOf<String>()
            if (primary?.source == source && primary.url == url) {
                val replacement = remaining.firstOrNull()
                if (replacement == null) {
                    primaryDeletes += member.groupId
                } else {
                    primaryUpserts += CrossSourceGroupPrimary(
                        member.groupId,
                        replacement.source,
                        replacement.url,
                        System.currentTimeMillis(),
                    )
                }
            }
            commit(
                actionType = GroupJournalActionType.REMOVE_FROM_GROUP,
                linkUpserts = emptyList(),
                linkDeletes = listOf(source to url),
                primaryUpserts = primaryUpserts,
                primaryDeletes = primaryDeletes,
                linksBefore = links,
                primariesBefore = primaries,
            )
        }
    }

    /** Explicitly merges the source group into the destination; no other group is read into the write set. */
    fun mergeGroups(sourceGroupId: String, destinationGroupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            if (sourceGroupId == destinationGroupId) return@mutate null
            val sourceMembers = links.filter { it.groupId == sourceGroupId }
            if (sourceMembers.isEmpty() || links.none { it.groupId == destinationGroupId }) return@mutate null
            val primaries = getCrossSourceGroupPrimary.awaitAll()
            val primaryMap = primaries.associateBy { it.groupId }
            val sourcePrimary = primaryMap[sourceGroupId]
            val destinationPrimary = primaryMap[destinationGroupId]
            val destinationPrimaryWrites = if (destinationPrimary == null && sourcePrimary != null) {
                listOf(CrossSourceGroupPrimary(destinationGroupId, sourcePrimary.source, sourcePrimary.url, System.currentTimeMillis()))
            } else {
                emptyList()
            }
            commit(
                actionType = GroupJournalActionType.MERGE,
                linkUpserts = sourceMembers.map { it.copy(groupId = destinationGroupId, updatedAt = System.currentTimeMillis()) },
                linkDeletes = emptyList(),
                primaryUpserts = destinationPrimaryWrites,
                primaryDeletes = if (sourcePrimary != null) listOf(sourceGroupId) else emptyList(),
                linksBefore = links,
                primariesBefore = primaries,
            )
        }
    }

    /** Ungroups every member only when the user explicitly chooses the group-level action. */
    fun ungroupGroup(groupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val members = links.filter { it.groupId == groupId }
            if (members.isEmpty()) return@mutate null
            val primaries = getCrossSourceGroupPrimary.awaitAll()
            val hasPrimary = primaries.any { it.groupId == groupId }
            commit(
                actionType = GroupJournalActionType.UNGROUP,
                linkUpserts = emptyList(),
                linkDeletes = members.map { it.source to it.url },
                primaryUpserts = emptyList(),
                primaryDeletes = if (hasPrimary) listOf(groupId) else emptyList(),
                linksBefore = links,
                primariesBefore = primaries,
            )
        }
    }

    fun undo(entryId: String, onResult: (GroupUndoOutcome) -> Unit = {}) {
        screenModelScope.launch {
            val outcome = groupUndoService.undo(entryId)
            load()
            onResult(outcome)
        }
    }

    private fun mutate(
        onResult: (LinkGroupMutationResult) -> Unit,
        action: suspend () -> GroupJournalEntry?,
    ) {
        screenModelScope.launch {
            val result = try {
                val entry = action()
                LinkGroupMutationResult(succeeded = entry != null, undoEntryId = entry?.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LinkGroupMutationResult(succeeded = false)
            }
            load()
            onResult(result)
        }
    }

    private suspend fun commit(
        actionType: GroupJournalActionType,
        linkUpserts: List<CrossSourceMangaLink>,
        linkDeletes: List<Pair<Long, String>>,
        primaryUpserts: List<CrossSourceGroupPrimary>,
        primaryDeletes: List<String>,
        linksBefore: List<CrossSourceMangaLink>,
        primariesBefore: List<CrossSourceGroupPrimary>,
    ): GroupJournalEntry? {
        val touchedKeys = (
            linkUpserts.map { RatedLinkKey(it.source, it.url) } +
                linkDeletes.map { (source, url) -> RatedLinkKey(source, url) }
            ).toSet()
        if (touchedKeys.isEmpty()) return null
        val priorLinks = linksBefore.associateBy { RatedLinkKey(it.source, it.url) }
        val expectedLinks = linkUpserts.associateBy { RatedLinkKey(it.source, it.url) }
        val previousLinks = touchedKeys.associateWith { key -> priorLinks[key]?.let(GroupLinkSnapshot::from) }
        val expectedPostLinks = touchedKeys.associateWith { key -> expectedLinks[key]?.let(GroupLinkSnapshot::from) }
        val touchedGroupIds = (primaryUpserts.map { it.groupId } + primaryDeletes).toSet()
        val priorPrimaries = primariesBefore.associateBy { it.groupId }
        val expectedPrimaries = primaryUpserts.associateBy { it.groupId }
        val previousPrimaries = touchedGroupIds.associateWith { id -> priorPrimaries[id]?.let(GroupPrimarySnapshot::from) }
        val expectedPostPrimaries = touchedGroupIds.associateWith { id -> expectedPrimaries[id]?.let(GroupPrimarySnapshot::from) }
        val entry = GroupJournalEntry(
            id = GroupJournalEntry.newId(),
            timestamp = System.currentTimeMillis(),
            actionType = actionType,
            touchedKeys = touchedKeys,
            previousLinks = previousLinks,
            expectedPostLinks = expectedPostLinks,
            touchedGroupIds = touchedGroupIds,
            previousPrimaries = previousPrimaries,
            expectedPostPrimaries = expectedPostPrimaries,
        )
        restoreCrossSourceGroupState.await(linkUpserts, linkDeletes, primaryUpserts, primaryDeletes)
        GroupUndoJournal.record(entry)
        return entry
    }

    sealed interface State {
        data object Loading : State
        data class Error(val error: Throwable) : State

        @Immutable
        data class Success(
            val groups: List<LinkGroup>,
            val allGroups: List<LinkGroup>,
            val addCandidates: List<LinkGroupCandidate>,
        ) : State
    }
}
