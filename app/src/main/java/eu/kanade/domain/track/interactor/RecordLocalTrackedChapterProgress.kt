package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.model.SManga
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.AlternateSourceBridge
import tachiyomi.domain.taste.repository.AlternateSourceBridgeRepository
import tachiyomi.domain.tracker.model.LocalTrackedProgressInheritancePolicy
import tachiyomi.domain.tracker.model.LocalTrackedProgressSourceMappingPolicy
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.model.TrackerChapterProgressMappingPolicy
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class RecordLocalTrackedChapterProgress(
    private val localTrackerRepository: LocalTrackerRepository,
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val identityAuthorizationResolver: CrossSourceIdentityAuthorizationResolver =
        CrossSourceIdentityAuthorizationResolver(),
    private val alternateSourceBridgeRepository: AlternateSourceBridgeRepository = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
) {

    suspend fun await(manga: Manga, chapter: Chapter, progressAt: Long = System.currentTimeMillis()) =
        withNonCancellableContext {
            val exactWorkId = localTrackerRepository.getWorkIdBySourceUrl(manga.source, manga.url)
            // A confirmed sibling may own the local work before the source being read has been
            // attached to it. Resolve that work before recording progress so reading the sibling
            // does not silently become untracked.
            if (exactWorkId == null && !trackPreferences.autoInheritLocalProgress().get()) {
                return@withNonCancellableContext
            }
            val workId = exactWorkId ?: resolveConfirmedWorkId(manga) ?: return@withNonCancellableContext
            val existingWork = localTrackerRepository.getWork(workId)
            if (exactWorkId == null && existingWork != null) {
                localTrackerRepository.upsertSource(
                    LocalTrackedWorkSource(
                        workId = existingWork.id,
                        source = manga.source,
                        url = manga.url,
                        title = manga.title,
                        confidence = 100,
                        confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                        createdAt = existingWork.createdAt,
                        updatedAt = progressAt,
                    ),
                )
            }
            val chapterNumber = chapter.chapterNumber.takeIf { chapter.isRecognizedNumber }
            existingWork?.takeIf {
                LocalTrackedProgressInheritancePolicy.acceptsAggregateProgress(
                    target = it,
                    chapterNumber = chapterNumber,
                    progressAt = progressAt,
                )
            }?.let { work ->
                val completedAtFinalChapter = manga.status == SManga.COMPLETED.toLong() &&
                    chapter.isRecognizedNumber &&
                    chapterRepository.getChapterByMangaId(manga.id)
                        .filter { it.isRecognizedNumber }
                        .maxOfOrNull { it.chapterNumber }
                        ?.let { finalChapter -> chapter.chapterNumber >= finalChapter } == true
                val status = when {
                    work.status == LocalTrackedWorkStatus.ON_HOLD || work.status == LocalTrackedWorkStatus.DROPPED -> work.status
                    completedAtFinalChapter -> LocalTrackedWorkStatus.COMPLETED
                    else -> LocalTrackedProgressInheritancePolicy.statusAfterProgress(work.status)
                }
                if (status != work.status || work.startDate == null ||
                    (status != LocalTrackedWorkStatus.COMPLETED && work.finishDate != null)
                ) {
                    localTrackerRepository.upsertWork(
                        work.copy(
                            status = status,
                            startDate = work.startDate ?: progressAt,
                            finishDate = work.finishDate.takeIf { status == LocalTrackedWorkStatus.COMPLETED }
                                ?: progressAt.takeIf { status == LocalTrackedWorkStatus.COMPLETED },
                            updatedAt = maxOf(work.updatedAt, progressAt),
                        ),
                    )
                }
            }
            // Metadata uses the pre-progress snapshot; save it before recording the new chapter.
            localTrackerRepository.recordProgress(
                workId = workId,
                source = manga.source,
                chapterNumber = chapterNumber,
                chapterUrl = chapter.url,
                chapterLabel = chapter.name,
                progressAt = progressAt,
            )
            localTrackerRepository.recordSourceProgress(
                LocalTrackedWorkSourceProgress(
                    workId = workId,
                    source = manga.source,
                    url = manga.url,
                    chapterNumber = chapterNumber,
                    chapterUrl = chapter.url,
                    chapterLabel = chapter.name,
                    progressAt = progressAt,
                    updatedAt = progressAt,
                ),
            )
            if (!trackPreferences.autoInheritLocalProgress().get()) return@withNonCancellableContext
            propagate(
                origin = manga,
                originWork = localTrackerRepository.getWork(workId),
                originChapterUrl = chapter.url,
                originChapterNumber = chapterNumber,
                progressAt = progressAt,
            )
        }

    /** Finds the existing local work for a confirmed sibling when the current source is new. */
    private suspend fun resolveConfirmedWorkId(manga: Manga): String? {
        val originLink = getCrossSourceMangaLinks.awaitBySourceUrl(manga.source, manga.url) ?: return null
        val confirmedMembers = identityAuthorizationResolver.confirmedGroupMembers(
            originSource = manga.source,
            originUrl = manga.url,
            members = getCrossSourceMangaLinks.awaitByGroupId(originLink.groupId),
        )
        val candidates = confirmedMembers.mapNotNull { member ->
            localTrackerRepository.getWorkIdBySourceUrl(member.source, member.url)
                ?.let { workId -> localTrackerRepository.getWork(workId)?.let { workId to it } }
        }
        return candidates.minWithOrNull(
            compareBy<Pair<String, tachiyomi.domain.tracker.model.LocalTrackedWork>> { it.second.createdAt }
                .thenBy { it.first },
        )?.first
    }

    /** Replays persisted local progress after refresh or after a new confirmed mapping is added. */
    suspend fun synchronize() {
        val bridges = alternateSourceBridgeRepository.getAllBridges()
        val mappings = alternateSourceBridgeRepository.getAllMappings()
        val works = localTrackerRepository.getAllWorksAsFlow().first()
        works.forEach { work ->
            var effectiveWork = work
            var progressRows = localTrackerRepository.getSourceProgressForWork(work.id)
            val sourceRows = localTrackerRepository.getSources(work.id)
            val pendingSource = work.lastChapterSource?.let { source ->
                sourceRows.singleOrNull {
                    it.source == source && it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED
                }
            }
            val pendingTrackerProgress = work.lastChapterNumber
            if (work.lastChapterUrl == null && pendingTrackerProgress != null && pendingSource != null) {
                val pendingManga = mangaRepository.getMangaByUrlAndSourceId(pendingSource.url, pendingSource.source)
                val pendingChapter = pendingManga?.let { manga ->
                    TrackerChapterProgressMappingPolicy.resolve(
                        chapters = chapterRepository.getChapterByMangaId(manga.id),
                        trackerProgress = pendingTrackerProgress,
                        allowReadingOrderFallback = trackPreferences.matchTrackerProgressByReadingOrder().get(),
                    )
                }
                if (pendingManga != null && pendingChapter != null) {
                    markChaptersReadThrough(pendingManga, pendingChapter.url, pendingChapter.chapterNumber)
                    val progressAt = work.lastProgressAt ?: System.currentTimeMillis()
                    localTrackerRepository.recordProgress(
                        workId = work.id,
                        source = pendingManga.source,
                        chapterNumber = pendingChapter.chapterNumber,
                        chapterUrl = pendingChapter.url,
                        chapterLabel = pendingChapter.name,
                        progressAt = progressAt,
                    )
                    val resolvedProgress = LocalTrackedWorkSourceProgress(
                        workId = work.id,
                        source = pendingManga.source,
                        url = pendingManga.url,
                        chapterNumber = pendingChapter.chapterNumber,
                        chapterUrl = pendingChapter.url,
                        chapterLabel = pendingChapter.name,
                        progressAt = progressAt,
                        updatedAt = progressAt,
                    )
                    localTrackerRepository.recordSourceProgress(resolvedProgress)
                    effectiveWork = work.copy(
                        lastChapterNumber = pendingChapter.chapterNumber,
                        lastChapterUrl = pendingChapter.url,
                        lastChapterLabel = pendingChapter.name,
                    )
                    progressRows = progressRows
                        .filterNot { it.source == resolvedProgress.source && it.url == resolvedProgress.url } +
                        resolvedProgress
                }
            }
            val excludedProgress = progressRows.filter { progress ->
                progress.inheritedFromSource != null && sourceRows.any {
                    it.source == progress.source && it.url == progress.url && it.inheritanceOptedOut
                }
            }
            progressRows = progressRows - excludedProgress.toSet()
            progressRows.forEach { progress ->
                mangaRepository.getMangaByUrlAndSourceId(progress.url, progress.source)?.let { manga ->
                    markChaptersReadThrough(manga, progress)
                }
            }
            // Chapter read flags mirror persisted local progress even when the user has disabled
            // propagation to other confirmed versions. Keep these concerns independent so a
            // tracker refresh cannot leave the currently tracked source visually behind.
            if (!trackPreferences.autoInheritLocalProgress().get()) return@forEach
            val aggregateSource = effectiveWork.lastChapterSource?.let { source ->
                sourceRows.singleOrNull {
                    it.source == source && it.confirmation == tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation.USER_CONFIRMED &&
                        excludedProgress.none { progress -> progress.source == it.source && progress.url == it.url }
                }
            }
            val aggregateChapterUrl = effectiveWork.lastChapterUrl
            val aggregateProgressAt = effectiveWork.lastProgressAt
            val aggregateProgress = aggregateSource?.let { source ->
                aggregateChapterUrl?.takeIf { it.isNotBlank() }?.let { chapterUrl ->
                    aggregateProgressAt?.let { progressAt ->
                        LocalTrackedWorkSourceProgress(
                            workId = effectiveWork.id,
                            source = source.source,
                            url = source.url,
                            chapterNumber = effectiveWork.lastChapterNumber,
                            chapterUrl = chapterUrl,
                            chapterLabel = work.lastChapterLabel?.takeIf { it.isNotBlank() }
                                ?: "Chapter ${effectiveWork.lastChapterNumber ?: "unknown"}",
                            progressAt = progressAt,
                            updatedAt = progressAt,
                        )
                    }
                }
            }
            val aggregateRow = aggregateProgress?.let { aggregate ->
                progressRows.singleOrNull { it.source == aggregate.source && it.url == aggregate.url }
            }
            if (aggregateProgress != null) {
                if (!isAtLeast(aggregateRow, aggregateProgress)) {
                    // The work row is the authoritative fallback when an older profile or an
                    // earlier write left a source row stale.
                    localTrackerRepository.recordSourceProgress(aggregateProgress)
                }
                // The aggregate row remains an origin even when its source row is already current.
                // In particular, tracker refresh creates that row before this reconciliation pass.
                propagate(
                    origin = Manga.create().copy(source = aggregateProgress.source, url = aggregateProgress.url),
                    originWork = effectiveWork,
                    originChapterUrl = aggregateProgress.chapterUrl,
                    originChapterNumber = aggregateProgress.chapterNumber,
                    progressAt = aggregateProgress.progressAt,
                    bridges = bridges,
                    mappings = mappings,
                )
            }
            progressRows
                .filterNot { progress -> aggregateProgress?.let { it.source == progress.source && it.url == progress.url } == true }
                .forEach { progress ->
                    propagate(
                        origin = Manga.create().copy(source = progress.source, url = progress.url),
                        originWork = effectiveWork,
                        originChapterUrl = progress.chapterUrl,
                        originChapterNumber = progress.chapterNumber,
                        progressAt = progress.progressAt,
                        bridges = bridges,
                        mappings = mappings,
                    )
                }
        }
    }

    private fun isAtLeast(
        existing: LocalTrackedWorkSourceProgress?,
        update: LocalTrackedWorkSourceProgress,
    ): Boolean {
        if (existing == null) return false
        val existingNumber = existing.chapterNumber
        val updateNumber = update.chapterNumber
        return when {
            existingNumber == null && updateNumber == null -> existing.progressAt >= update.progressAt
            existingNumber == null -> false
            updateNumber == null -> true
            existingNumber > updateNumber -> true
            existingNumber < updateNumber -> false
            else -> existing.progressAt >= update.progressAt
        }
    }

    private suspend fun propagate(
        origin: Manga,
        originWork: tachiyomi.domain.tracker.model.LocalTrackedWork?,
        originChapterUrl: String,
        originChapterNumber: Double?,
        progressAt: Long,
        bridges: List<AlternateSourceBridge>? = null,
        mappings: List<tachiyomi.domain.taste.model.AlternateSourceBridgeMapping>? = null,
    ) {
        if (!trackPreferences.autoInheritLocalProgress().get()) return
        val originLink = getCrossSourceMangaLinks.awaitBySourceUrl(origin.source, origin.url)
        val confirmedMembers = originLink?.let { link ->
            identityAuthorizationResolver.confirmedGroupMembers(
                originSource = origin.source,
                originUrl = origin.url,
                members = getCrossSourceMangaLinks.awaitByGroupId(link.groupId),
            )
        }.orEmpty()
        val sharedSources = originWork?.let { work ->
            localTrackerRepository.getSources(work.id)
                .filter { it.confirmation == LocalTrackedWorkSourceConfirmation.USER_CONFIRMED }
        }.orEmpty()
        // A shared local tracker is itself a confirmed link, even without a separate rating group.
        val targets = (confirmedMembers.map { it.source to it.url } + sharedSources.map { it.source to it.url })
            .distinct()
            .filterNot { (source, url) -> source == origin.source && url == origin.url }
        if (targets.isEmpty()) return
        val resolvedBridges = bridges ?: alternateSourceBridgeRepository.getAllBridges()
        val resolvedMappings = mappings ?: alternateSourceBridgeRepository.getAllMappings()
        targets.forEach { (targetSource, targetUrl) ->
            inheritToTarget(
                origin = origin,
                originWork = originWork,
                originChapterUrl = originChapterUrl,
                originChapterNumber = originChapterNumber,
                targetSource = targetSource,
                targetUrl = targetUrl,
                progressAt = progressAt,
                bridges = resolvedBridges,
                mappings = resolvedMappings,
            )
        }
    }

    private suspend fun inheritToTarget(
        origin: Manga,
        originWork: tachiyomi.domain.tracker.model.LocalTrackedWork?,
        originChapterUrl: String,
        originChapterNumber: Double?,
        targetSource: Long,
        targetUrl: String,
        progressAt: Long,
        bridges: List<AlternateSourceBridge>,
        mappings: List<tachiyomi.domain.taste.model.AlternateSourceBridgeMapping>,
    ) {
        val targetManga = mangaRepository.getMangaByUrlAndSourceId(targetUrl, targetSource)
        if (targetManga == null) return
        val existingTargetWorkId = localTrackerRepository.getWorkIdBySourceUrl(targetSource, targetUrl)
        val existingTargetWork = existingTargetWorkId?.let { localTrackerRepository.getWork(it) }
            ?: if (existingTargetWorkId != null) return else null
        val targetSourceRow = existingTargetWork?.let { targetWork ->
            localTrackerRepository.getSources(targetWork.id)
                .singleOrNull { it.source == targetSource && it.url == targetUrl }
        }
        if (targetSourceRow?.inheritanceOptedOut == true) return
        if (existingTargetWork != null && targetSourceRow == null) {
            // A confirmed version can be discovered after its local work was created through
            // another source. Reattach it before applying the same progress inheritance path.
            localTrackerRepository.upsertSource(
                tachiyomi.domain.tracker.model.LocalTrackedWorkSource(
                    workId = existingTargetWork.id,
                    source = targetSource,
                    url = targetUrl,
                    title = targetManga.title,
                    confidence = 100,
                    confirmation = tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                    createdAt = progressAt,
                    updatedAt = progressAt,
                ),
            )
        }
        val mapping = LocalTrackedProgressSourceMappingPolicy.resolve(
            originSource = origin.source,
            originUrl = origin.url,
            originChapterUrl = originChapterUrl,
            targetSource = targetSource,
            targetUrl = targetUrl,
            bridges = bridges,
            mappings = mappings,
        )
        val targetChapter = if (mapping != null) {
            chapterRepository.getChapterByUrlAndMangaId(mapping.targetChapterUrl, targetManga.id)
        } else {
            resolveRecognizedChapter(manga = targetManga, originChapterNumber = originChapterNumber)
        }
        if (targetChapter == null) return
        markChaptersReadThrough(
            manga = targetManga,
            chapterUrl = targetChapter.url,
            chapterNumber = targetChapter.chapterNumber.takeIf { targetChapter.isRecognizedNumber },
        )
        val targetChapterUrl = targetChapter.url
        val targetWork = existingTargetWork ?: run {
            val sourceWork = originWork ?: return
            val newWork = sourceWork.copy(
                id = UUID.randomUUID().toString(),
                title = targetManga.title,
                normalizedTitle = targetManga.title.trim().lowercase(),
                lastChapterSource = null,
                lastChapterNumber = null,
                lastChapterUrl = null,
                lastChapterLabel = null,
                lastProgressAt = null,
                updatedAt = progressAt,
            )
            localTrackerRepository.upsertWork(newWork)
            localTrackerRepository.upsertSource(
                tachiyomi.domain.tracker.model.LocalTrackedWorkSource(
                    workId = newWork.id,
                    source = targetSource,
                    url = targetUrl,
                    title = targetManga.title,
                    confidence = 100,
                    confirmation = tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                    createdAt = progressAt,
                    updatedAt = progressAt,
                ),
            )
            newWork
        }
        val update = tachiyomi.domain.tracker.model.MappedLocalTrackedProgress(
            targetSource = targetSource,
            targetUrl = targetUrl,
            targetChapterUrl = targetChapterUrl,
            targetLabel = targetChapter.name,
            chapterNumber = targetChapter.chapterNumber.takeIf { targetChapter.isRecognizedNumber },
            progressAt = progressAt,
        )
        val existingTargetProgress = localTrackerRepository.getSourceProgress(
            workId = targetWork.id,
            source = targetSource,
            url = targetUrl,
        )
        val decision = LocalTrackedProgressInheritancePolicy.decide(
            enabled = true,
            confirmedGroupMember = true,
            optedOut = targetSourceRow?.inheritanceOptedOut ?: false,
            target = targetWork,
            update = update,
        )
        if (decision == tachiyomi.domain.tracker.model.LocalTrackedProgressInheritanceDecision.ALREADY_RECORDED) {
            // The work-level chapter may already match while this source's row is stale. Keep
            // the aggregate conflict decision, but advance the source-specific row independently.
            val status = LocalTrackedProgressInheritancePolicy.statusAfterProgress(targetWork.status)
            if (status != targetWork.status || targetWork.startDate == null ||
                (status != LocalTrackedWorkStatus.COMPLETED && targetWork.finishDate != null)
            ) {
                localTrackerRepository.upsertWork(
                    targetWork.copy(
                        status = status,
                        startDate = targetWork.startDate ?: progressAt,
                        finishDate = targetWork.finishDate.takeIf { status == LocalTrackedWorkStatus.COMPLETED },
                        updatedAt = maxOf(targetWork.updatedAt, progressAt),
                    ),
                )
            }
            if (LocalTrackedProgressInheritancePolicy.acceptsSourceProgress(existingTargetProgress, update)) {
                localTrackerRepository.recordSourceProgress(
                    LocalTrackedWorkSourceProgress(
                        workId = targetWork.id,
                        source = targetSource,
                        url = targetUrl,
                        chapterNumber = update.chapterNumber,
                        chapterUrl = targetChapterUrl,
                        chapterLabel = update.targetLabel,
                        progressAt = progressAt,
                        inheritedFromSource = origin.source,
                        inheritedFromUrl = origin.url,
                        updatedAt = progressAt,
                    ),
                )
            }
            return
        }
        if (decision != tachiyomi.domain.tracker.model.LocalTrackedProgressInheritanceDecision.APPLY) return
        val updatedWork = LocalTrackedProgressInheritancePolicy.apply(targetWork, update) ?: return
        localTrackerRepository.upsertWork(updatedWork)
        localTrackerRepository.recordSourceProgress(
            LocalTrackedWorkSourceProgress(
                workId = targetWork.id,
                source = targetSource,
                url = targetUrl,
                chapterNumber = update.chapterNumber,
                chapterUrl = targetChapterUrl,
                chapterLabel = update.targetLabel,
                progressAt = progressAt,
                inheritedFromSource = origin.source,
                inheritedFromUrl = origin.url,
                updatedAt = progressAt,
            ),
        )
    }

    /**
     * A confirmed group can still lack a confirmed URL mapping when a source has just refreshed.
     * Reuse external tracking's numeric threshold, including multiple translations and sources
     * missing the exact last-read chapter. Explicit saved chapter mappings take precedence.
     */
    private suspend fun resolveRecognizedChapter(
        manga: Manga,
        originChapterNumber: Double?,
    ): Chapter? {
        if (originChapterNumber == null) return null
        return TrackerChapterProgressMappingPolicy.resolve(
            chapters = chapterRepository.getChapterByMangaId(manga.id),
            trackerProgress = originChapterNumber,
            allowReadingOrderFallback = false,
        )
    }

    /** Applies persisted local progress to the corresponding source chapter rows. */
    private suspend fun markChaptersReadThrough(
        manga: Manga,
        progress: LocalTrackedWorkSourceProgress,
    ) {
        markChaptersReadThrough(manga, progress.chapterUrl, progress.chapterNumber)
    }

    private suspend fun markChaptersReadThrough(
        manga: Manga,
        chapterUrl: String,
        chapterNumber: Double?,
    ) {
        val targetChapter = chapterRepository.getChapterByUrlAndMangaId(chapterUrl, manga.id)
            ?: chapterNumber?.let { number ->
                chapterRepository.getChapterByMangaId(manga.id)
                    .filter { it.isRecognizedNumber && it.chapterNumber == number }
                    .singleOrNull()
            }
            ?: return
        val chaptersToMark = chapterRepository.getChapterByMangaId(manga.id)
            .filter { chapter ->
                !chapter.read && if (chapterNumber != null && targetChapter.isRecognizedNumber) {
                    chapter.isRecognizedNumber && chapter.chapterNumber <= targetChapter.chapterNumber
                } else {
                    chapter.id == targetChapter.id
                }
            }
            .map { chapter -> ChapterUpdate(id = chapter.id, read = true) }
        if (chaptersToMark.isNotEmpty()) {
            chapterRepository.updateAll(chaptersToMark)
        }
    }
}
