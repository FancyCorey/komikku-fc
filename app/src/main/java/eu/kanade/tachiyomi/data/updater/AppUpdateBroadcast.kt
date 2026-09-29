package eu.kanade.tachiyomi.data.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR

class AppUpdateBroadcast : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Package replacement is delivered while the app is being relaunched. Keep notification
            // construction and binder work off the receiver's main thread so install completion
            // cannot turn into a broadcast ANR under device pressure.
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    AppUpdateNotifier(context.applicationContext).onInstallFinished()
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val appUpdateNotifier = AppUpdateNotifier(context)

        if (intent.action == AppUpdateDownloadJob.PACKAGE_INSTALLED_ACTION) {
            /*
             * Callback on PackageInstaller status
             */
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val outcome = AppUpdateInstallStatusPolicy.classify(status)
            val confirmIntent = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
            val attemptId = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_ATTEMPT_ID)
            if (attemptId == null) {
                // PackageInstaller sessions can outlive the app version that created their callback.
                // Preserve a legacy system confirmation without touching notification/attempt state
                // that may now belong to a newer update.
                if (
                    AppUpdateInstallStatusPolicy.legacyCallbackAction(
                        outcome = outcome,
                        hasConfirmationIntent = confirmIntent != null,
                    ) == AppUpdateInstallStatusPolicy.LegacyCallbackAction.LaunchConfirmation
                ) {
                    try {
                        context.startActivity(confirmIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (error: Exception) {
                        context.toast(KMR.strings.could_not_install_update)
                    }
                }
                return
            }
            val sessionId = intent.getIntExtra(AppUpdateDownloadJob.EXTRA_SESSION_ID, -1)
            if (sessionId < 0) {
                AppUpdateAttemptStore(context).runIfCurrentAndRun(attemptId) {
                    appUpdateNotifier.cancelInstallNotification()
                    AppUpdateDownloadJob.stop(context, attemptId)
                }
                return
            }
            val fileUri = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_FILE_URI)
            val callbackAction = AppUpdateInstallStatusPolicy.callbackAction(
                outcome = outcome,
                hasConfirmationIntent = confirmIntent != null,
                hasFileUri = fileUri != null,
            )
            AppUpdateAttemptStore(context).handleCallback(
                attemptId = attemptId,
                sessionId = sessionId,
                terminal = callbackAction != AppUpdateInstallStatusPolicy.CallbackAction.LaunchConfirmation,
            ) {
                when (outcome) {
                    AppUpdateInstallStatusPolicy.Outcome.PendingUserAction -> {
                        when (callbackAction) {
                            AppUpdateInstallStatusPolicy.CallbackAction.LaunchConfirmation -> {
                                try {
                                    context.startActivity(confirmIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    appUpdateNotifier.cancelInstallNotification()
                                    val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                                    appUpdateNotifier.promptInstall(fileUri!!.toUri(), title)
                                } catch (error: Exception) {
                                    appUpdateNotifier.cancelInstallNotification()
                                    AppUpdateDownloadJob.stop(context, attemptId)
                                    AppUpdateAttemptStore(context).clear(attemptId)
                                    val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                                    appUpdateNotifier.onInstallError(fileUri!!.toUri(), title)
                                }
                            }
                            AppUpdateInstallStatusPolicy.CallbackAction.ShowInstallError -> {
                                appUpdateNotifier.cancelInstallNotification()
                                AppUpdateDownloadJob.stop(context, attemptId)
                                val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                                appUpdateNotifier.onInstallError(fileUri!!.toUri(), title)
                            }
                            AppUpdateInstallStatusPolicy.CallbackAction.CleanupOnly -> {
                                appUpdateNotifier.cancelInstallNotification()
                                AppUpdateDownloadJob.stop(context, attemptId)
                            }
                        }
                    }
                    AppUpdateInstallStatusPolicy.Outcome.Success -> {
                        try {
                            appUpdateNotifier.onInstallFinished()
                        } finally {
                            AppUpdateDownloadJob.stop(context, attemptId)
                            appUpdateNotifier.cancelInstallNotification()
                        }
                    }
                    AppUpdateInstallStatusPolicy.Outcome.Aborted -> {
                        appUpdateNotifier.cancelInstallNotification()
                        AppUpdateDownloadJob.stop(context, attemptId)
                    }
                    AppUpdateInstallStatusPolicy.Outcome.Failure -> {
                        context.toast(KMR.strings.could_not_install_update)
                        appUpdateNotifier.cancelInstallNotification()
                        AppUpdateDownloadJob.stop(context, attemptId)
                        if (callbackAction == AppUpdateInstallStatusPolicy.CallbackAction.ShowInstallError) {
                            val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                            appUpdateNotifier.onInstallError(fileUri!!.toUri(), title)
                        }
                    }
                }
            }
        }
    }
}
