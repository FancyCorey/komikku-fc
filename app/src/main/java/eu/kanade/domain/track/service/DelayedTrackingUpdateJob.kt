package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > 3) {
            return Result.failure()
        }

        val getTracks = Injekt.get<GetTracks>()
        val trackChapter = Injekt.get<TrackChapter>()

        val delayedTrackingStore = Injekt.get<DelayedTrackingStore>()

        withIOContext {
            processQueuedItems(getTracks, trackChapter, delayedTrackingStore)
        }

        return if (delayedTrackingStore.getItems().isEmpty()) Result.success() else Result.retry()
    }

    internal suspend fun processQueuedItems(
        getTracks: GetTracks,
        trackChapter: TrackChapter,
        delayedTrackingStore: DelayedTrackingStore,
    ) {
        delayedTrackingStore.getItems().forEach { item ->
            val track = getTracks.awaitOne(item.trackId)
            when {
                track == null -> delayedTrackingStore.remove(item.trackId)
                track.lastChapterRead >= item.lastChapterRead.toDouble() -> {
                    delayedTrackingStore.removeIfAtMost(item.trackId, track.lastChapterRead)
                }
                else -> {
                    logcat(LogPriority.DEBUG) {
                        "Updating delayed track item: ${track.mangaId}, last chapter read: ${item.lastChapterRead}"
                    }
                    trackChapter.await(
                        context,
                        track.mangaId,
                        item.lastChapterRead.toDouble(),
                        setupJobOnFailure = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
