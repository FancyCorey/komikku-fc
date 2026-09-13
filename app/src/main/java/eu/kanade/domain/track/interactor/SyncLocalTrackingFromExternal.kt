package eu.kanade.domain.track.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.TrackerChapterProgressMappingPolicy
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

/** Copies newer remote progress into the local-tracking work for the same source. */
class SyncLocalTrackingFromExternal(
    private val localTrackerRepository: LocalTrackerRepository,
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val trackPreferences: TrackPreferences,
    private val sourcePreferences: SourcePreferences,
    private val recordLocalTrackedChapterProgress: RecordLocalTrackedChapterProgress? = null,
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks,
    private val identityAuthorizationResolver: CrossSourceIdentityAuthorizationResolver,
) {
    suspend fun sync(track: Track, tracker: Tracker? = null) {
        if (!trackPreferences.autoSyncLocalTrackingFromTrackers().get()) return

        val manga = mangaRepository.getMangaById(track.mangaId)
        val workId = resolveWorkId(manga) ?: return
        val work = localTrackerRepository.getWork(workId) ?: return
        val now = System.currentTimeMillis()
        tracker?.let { synchronizeStatus(work, track, it, now) }
        if (localTrackerRepository.getWorkIdBySourceUrl(manga.source, manga.url) == null) {
            localTrackerRepository.upsertSource(
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
        }
        val chapter = TrackerChapterProgressMappingPolicy.resolve(
            chapters = getChaptersByMangaId.await(manga.id),
            trackerProgress = track.lastChapterRead,
            allowReadingOrderFallback = trackPreferences.matchTrackerProgressByReadingOrder().get(),
        )
        if (chapter == null) {
            // Keep the remote number on the work when this source has not loaded its chapter rows
            // yet. The next replay resolves it by number; dropping it here loses the refresh.
            localTrackerRepository.recordPendingProgress(
                workId = work.id,
                source = manga.source,
                chapterNumber = track.lastChapterRead,
                chapterLabel = "Chapter ${track.lastChapterRead}",
                progressAt = now,
            )
            recordLocalTrackedChapterProgress?.synchronize()
            return
        }
        // The repository applies this update with a chapter/progress monotonic predicate. Keeping
        // the compare-and-write in the database prevents a slower refresh from regressing a newer
        // tracker result after both callers have read the same initial work row.
        localTrackerRepository.recordProgress(
            workId = workId,
            source = manga.source,
            chapterNumber = chapter.chapterNumber,
            chapterUrl = chapter.url,
            chapterLabel = chapter.name,
            progressAt = now,
        )
        localTrackerRepository.recordSourceProgress(
            LocalTrackedWorkSourceProgress(
                workId = workId,
                source = manga.source,
                url = manga.url,
                chapterNumber = chapter.chapterNumber,
                chapterUrl = chapter.url,
                chapterLabel = chapter.name,
                progressAt = now,
                updatedAt = now,
            ),
        )
        // Replay immediately so the refreshed tracker chapter is reflected in the source chapter
        // rows before the manga screen chooses its next unread chapter. The batch replay in
        // RefreshTracks remains as a recovery pass for multiple trackers and cross-source writes.
        recordLocalTrackedChapterProgress?.synchronize()
    }

    /** Apply only safe automatic transitions; explicit paused/terminal local choices win. */
    private suspend fun synchronizeStatus(
        work: tachiyomi.domain.tracker.model.LocalTrackedWork,
        track: Track,
        tracker: Tracker,
        now: Long,
    ) {
        val targetStatus = when {
            track.status == tracker.getCompletionStatus() -> tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED
            track.status == tracker.getReadingStatus() -> tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING
            else -> null
        } ?: return
        val nextStatus = when (work.status) {
            tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.ON_HOLD,
            tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.DROPPED,
            tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED,
            -> if (targetStatus == tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED &&
                work.status == tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED
            ) {
                work.status
            } else {
                return
            }
            tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.PLANNED -> targetStatus
            tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.READING ->
                targetStatus.takeIf { it == tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED }
                    ?: work.status
        }
        if (nextStatus == work.status) return
        localTrackerRepository.upsertWork(
            work.copy(
                status = nextStatus,
                finishDate = track.finishDate.takeIf { nextStatus == tachiyomi.domain.tracker.model.LocalTrackedWorkStatus.COMPLETED && it > 0L }
                    ?: work.finishDate,
                updatedAt = maxOf(work.updatedAt, now),
            ),
        )
    }

    /**
     * Tracker rows can be refreshed for a source that is not the source currently attached to the
     * local work. Only an explicitly confirmed cross-source identity may bridge that gap; a legacy
     * group membership by itself must never create a local-tracking relationship.
     */
    private suspend fun resolveWorkId(manga: Manga): String? {
        localTrackerRepository.getWorkIdBySourceUrl(manga.source, manga.url)?.let { return it }
        if (!sourcePreferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().get()) return null
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

    /**
     * Replays tracker-originated progress through the confirmed-version mapping after all
     * external tracker rows have refreshed. Running this once per refresh batch avoids concurrent
     * reconciliation passes when a manga is connected to more than one tracker.
     */
    suspend fun synchronize() {
        if (!trackPreferences.autoSyncLocalTrackingFromTrackers().get()) return
        recordLocalTrackedChapterProgress?.synchronize()
    }
}
