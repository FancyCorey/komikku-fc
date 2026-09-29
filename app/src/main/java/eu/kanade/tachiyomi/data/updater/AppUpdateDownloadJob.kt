package eu.kanade.tachiyomi.data.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.connectivityManager
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import exh.log.xLogE
import exh.source.ExhPreferences
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.service.AppUpdatePolicy
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

internal fun shouldPropagateInstallationCancellation(error: Throwable): Boolean =
    error is CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()

    // KMK -->
    private val exhPreferences: ExhPreferences by injectLazy()
    // KMK <--

    override suspend fun doWork(): Result {
        val attemptId = inputData.getString(EXTRA_ATTEMPT_INPUT) ?: "legacy-$id"
        val attemptStore = AppUpdateAttemptStore(context)
        val isLegacyWork = inputData.getString(EXTRA_ATTEMPT_INPUT) == null
        val ownsAttempt = if (isLegacyWork) {
            attemptStore.adoptIfEmpty(attemptId)
        } else {
            attemptStore.isCurrent(attemptId)
        }
        if (!ownsAttempt) return Result.success()

        // KMK -->
        val idleRun = inputData.getBoolean(SCHEDULED_RUN, false)
        if (idleRun) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                cleanupDownloadedApk()
                return Result.failure()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val restrictions = exhPreferences.appShouldAutoUpdate().get()
                if ((
                        AppUpdatePolicy.DEVICE_ONLY_ON_WIFI in restrictions &&
                            !context.isConnectedToWifi()
                        ) ||
                    (
                        AppUpdatePolicy.DEVICE_NETWORK_NOT_METERED in restrictions &&
                            context.connectivityManager.isActiveNetworkMetered
                        )
                ) {
                    return Result.retry()
                }
            }
        }
        // KMK <--

        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)

        if (url.isNullOrEmpty()) {
            cleanupDownloadedApk()
            return Result.failure()
        }

        setForegroundSafely()

        val result = withIOContext {
            downloadApk(title, url, attemptId)
        }

        return when (result) {
            DownloadOutcome.Completed -> Result.success()
            DownloadOutcome.Retry -> Result.retry()
            DownloadOutcome.Failure -> Result.failure()
            DownloadOutcome.Cancelled -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(title: String, url: String, attemptId: String): DownloadOutcome = coroutineScope {
        val apkFile = File(context.externalCacheDir, UPDATE_FILE_NAME)

        // Show notification download starting.
        with(notifier) {
            onDownloadStarted(title)
                // KMK -->
                .show()
            // KMK <--
        }

        val progressListener = object : ProgressListener {
            // KMK -->
            // Total size of the downloading file, should be set when starting and kept over retries
            var totalSize = 0L
            // KMK <--

            // Progress of the download
            var savedProgress = 0

            // Keep track of the last notification sent to avoid posting too many.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                // KMK -->
                val downloadedSize: Long
                if (totalSize == 0L) {
                    totalSize = contentLength
                    downloadedSize = bytesRead
                } else {
                    downloadedSize = totalSize - contentLength + bytesRead
                }
                // KMK <--
                val progress = (100 * (downloadedSize.toFloat() / totalSize)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    notifier.onProgressChange(progress)
                }
            }
        }

        try {
            // KMK -->
            network.downloadFileWithResume(url, apkFile, progressListener)
            if (isStopped) {
                cancel()
                return@coroutineScope DownloadOutcome.Cancelled
            }
            // KMK <--

            notifier.cancel()
            // KMK -->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startInstalling(apkFile, title, attemptId)
            } else {
                // KMK <--
                notifier.promptInstall(apkFile.getUriCompat(context))
            }
            DownloadOutcome.Completed
        } catch (e: Exception) {
            xLogE("App update stopped:", e)
            val shouldCancel = e is CancellationException ||
                isStopped ||
                (e is StreamResetException && e.errorCode == ErrorCode.CANCEL)
            if (shouldCancel) {
                notifier.cancel()
                return@coroutineScope DownloadOutcome.Cancelled
            } else {
                notifier.onDownloadError(
                    url,
                    // KMK -->
                    context.stringResource(MR.strings.update_check_notification_download_error),
                    // KMK <--
                )
            }
            when (
                AppUpdateDownloadResultPolicy.classify(
                    error = e,
                    runAttemptCount = runAttemptCount,
                    maxAttempts = MAX_RUN_ATTEMPTS,
                )
            ) {
                AppUpdateDownloadResultPolicy.Outcome.Retry -> DownloadOutcome.Retry
                AppUpdateDownloadResultPolicy.Outcome.Failure -> {
                    cleanupDownloadedApk()
                    DownloadOutcome.Failure
                }
                AppUpdateDownloadResultPolicy.Outcome.Cancelled -> DownloadOutcome.Cancelled
            }
        }
    }

    // KMK -->
    @RequiresApi(31)
    private suspend fun startInstalling(file: File, title: String, attemptId: String) {
        val attemptStore = AppUpdateAttemptStore(context)
        if (!attemptStore.isCurrent(attemptId)) return
        val packageInstaller = context.packageManager.packageInstaller
        var sessionId: Int? = null
        var committed = false
        try {
            file.inputStream().use { data ->
                val installParams = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                )
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val createdSessionId = packageInstaller.createSession(installParams)
                sessionId = createdSessionId

                packageInstaller.openSession(createdSessionId).use { session ->
                    session.openWrite("package", 0, -1).use { packageInSession ->
                        data.copyTo(packageInSession)
                    }

                    val newIntent = Intent(context, AppUpdateBroadcast::class.java)
                        .setAction(PACKAGE_INSTALLED_ACTION)
                        .putExtra(EXTRA_FILE_URI, file.getUriCompat(context).toString())
                        .putExtra(EXTRA_DOWNLOAD_TITLE, title)
                        .putExtra(EXTRA_ATTEMPT_ID, attemptId)
                        .putExtra(EXTRA_SESSION_ID, createdSessionId)

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        createdSessionId,
                        newIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    val statusReceiver = pendingIntent.intentSender
                    committed = attemptStore.runIfCurrent(attemptId) {
                        session.commit(statusReceiver)
                    }
                    if (!committed) {
                        packageInstaller.abandonSession(createdSessionId)
                        return
                    }
                }
            }

            if (!attemptStore.runIfCurrentAndRun(attemptId) {
                    notifier.onInstalling(file.getUriCompat(context))
                }
            ) {
                return
            }
            launchUI {
                delay(1000)
                attemptStore.runIfCurrentAndRun(attemptId) {
                    val hasNotification = context.notificationManager
                        .activeNotifications.any { it.id == Notifications.ID_APP_INSTALL }
                    // If the package manager crashes for whatever reason (china phone)
                    // set a timeout and let the user manually install
                    if (packageInstaller.getSessionInfo(sessionId!!) == null && !hasNotification) {
                        notifier.cancelInstallNotification()
                        notifier.promptInstall(file.getUriCompat(context))
                    }
                }
            }
        } catch (error: Exception) {
            if (!committed) {
                sessionId?.let { id ->
                    runCatching {
                        packageInstaller.abandonSession(id)
                    }.onFailure { cleanupFailure ->
                        error.addSuppressed(cleanupFailure)
                    }
                }
            }
            if (shouldPropagateInstallationCancellation(error)) {
                throw error
            }
            // Either install package can't be found (probably bots) or there's a security exception
            // with the download manager. Nothing we can workaround.
            attemptStore.runIfCurrentAndRun(attemptId) {
                context.toast(context.stringResource(MR.strings.update_check_notification_download_error))
                notifier.cancelInstallNotification()
                notifier.promptInstall(file.getUriCompat(context))
            }
        }
    }
    // KMK <--

    private fun cleanupDownloadedApk() {
        val apkFile = context.externalCacheDir?.resolve(UPDATE_FILE_NAME) ?: return
        if (apkFile.exists() && !apkFile.delete()) {
            xLogE("Unable to remove failed app update download: ${apkFile.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "AppUpdateDownload"

        // KMK -->
        const val PACKAGE_INSTALLED_ACTION =
            "${BuildConfig.APPLICATION_ID}.SESSION_SELF_API_PACKAGE_INSTALLED"
        internal const val EXTRA_FILE_URI = "${BuildConfig.APPLICATION_ID}.AppInstaller.FILE_URI"
        internal const val EXTRA_ATTEMPT_ID = "${BuildConfig.APPLICATION_ID}.AppInstaller.ATTEMPT_ID"
        internal const val EXTRA_SESSION_ID = "${BuildConfig.APPLICATION_ID}.AppInstaller.SESSION_ID"
        internal const val EXTRA_ATTEMPT_INPUT = "update_attempt_id"
        private const val SCHEDULED_RUN = "scheduled_run"
        // KMK <--

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val MAX_RUN_ATTEMPTS = 3
        private const val UPDATE_FILE_NAME = "update.apk"

        fun start(
            context: Context,
            url: String,
            title: String? = null,
            // KMK -->
            scheduled: Boolean = false,
            // KMK <--
        ) {
            val attemptId = java.util.UUID.randomUUID().toString()
            val data = Data.Builder()
            data.putString(EXTRA_DOWNLOAD_URL, url)
            data.putString(EXTRA_DOWNLOAD_TITLE, title)
            data.putString(EXTRA_ATTEMPT_INPUT, attemptId)
            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .addTag(TAG)
                .apply {
                    // KMK -->
                    if (scheduled) {
                        data.putBoolean(SCHEDULED_RUN, true)
                        val restrictions = Injekt.get<ExhPreferences>().appShouldAutoUpdate().get()
                        val networkType = if (AppUpdatePolicy.DEVICE_NETWORK_NOT_METERED in restrictions) {
                            NetworkType.UNMETERED
                        } else {
                            NetworkType.CONNECTED
                        }
                        val networkRequestBuilder = NetworkRequest.Builder()
                        if (AppUpdatePolicy.DEVICE_ONLY_ON_WIFI in restrictions) {
                            networkRequestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        }
                        if (AppUpdatePolicy.DEVICE_NETWORK_NOT_METERED in restrictions) {
                            networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        }
                        val constraints = Constraints.Builder()
                            // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
                            .setRequiredNetworkRequest(networkRequestBuilder.build(), networkType)
                            .setRequiresCharging(AppUpdatePolicy.DEVICE_CHARGING in restrictions)
                            .setRequiresBatteryNotLow(true)
                            .build()

                        setConstraints(constraints)
                        setInitialDelay(10, TimeUnit.MINUTES)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    } else {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        // KMK <--
                        setConstraints(
                            Constraints(
                                requiredNetworkType = NetworkType.CONNECTED,
                            ),
                        )
                    }
                    setInputData(data.build())
                }
                .build()

            check(
                AppUpdateAttemptStore(context).beginAndRun(attemptId) {
                    context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
                },
            ) { "Unable to record app update attempt" }
        }

        fun stop(context: Context) {
            AppUpdateAttemptStore(context).clearAndRun {
                context.workManager.cancelUniqueWork(TAG)
            }
        }

        internal fun stop(context: Context, attemptId: String) {
            AppUpdateAttemptStore(context).runIfCurrentAndRun(attemptId) {
                context.workManager.cancelUniqueWork(TAG)
            }
        }
    }

    private enum class DownloadOutcome {
        Completed,
        Retry,
        Failure,
        Cancelled,
    }
}
