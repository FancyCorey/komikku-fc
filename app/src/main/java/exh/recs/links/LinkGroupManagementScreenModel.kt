package exh.recs.links

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import exh.recs.matching.ConfirmedGroupJoinSynchronizer
import exh.recs.matching.GroupJoinConfirmations
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
import tachiyomi.domain.taste.model.CrossSourceIdentityPair
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey
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
        // KMK v0.8.25: an empty title falls through too, so a group is never shown without a name (seen on the group list and in
        // Move and Merge)
        get() = listOfNotNull(primary?.manga?.title, primary?.link?.title, *links.map { it.title }.toTypedArray())
            .firstOrNull { it.isNotBlank() } ?: groupId
}

data class LinkGroupMutationResult(
    val succeeded: Boolean,
    val undoEntryId: String? = null,
    // KMK v0.8.25: the link and what was shared afterwards are reported separately
    val groupId: String? = null,
    val sharing: LinkGroupSharing = LinkGroupSharing.NOT_NEEDED,
    // pairs the user marked "Not the same manga"; when not empty nothing was linked (or, for sharing, nothing was shared with them)
    val conflicts: List<LinkGroupConflict> = emptyList(),
)

// KMK v0.8.25 -->
enum class LinkGroupSharing { NOT_NEEDED, COMPLETE, INCOMPLETE }

