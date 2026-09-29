package eu.kanade.tachiyomi.data.updater

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateInstallStatusPolicyTest {
    @Test
    fun `pending approval and success have dedicated outcomes`() {
        assertEquals(
            AppUpdateInstallStatusPolicy.Outcome.PendingUserAction,
            AppUpdateInstallStatusPolicy.classify(PackageInstaller.STATUS_PENDING_USER_ACTION),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.Outcome.Success,
            AppUpdateInstallStatusPolicy.classify(PackageInstaller.STATUS_SUCCESS),
        )
    }

    @Test
    fun `user abort is distinct from installer failure`() {
        assertEquals(
            AppUpdateInstallStatusPolicy.Outcome.Aborted,
            AppUpdateInstallStatusPolicy.classify(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.Outcome.Failure,
            AppUpdateInstallStatusPolicy.classify(PackageInstaller.STATUS_FAILURE_TIMEOUT),
        )
    }

    @Test
    fun `unrecognized future status fails visibly`() {
        assertEquals(
            AppUpdateInstallStatusPolicy.Outcome.Failure,
            AppUpdateInstallStatusPolicy.classify(Int.MAX_VALUE),
        )
    }

    @Test
    fun `callback actions require both pending intent and file uri`() {
        assertEquals(
            AppUpdateInstallStatusPolicy.CallbackAction.LaunchConfirmation,
            AppUpdateInstallStatusPolicy.callbackAction(
                AppUpdateInstallStatusPolicy.Outcome.PendingUserAction,
                hasConfirmationIntent = true,
                hasFileUri = true,
            ),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.CallbackAction.ShowInstallError,
            AppUpdateInstallStatusPolicy.callbackAction(
                AppUpdateInstallStatusPolicy.Outcome.PendingUserAction,
                hasConfirmationIntent = false,
                hasFileUri = true,
            ),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.CallbackAction.CleanupOnly,
            AppUpdateInstallStatusPolicy.callbackAction(
                AppUpdateInstallStatusPolicy.Outcome.Failure,
                hasConfirmationIntent = false,
                hasFileUri = false,
            ),
        )
    }

    @Test
    fun `legacy pending action is launched only when the installer supplies confirmation`() {
        assertEquals(
            AppUpdateInstallStatusPolicy.LegacyCallbackAction.LaunchConfirmation,
            AppUpdateInstallStatusPolicy.legacyCallbackAction(
                AppUpdateInstallStatusPolicy.Outcome.PendingUserAction,
                hasConfirmationIntent = true,
            ),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.LegacyCallbackAction.Ignore,
            AppUpdateInstallStatusPolicy.legacyCallbackAction(
                AppUpdateInstallStatusPolicy.Outcome.PendingUserAction,
                hasConfirmationIntent = false,
            ),
        )
        assertEquals(
            AppUpdateInstallStatusPolicy.LegacyCallbackAction.Ignore,
            AppUpdateInstallStatusPolicy.legacyCallbackAction(
                AppUpdateInstallStatusPolicy.Outcome.Success,
                hasConfirmationIntent = true,
            ),
        )
    }
}
