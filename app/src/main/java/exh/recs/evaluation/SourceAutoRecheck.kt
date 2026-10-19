package exh.recs.evaluation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.taste.interactor.GetSourceEvaluations
import tachiyomi.domain.taste.interactor.GetSourceRecommendationFit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

// KMK v0.8.24 -->
/**
 * Schedules the optional automatic source re-check. Off by default; [sync] enqueues or cancels the periodic work to match
 * the current preferences and is called whenever one of them changes.
 */
object SourceAutoRecheckScheduler {
    private const val UNIQUE_WORK_NAME = "SourceAutoRecheck"
    private const val TAG = "SourceAutoRecheck"

    fun sync(context: Context, preferences: SourcePreferences = Injekt.get()) {
        val workManager = context.workManager
        if (!preferences.recommendationAutoRecheckEnabled().get()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            // A check the schedule already started stops too; a manual check is left alone.
            SourceRecommendationQualityJob.cancelAutomatic(context)
            return
        }
        val days = SourceAutoRecheckPolicy.coerceIntervalDays(preferences.recommendationAutoRecheckIntervalDays().get())
        val constraints = constraints(preferences)
        val request = PeriodicWorkRequestBuilder<SourceAutoRecheckWorker>(days.toLong(), TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        // UPDATE keeps the existing schedule's timing where possible and applies the new interval and constraints.
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** The Wi-Fi and battery requirements, applied both to the schedule and to the check it starts. */
    fun constraints(preferences: SourcePreferences): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (preferences.recommendationAutoRecheckUnmeteredOnly().get()) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresBatteryNotLow(preferences.recommendationAutoRecheckBatteryGuard().get())
        .build()
}

/**
 * Re-checks sources whose For You compatibility result is missing or out of date, by staging them and starting the same
 * [SourceRecommendationQualityJob] the manual action uses. Installer mode CURRENT means the runner refuses sources that
 * are not installed, so this never installs or removes an extension.
 */
class SourceAutoRecheckWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<SourcePreferences>()
        if (!preferences.recommendationAutoRecheckEnabled().get()) return Result.success()
        return try {
            val evaluations = Injekt.get<GetSourceEvaluations>().awaitAll()
            val fits = Injekt.get<GetSourceRecommendationFit>().awaitAll().associateBy { it.evaluationKey }
            val queue = SourceRecommendationQualityQueue.compute(evaluations, fits)
            val targets = SourceAutoRecheckPolicy.targets(queue.missingPromising, queue.outdatedPromising)
            val skip = SourceAutoRecheckPolicy.skipReason(
                targetCount = targets.size,
                evaluationRunning = SourceEvaluationJob.isRunning(context),
                qualityRunning = SourceRecommendationQualityJob.isRunning(context),
            )
            if (skip == null) {
                SourceRecommendationQualityJobState.pendingTargets = targets
                SourceRecommendationQualityJobState.activeQueueState.value = SourceRecommendationQualityQueueState(
                    status = SourceRecommendationQualityQueueState.Status.Running,
                    totalCount = targets.size,
                )
                SourceRecommendationQualityJob.start(
                    context,
                    SourceEvaluationInstallerPolicy.InstallerMode.CURRENT,
                    // The check itself, not only this launcher, must wait for Wi-Fi and battery as chosen.
                    automaticConstraints = SourceAutoRecheckScheduler.constraints(preferences),
                )
            }
            // A skipped run still counts as checked: nothing needed doing, or another check was already doing it.
            preferences.recommendationAutoRecheckLastRunMillis().set(System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "KMK SourceAutoRecheck: run failed, will retry" }
            Result.retry()
        }
    }
}
// KMK <--