@Immutable
data class LinkGroupConflict(val pair: CrossSourceIdentityPair, val firstTitle: String, val secondTitle: String)
// KMK <--

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
    // KMK v0.8.25: confirms and syncs tracking, rating and tracker entries for titles that join a group
    private val joinSynchronizer: Lazy<ConfirmedGroupJoinSynchronizer> = lazy { ConfirmedGroupJoinSynchronizer() },
    // KMK v0.8.25: "Not the same manga" checks, and taking back the confirmations grouping made when the grouping is undone
    private val groupConfirmations: Lazy<GroupJoinConfirmations> = lazy { GroupJoinConfirmations() },
    // KMK v0.8.25: the same primary-version write the rated lists use
    private val setCrossSourceGroupPrimary: Lazy<tachiyomi.domain.taste.interactor.SetCrossSourceGroupPrimary> = lazy { Injekt.get() },
    private val sourcePreferences: Lazy<eu.kanade.domain.source.service.SourcePreferences> = lazy { Injekt.get() },
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
                    // KMK v0.8.25: the primary version first, then the others by source name, so the order never jumps around
                    val ordered = groupMembers.sortedWith(
                        compareBy<LinkGroupMember>({ it != primaryMember }, { it.sourceName.lowercase(Locale.ROOT) }, { it.link.url }),
                    )
                    LinkGroup(groupId, groupLinks, ordered, primaryMember)
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
            if (links.none { it.groupId == groupId }) return@mutate failed()
            val selected = manga.distinctBy { it.source to it.url }
            if (selected.isEmpty() || selected.any { candidate -> links.any { it.source == candidate.source && it.url == candidate.url } }) return@mutate failed()
            conflicts(selected.map { it.key() }, links.filter { it.groupId == groupId }.map { it.key() }, links, selected)
                .takeIf { it.isNotEmpty() }?.let { return@mutate LinkGroupMutationResult(false, groupId = groupId, conflicts = it) }
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
            ).joined(groupId, newLinks.map { link -> link.source to link.url })
        }
    }

    /** Creates a new group from exactly the distinct, currently ungrouped selected manga. */
    fun createGroup(manga: List<Manga>, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val selected = manga.distinctBy { it.source to it.url }
            if (selected.size < 2) return@mutate failed()
            val links = getCrossSourceMangaLinks.awaitAll()
            if (selected.any { candidate -> links.any { it.source == candidate.source && it.url == candidate.url } }) {
                return@mutate failed()
            }
            conflicts(selected.map { it.key() }, emptyList(), links, selected)
                .takeIf { it.isNotEmpty() }?.let { return@mutate LinkGroupMutationResult(false, conflicts = it) }
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
            ).joined(groupId, writes.map { link -> link.source to link.url })
        }
    }

    /** Moves exactly one member. Other members of both groups keep their current group IDs. */
    fun moveMember(source: Long, url: String, destinationGroupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val member = links.singleOrNull { it.source == source && it.url == url } ?: return@mutate failed()
            if (member.groupId == destinationGroupId || links.none { it.groupId == destinationGroupId }) return@mutate failed()
            conflicts(listOf(member.key()), links.filter { it.groupId == destinationGroupId }.map { it.key() }, links)
                .takeIf { it.isNotEmpty() }?.let { return@mutate LinkGroupMutationResult(false, groupId = destinationGroupId, conflicts = it) }
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
            ).also { entry ->
                // it left its old group, so what grouping confirmed between it and that group is taken back
                if (entry != null) takeBack(member.key(), oldGroupRemaining.map { it.key() }, entry.id)
            }.joined(destinationGroupId, listOf(source to url))
        }
    }

    /** Removes only the requested member; it never unlinks its former group peers. */
    fun ungroupMember(source: Long, url: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val member = links.singleOrNull { it.source == source && it.url == url } ?: return@mutate failed()
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
            ).also { entry -> if (entry != null) takeBack(member.key(), remaining.map { it.key() }, entry.id) }.saved()
        }
    }

    /** Explicitly merges the source group into the destination; no other group is read into the write set. */
    fun mergeGroups(sourceGroupId: String, destinationGroupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            if (sourceGroupId == destinationGroupId) return@mutate failed()
            val sourceMembers = links.filter { it.groupId == sourceGroupId }
            if (sourceMembers.isEmpty() || links.none { it.groupId == destinationGroupId }) return@mutate failed()
            conflicts(sourceMembers.map { it.key() }, links.filter { it.groupId == destinationGroupId }.map { it.key() }, links)
                .takeIf { it.isNotEmpty() }?.let { return@mutate LinkGroupMutationResult(false, groupId = destinationGroupId, conflicts = it) }
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
            ).joined(destinationGroupId, sourceMembers.map { link -> link.source to link.url })
        }
    }

    /** Ungroups every member only when the user explicitly chooses the group-level action. */
    fun ungroupGroup(groupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val links = getCrossSourceMangaLinks.awaitAll()
            val members = links.filter { it.groupId == groupId }
            if (members.isEmpty()) return@mutate failed()
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
            ).also { entry ->
                if (entry != null) members.forEach { member -> takeBack(member.key(), members.map { it.key() }, entry.id) }
            }.saved()
        }
    }

    /**
     * KMK v0.8.25: the version that gives the group its title and cover here, on the rated lists and in Move and Merge. Can be
     * undone like the other group changes.
     */
    fun setPrimaryVersion(groupId: String, source: Long, url: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        mutate(onResult) {
            val previous = getCrossSourceGroupPrimary.awaitByGroupId(groupId)
            val entry = exh.util.GroupUndoRecorder.buildSetPrimaryEntry(
                sourcePreferences.value,
                groupId,
                previous,
                RatedLinkKey(source, url),
            )
            setCrossSourceGroupPrimary.value.await(groupId, source, url)
            entry?.let(GroupUndoJournal::record)
            LinkGroupMutationResult(true, entry?.id)
        }
    }

    fun undo(entryId: String, onResult: (GroupUndoOutcome) -> Unit = {}) {
        screenModelScope.launch {
            val outcome = groupUndoService.undo(entryId)
            if (outcome.result == exh.util.GroupUndoResult.RESTORED) {
                // KMK v0.8.25: identity follows the grouping back: an undone join takes back what it confirmed, an undone removal
                // confirms again what it took back. Decisions the user made since are kept.
                guarded {
                    groupConfirmations.value.reverseForAction(entryId)
                    groupConfirmations.value.restoreClearedBy(entryId)
                }
            }
            load()
            onResult(outcome)
        }
    }

    /** KMK v0.8.25: the user says the titles in [conflicts] are the same manga after all; [then] repeats the action they asked for. */
    fun confirmSameManga(conflicts: List<LinkGroupConflict>, then: () -> Unit) {
        screenModelScope.launch {
            val ok = try {
                groupConfirmations.value.confirmAsUser(conflicts.map { it.pair })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) then()
        }
    }

    /**
     * Makes every title already in [groupId] one confirmed group and shares the group's rating, tracking, chapter progress and
     * tracker entry with the titles that lack them. For groups made before linking did this.
     */
    fun syncGroup(groupId: String, onResult: (LinkGroupMutationResult) -> Unit = {}) {
        screenModelScope.launch {
            val links = try {
                getCrossSourceMangaLinks.awaitAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val members = links.filter { it.groupId == groupId }
            val outcome = sync(groupId, members.map { it.source to it.url }, actionId = null)
            load()
            onResult(
                LinkGroupMutationResult(
                    succeeded = members.isNotEmpty(),
                    groupId = groupId,
                    sharing = outcome.sharing(),
                    conflicts = outcome?.rejected.orEmpty().map { it.toConflict(links) },
                ),
            )
        }
    }

    /** Confirms the new members and gives them the group's tracking, rating and tracker entries. Never fails the link itself. */
    private suspend fun sync(
        groupId: String,
        joined: List<Pair<Long, String>>,
        actionId: String?,
    ): ConfirmedGroupJoinSynchronizer.JoinOutcome? = try {
        joinSynchronizer.value.join(groupId, joined, actionId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // the link is already saved; the synchronizer logs its own step failures
        null
    }

    private fun ConfirmedGroupJoinSynchronizer.JoinOutcome?.sharing() =
        if (this?.sharingComplete == true) LinkGroupSharing.COMPLETE else LinkGroupSharing.INCOMPLETE

    /** After a join was saved: shares with the new members and says whether that finished. */
    private suspend fun GroupJournalEntry?.joined(groupId: String, keys: List<Pair<Long, String>>): LinkGroupMutationResult {
        val entry = this ?: return failed()
        val outcome = sync(groupId, keys, entry.id)
        return LinkGroupMutationResult(true, entry.id, groupId, outcome.sharing())
    }

    private fun GroupJournalEntry?.saved() = this?.let { LinkGroupMutationResult(true, it.id) } ?: failed()

    private fun failed() = LinkGroupMutationResult(false)

    /** "Not the same manga" decisions between the titles about to be linked and the group they join. */
    private suspend fun conflicts(
        joining: List<CrossSourceRecordKey>,
        existing: List<CrossSourceRecordKey>,
        links: List<CrossSourceMangaLink>,
        extraTitles: List<Manga> = emptyList(),
    ): List<LinkGroupConflict> {
        val pairs = try {
            groupConfirmations.value.rejectedAmong(joining, existing)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the join itself never turns a rejection into a confirmation, so a failed check cannot overrule the user
            emptyList()
        }
        return pairs.map { it.toConflict(links, extraTitles) }
    }

    private fun CrossSourceIdentityPair.toConflict(links: List<CrossSourceMangaLink>, extra: List<Manga> = emptyList()): LinkGroupConflict {
        fun title(key: CrossSourceRecordKey) = links.firstOrNull { it.source == key.source && it.url == key.url }?.title
            ?: extra.firstOrNull { it.source == key.source && it.url == key.url }?.title
            ?: key.url
        return LinkGroupConflict(this, title(left), title(right))
    }

    private suspend fun takeBack(member: CrossSourceRecordKey, formerPeers: List<CrossSourceRecordKey>, actionId: String) =
        guarded { groupConfirmations.value.reverseBetween(member, formerPeers, actionId) }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the grouping change is saved; a confirmation that could not be taken back stays recorded and is retried next time
        }
    }

    private fun CrossSourceMangaLink.key() = CrossSourceRecordKey(source, url)
    private fun Manga.key() = CrossSourceRecordKey(source, url)

    private fun mutate(
        onResult: (LinkGroupMutationResult) -> Unit,
        action: suspend () -> LinkGroupMutationResult,
    ) {
        screenModelScope.launch {
            val result = try {
                action()
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
