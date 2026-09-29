package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppUpdateBroadcastSourceTest {
    @Test
    fun `package replacement notification is outside receiver main thread`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateBroadcast.kt").readText()
        val replacementBlock = source.substringAfter("Intent.ACTION_MY_PACKAGE_REPLACED")

        assertTrue(replacementBlock.contains("goAsync()"))
        assertTrue(replacementBlock.contains("Dispatchers.Default"))
        assertTrue(replacementBlock.contains("pendingResult.finish()"))
        assertTrue(replacementBlock.contains("return"))
    }

    @Test
    fun `installer callback outcomes are delegated to the status policy`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateBroadcast.kt").readText()

        assertTrue(source.contains("AppUpdateInstallStatusPolicy.classify(status)"))
        assertTrue(source.contains("AppUpdateInstallStatusPolicy.Outcome.Failure"))
        assertTrue(source.contains("appUpdateNotifier.onInstallError(fileUri!!.toUri(), title)"))

        val abortedBlock = source.substringAfter("AppUpdateInstallStatusPolicy.Outcome.Aborted ->")
            .substringBefore("AppUpdateInstallStatusPolicy.Outcome.Failure")
        assertTrue(abortedBlock.contains("appUpdateNotifier.cancelInstallNotification()"))
        assertFalse(abortedBlock.contains("appUpdateNotifier.onInstallError"))
    }

    @Test
    fun `installer callbacks are scoped to their update attempt and session`() {
        val receiver = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateBroadcast.kt").readText()
        val worker = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()

        assertTrue(receiver.contains("AppUpdateAttemptStore(context).handleCallback("))
        assertTrue(receiver.contains("terminal = callbackAction != AppUpdateInstallStatusPolicy.CallbackAction.LaunchConfirmation"))
        assertTrue(worker.contains(".putExtra(EXTRA_ATTEMPT_ID, attemptId)"))
        assertTrue(worker.contains(".putExtra(EXTRA_SESSION_ID, createdSessionId)"))
        assertTrue(worker.contains("attemptStore.isCurrent(attemptId)"))
        assertTrue(worker.contains("attemptStore.runIfCurrentAndRun(attemptId)"))
    }

    @Test
    fun `malformed callbacks remain terminal and clean up the active attempt`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateBroadcast.kt").readText()

        assertTrue(source.contains("callbackAction = AppUpdateInstallStatusPolicy.callbackAction"))
        assertTrue(source.contains("terminal = callbackAction != AppUpdateInstallStatusPolicy.CallbackAction.LaunchConfirmation"))
        assertTrue(source.contains("if (attemptId == null)"))
        assertTrue(source.contains("if (sessionId < 0)"))
        assertTrue(source.contains("confirmIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"))
        assertTrue(source.contains("AppUpdateDownloadJob.stop(context, attemptId)"))
        assertTrue(source.contains("if (callbackAction == AppUpdateInstallStatusPolicy.CallbackAction.ShowInstallError)"))

        val legacyCallbackBlock = source.substringAfter("if (attemptId == null) {")
            .substringBefore("val sessionId")
        assertTrue(legacyCallbackBlock.contains("AppUpdateInstallStatusPolicy.legacyCallbackAction("))
        assertTrue(legacyCallbackBlock.contains("context.startActivity(confirmIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))"))
        assertTrue(legacyCallbackBlock.contains("catch (error: Exception)"))
        assertTrue(legacyCallbackBlock.contains("context.toast(KMR.strings.could_not_install_update)"))
        assertFalse(legacyCallbackBlock.contains("cancelInstallNotification"))
        assertFalse(legacyCallbackBlock.contains("AppUpdateDownloadJob.stop"))
        assertFalse(legacyCallbackBlock.contains("AppUpdateAttemptStore(context).clear"))

        val invalidSessionBlock = source.substringAfter("if (sessionId < 0) {")
            .substringBefore("val fileUri")
        assertTrue(invalidSessionBlock.contains("AppUpdateAttemptStore(context).runIfCurrentAndRun(attemptId)"))
        assertTrue(invalidSessionBlock.contains("appUpdateNotifier.cancelInstallNotification()"))
        assertTrue(invalidSessionBlock.contains("AppUpdateDownloadJob.stop(context, attemptId)"))
    }

    @Test
    fun `confirmation launch failure clears the attempt and shows recovery`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateBroadcast.kt").readText()

        assertTrue(source.contains("catch (error: Exception)"))
        assertTrue(source.contains("AppUpdateAttemptStore(context).clear(attemptId)"))
        assertTrue(source.contains("appUpdateNotifier.onInstallError(fileUri!!.toUri(), title)"))
    }
}
