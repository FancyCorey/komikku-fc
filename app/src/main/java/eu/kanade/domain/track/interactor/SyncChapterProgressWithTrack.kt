package eu.kanade.domain.track.interactor

import android.app.Application
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.tracker.model.TrackerChapterProgressMappingPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

class SyncChapterProgressWithTrack(
    private val updateChapter: UpdateChapter,
    private val insertTrack: InsertTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val trackPreferences: TrackPreferences,
    private val enqueueUploadRetry: (Long, Double) -> Unit = { trackId, chapterNumber ->
        Injekt.get<DelayedTrackingStore>().add(trackId, chapterNumber)
        DelayedTrackingUpdateJob.setupTask(Injekt.get<Application>())
    },
) {
    /**
     * Sync chapter progress with the [EnhancedTracker]
     */
    suspend fun await(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ): Int? {
        if (tracker !is EnhancedTracker) {
            return null
        }
        // KMK -->
        return sync(mangaId, remoteTrack, tracker, queueFailedUpload = false)
    }

    /**
     * Sync chapter progress with the all trackers.
     */
    suspend fun sync(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
        queueFailedUpload: Boolean = true,
        // KMK --> v0.8.24: a manual pull always uploads newer local progress, but marking local chapters from
        // the remote record is the separate "Sync reading progress from connected trackers" setting.
        applyRemoteToLocal: Boolean = true,
        // KMK <--
    ): Int? {
        // KMK <--
        // Current chapters in database, sort by source's order because database's order is a mess
        val dbChapters = getChaptersByMangaId.await(mangaId)
            // KMK -->
            .sortedByDescending { it.sourceOrder }
            // KMK <--
            .filter { it.isRecognizedNumber }

        val sortedChapters = dbChapters
            .sortedBy { it.chapterNumber }

        // KMK -->
        var lastCheckChapter: Double
        var checkingChapter = 0.0

        /**
         * Chapters to update to follow tracker: only continuous incremental chapters
         * any abnormal chapter number will stop it from updating read status further.
         * Some mangas has name such as Volume 2 Chapter 1 which will corrupt the order
         * if we sort by chapterNumber.
         */
        val sourceTarget = if (applyRemoteToLocal) {
            TrackerChapterProgressMappingPolicy.resolve(
                chapters = dbChapters,
                trackerProgress = remoteTrack.lastChapterRead,
                allowReadingOrderFallback = trackPreferences.matchTrackerProgressByReadingOrder().get(),
            )
        } else {
            null
        }
        val chapterUpdates = sourceTarget?.let { target ->
            dbChapters
                .takeWhile { chapter ->
                    lastCheckChapter = checkingChapter
                    checkingChapter = chapter.chapterNumber
                    chapter.chapterNumber >= lastCheckChapter && chapter.chapterNumber <= target.chapterNumber
                }
                .filter { chapter -> !chapter.read }
                .map { it.copy(read = true).toChapterUpdate() }
        }.orEmpty()
        // KMK <--

        // only take into account continuous reading
        val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapterNumber ?: 0F
        // Tracker will update to latest read chapter
        val lastRead = max(remoteTrack.lastChapterRead, localLastRead.toDouble())
        val updatedTrack = remoteTrack.copy(lastChapterRead = lastRead)

        // Update Tracker to localLastRead if needed. Only a failure before remote success
        // is a delayed tracking failure; local persistence must not create a false upload retry.
        if (lastRead > remoteTrack.lastChapterRead) {
            try {
                tracker.update(updatedTrack.toDbTrack())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (queueFailedUpload) {
                    enqueueUploadRetry(remoteTrack.id, lastRead)
                }
                logcat(LogPriority.WARN, e)
                return null
            }
            try {
                insertTrack.await(updatedTrack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                return null
            }
            // KMK --> v0.8.24: the tracker entry is universal across versions of a manga
            MirrorTrackToLinkedVersions(insertTrack = { insertTrack }).await(updatedTrack)
            // KMK <--
        }
        try {
            // Always update local chapters following Tracker even past chapters
            if (chapterUpdates.isNotEmpty() && !tracker.hasNotStartedReading(remoteTrack.status)) {
                updateChapter.awaitAll(chapterUpdates)
                return lastRead.toInt()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
        // KMK -->
        return null
        // KMK <--
    }
}
