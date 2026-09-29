package eu.kanade.tachiyomi.data.updater

import android.content.pm.PackageInstaller

/** Maps installer callbacks to terminal or user-action handling. Unknown statuses fail visibly. */
internal object AppUpdateInstallStatusPolicy {
    enum class Outcome {
        PendingUserAction,
        Success,
        Aborted,
        Failure,
    }

    enum class CallbackAction {
        LaunchConfirmation,
        ShowInstallError,
        CleanupOnly,
    }

    enum class LegacyCallbackAction {
        LaunchConfirmation,
        Ignore,
    }

    fun classify(status: Int): Outcome = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome.PendingUserAction
        PackageInstaller.STATUS_SUCCESS -> Outcome.Success
        PackageInstaller.STATUS_FAILURE_ABORTED -> Outcome.Aborted
        else -> Outcome.Failure
    }

    fun callbackAction(outcome: Outcome, hasConfirmationIntent: Boolean, hasFileUri: Boolean): CallbackAction = when {
        outcome == Outcome.PendingUserAction && hasConfirmationIntent && hasFileUri -> CallbackAction.LaunchConfirmation
        outcome == Outcome.Failure && hasFileUri -> CallbackAction.ShowInstallError
        outcome == Outcome.PendingUserAction && hasFileUri -> CallbackAction.ShowInstallError
        else -> CallbackAction.CleanupOnly
    }

    fun legacyCallbackAction(outcome: Outcome, hasConfirmationIntent: Boolean): LegacyCallbackAction =
        if (outcome == Outcome.PendingUserAction && hasConfirmationIntent) {
            LegacyCallbackAction.LaunchConfirmation
        } else {
            LegacyCallbackAction.Ignore
        }
}
