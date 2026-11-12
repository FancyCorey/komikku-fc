package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.track.LocalTrackingHistoryProgressPolicy
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.UUID

/**
 * Attaches confirmed versions to an existing local-tracking work without changing an existing
 * target work. Rating may always ask this owner to create tracking for its primary manga; the
 * linked-version preference independently decides whether confirmed siblings join it.
 * Unconfirmed candidates must stay outside this owner.
 */
class ConfirmedGroupLocalTrackingPropagator(
    private val repository: LocalTrackerRepository,
    private val sourcePreferencesOverride: SourcePreferences? = null,
    private val trackPreferencesOverride: TrackPreferences? = null,
    private val getHistoryOverride: GetHistory? = null,
    private val getChapterOverride: GetChapter? = null,
    private val getChaptersByMangaIdOverride: GetChaptersByMangaId? = null,
    private val ratingActionsOverride: ConfirmedGroupTasteRatingActions? = null,
    private val getLinksOverride: tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks? = null,
    private val identityResolverOverride: CrossSourceIdentityAuthorizationResolver? = null,
) {
    private data class LatestRead(
        val manga: Manga,
        val progress: LocalTrackingHistoryProgressPolicy.ReadProgress,
        val chapter: tachiyomi.domain.chapter.model.Chapter,
    )

    private val trackPreferences: TrackPreferences by lazy { trackPreferencesOverride ?: Injekt.get() }
    private val sourcePreferences: SourcePreferences by lazy { sourcePreferencesOverride ?: Injekt.get() }
    private val getHistory: GetHistory by lazy { getHistoryOverride ?: Injekt.get() }
    private val getChapter: GetChapter by lazy { getChapterOverride ?: Injekt.get() }
    private val getChaptersByMangaId: GetChaptersByMangaId by lazy { getChaptersByMangaIdOverride ?: Injekt.get() }
    private val ratingActions: ConfirmedGroupTasteRatingActions by lazy { ratingActionsOverride ?: ConfirmedGroupTasteRatingManager() }

    private val getLinks: tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks by lazy { getLinksOverride ?: Injekt.get() }
    private val identityResolver by lazy { identityResolverOverride ?: CrossSourceIdentityAuthorizationResolver() }

    private data class MembershipResult(
        val tracked: Boolean,
        val workId: String? = null,
        val members: List<Manga> = emptyList(),
        val attached: List<Manga> = emptyList(),
    )

    /** Captured targets are hints: current group membership and confirmation authorize each mutation. Called under the coordinator. */
    private suspend fun currentTargets(targets: List<Manga>): List<Manga> {
        val requested = targets.distinctBy { it.source to it.url }
        val primary = requested.firstOrNull() ?: return emptyList()
        if (requested.size == 1) return requested
        val link = getLinks.awaitBySourceUrl(primary.source, primary.url) ?: return listOf(primary)
        val members = identityResolver.confirmedGroupMembers(primary.source, primary.url, getLinks.awaitByGroupId(link.groupId))
        val keys = members.map { it.source to it.url }.toSet()
        val current = requested.filter { it == primary || (it.source to it.url) in keys }
        // A non-anchor rejection must not be bypassed by consolidating the entire connected group.
        for ((index, left) in current.withIndex()) {
            for (right in current.drop(index + 1)) {
                if (identityResolver.isRejected(left.source, left.url, right.source, right.url)) return listOf(primary)
            }
        }
        return current
    }

    private suspend fun finish(result: MembershipResult): Boolean {
        result.workId?.let { shareGroupRating(it, result.members, result.attached) }
        return result.tracked
    }

    /** Creates the first local-tracking work when a rating is intended to mean follow this title. */
    suspend fun ensureTrackedForRating(targets: List<Manga>): Boolean = finish(
        GroupTrackingMembershipCoordinator.withMembershipLock { ensureTrackedForRatingLocked(targets) },
    )

    private suspend fun ensureTrackedForRatingLocked(targets: List<Manga>): MembershipResult {
        val propagationEnabled = sourcePreferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().get()
        val resolved = if (propagationEnabled) currentTargets(targets) else targets.take(1)
        if (resolved.isEmpty()) return MembershipResult(false)
        val primary = resolved.first()
        val existingByKey = resolved.associateWith { manga ->
            repository.getWorkIdBySourceUrl(manga.source, manga.url)
        }
        // Creating the first work and propagating into an existing work are separate user
        // choices. Turning off automatic creation must not disable propagation for a group that is
        // already tracked.
        if (existingByKey.values.any { it != null }) {
            if (propagationEnabled) return propagateLocked(resolved)
            if (existingByKey[primary] != null) return MembershipResult(true)
        }
        if (!trackPreferences.autoCreateLocalTrackingFromRating().get()) return MembershipResult(false)
        val creationTargets = if (propagationEnabled) resolved else listOf(primary)

        val now = System.currentTimeMillis()
        val workId = UUID.randomUUID().toString()
        val historyByMangaId = creationTargets.associate { manga -> manga.id to getHistory.await(manga.id) }
        val latestRead = creationTargets.mapNotNull { manga ->
            LocalTrackingHistoryProgressPolicy.resolve(historyByMangaId[manga.id].orEmpty())
                ?.let { progress -> getChapter.await(progress.chapterId)?.let { chapter -> LatestRead(manga, progress, chapter) } }
        }.maxByOrNull { it.progress.progressAt }
        val completedByMetadata = creationTargets.any { manga ->
            if (manga.status != SManga.COMPLETED.toLong()) return@any false
            val chapters = getChaptersByMangaId.await(manga.id)
            val finalChapter = chapters.filter { it.isRecognizedNumber }.maxByOrNull { it.chapterNumber }
                ?: return@any false
            LocalTrackingHistoryProgressPolicy.hasReachedFinalChapter(
                history = historyByMangaId[manga.id].orEmpty(),
                chapters = chapters,
                finalChapter = finalChapter,
            )
        }
        val inferredStatus = when {
            !sourcePreferences.automaticLocalTrackingStatusInferenceEnabled().get() -> LocalTrackedWorkStatus.PLANNED
            completedByMetadata -> LocalTrackedWorkStatus.COMPLETED
            latestRead != null -> LocalTrackedWorkStatus.READING
            else -> LocalTrackedWorkStatus.PLANNED
        }
        val latestReadLabel = latestRead?.chapter?.name?.takeIf { it.isNotBlank() }
            ?: latestRead?.chapter?.chapterNumber?.takeIf { it.isFinite() }?.let { "Chapter $it" }
        repository.upsertWork(
            LocalTrackedWork(
                id = workId,
                title = primary.title,
                normalizedTitle = primary.title.trim().lowercase(Locale.ROOT),
                status = inferredStatus,
                lastChapterSource = latestRead?.manga?.source,
                lastChapterNumber = latestRead?.chapter?.takeIf { it.isRecognizedNumber }?.chapterNumber,
                lastChapterUrl = latestRead?.chapter?.url,
                lastChapterLabel = latestReadLabel,
                lastProgressAt = latestRead?.progress?.progressAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
        creationTargets.forEach { manga ->
            repository.upsertSource(
                LocalTrackedWorkSource(
                    workId = workId,
                    source = manga.source,
                    url = manga.url,
                    title = manga.title,
                    confidence = 100,
                    confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (latestRead?.manga?.source == manga.source && latestRead.manga.url == manga.url) {
                repository.upsertSourceProgress(
                    LocalTrackedWorkSourceProgress(
                        workId = workId,
                        source = manga.source,
                        url = manga.url,
                        chapterNumber = latestRead.chapter.takeIf { it.isRecognizedNumber }?.chapterNumber,
                        chapterUrl = latestRead.chapter.url,
                        chapterLabel = latestReadLabel ?: "Chapter ${latestRead.chapter.chapterNumber}",
                        progressAt = latestRead.progress.progressAt,
                        updatedAt = now,
                    ),
                )
            }
        }
        return MembershipResult(true)
    }

    suspend fun propagateIfAnyTracked(
        targets: List<Manga>,
        enabled: Boolean = sourcePreferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().get(),
    ): Boolean {
        if (!enabled) return false
        return finish(GroupTrackingMembershipCoordinator.withMembershipLock { propagateLocked(currentTargets(targets)) })
    }

    private suspend fun propagateLocked(resolved: List<Manga>): MembershipResult {
        val existingWorkIds = resolved.mapNotNull { manga ->
            repository.getWorkIdBySourceUrl(manga.source, manga.url)
        }.distinct()
        // The oldest work owns the canonical identity; target ordering must not affect it.
        val existing = existingWorkIds.mapNotNull { repository.getWork(it) }
            .minWithOrNull(compareBy<tachiyomi.domain.tracker.model.LocalTrackedWork> { it.createdAt }.thenBy { it.id })
            ?: return MembershipResult(false)

        val now = System.currentTimeMillis()
        val sharedWorkId = existing.id
        val duplicateWorkIds = existingWorkIds.filter { it != sharedWorkId }
        duplicateWorkIds.forEach { duplicateWorkId ->
            repository.consolidateWork(sharedWorkId, duplicateWorkId)
        }
        val attachedNow = mutableListOf<Manga>()
        for (manga in resolved) {
            val existingId = repository.getWorkIdBySourceUrl(manga.source, manga.url)
            if (existingId != null) continue
            if (repository.getWork(sharedWorkId) == null) {
                repository.upsertWork(
                    existing.copy(
                        id = sharedWorkId,
                        title = manga.title,
                        normalizedTitle = manga.title.trim().lowercase(Locale.ROOT),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            val work = repository.getWork(sharedWorkId) ?: continue
            repository.upsertSource(
                LocalTrackedWorkSource(
                    workId = work.id,
                    source = manga.source,
                    url = manga.url,
                    title = manga.title,
                    confidence = 100,
                    confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                    createdAt = work.createdAt,
                    updatedAt = now,
                ),
            )
            attachedNow += manga
        }
        return MembershipResult(true, sharedWorkId, resolved, attachedNow)
    }

    /**
     * KMK v0.8.25: a title that joins a tracked group takes the group's rating when it has none of its own, the same way it
     * takes the group's chapter. Never fails the attach; the rating-sharing setting is checked by the rating owner.
     */
    private suspend fun shareGroupRating(workId: String, resolved: List<Manga>, attached: List<Manga>) {
        if (attached.isEmpty()) return
        try {
            val members = (
                repository.getSources(workId).map { Triple(it.source, it.url, it.title) } +
                    resolved.map { Triple(it.source, it.url, it.title) }
                ).distinctBy { it.first to it.second }
                .map { (source, url, title) -> CrossSourceMangaLink(source, url, workId, title, 0L, 0L) }
            attached.forEach { ratingActions.applyGroupRatingToJoinedMember(members, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // the title is already attached to the tracking entry
        }
    }
}
