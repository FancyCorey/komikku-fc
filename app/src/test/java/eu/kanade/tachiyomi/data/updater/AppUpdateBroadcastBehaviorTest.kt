package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import eu.kanade.tachiyomi.util.system.workManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AppUpdateBroadcastBehaviorTest {

    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var attemptStore: AppUpdateAttemptStore
    private val startedActivities = mutableListOf<Intent>()
    private var startActivityFailure: Exception? = null
    private var notifierConstructorMocked = false

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        context = spyk(application)
        startedActivities.clear()
        startActivityFailure = null
        every { context.applicationContext } returns application
        every { context.startActivity(any<Intent>()) } answers {
            startedActivities += firstArg<Intent>()
            startActivityFailure?.let { throw it }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        attemptStore = AppUpdateAttemptStore(context)
        attemptStore.clear()
        application.workManager.cancelAllWorkByTag(APP_UPDATE_WORK_NAME).result.get()

        mockkConstructor(AppUpdateNotifier::class)
        notifierConstructorMocked = true
        every { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() } just Runs
        every { anyConstructed<AppUpdateNotifier>().onInstallFinished() } just Runs
        every { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) } just Runs
        every { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        try {
            if (::application.isInitialized) {
                application.workManager.cancelAllWorkByTag(APP_UPDATE_WORK_NAME).result.get()
            }
        } finally {
            try {
                if (::attemptStore.isInitialized) attemptStore.clear()
            } finally {
                if (notifierConstructorMocked) {
                    unmockkConstructor(AppUpdateNotifier::class)
                    notifierConstructorMocked = false
                }
            }
        }
    }

    @Test
    fun obsoleteCallbacksCannotAffectANewerAttempt() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()
        val confirmation = Intent("system.confirm.update")

        receive(PackageInstaller.STATUS_SUCCESS, attemptId = OBSOLETE_ATTEMPT, sessionId = SESSION_ID)
        receive(
            PackageInstaller.STATUS_FAILURE,
            attemptId = OBSOLETE_ATTEMPT,
            sessionId = SESSION_ID + 1,
            fileUri = UPDATE_URI,
        )
        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            attemptId = OBSOLETE_ATTEMPT,
            sessionId = SESSION_ID + 2,
            fileUri = UPDATE_URI,
            confirmation = Intent("system.confirm.update"),
        )
        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            attemptId = OBSOLETE_ATTEMPT,
            sessionId = null,
            fileUri = UPDATE_URI,
            confirmation = confirmation,
        )

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, workState(work))
        assertTrue(startedActivities.isEmpty())
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallFinished() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    @Test
    fun currentSuccessCallbackCancelsWorkAndDispatchesOnlyOnce() {
        assertTerminalHandledOnce(PackageInstaller.STATUS_SUCCESS, expectFinished = true, expectInstallError = false)
    }

    @Test
    fun currentAbortedCallbackCancelsWorkAndDispatchesOnlyOnce() {
        assertTerminalHandledOnce(PackageInstaller.STATUS_FAILURE_ABORTED, expectFinished = false, expectInstallError = false)
    }

    @Test
    fun currentFailureCallbackCancelsWorkAndDispatchesInstallErrorOnlyOnce() {
        assertTerminalHandledOnce(PackageInstaller.STATUS_FAILURE, expectFinished = false, expectInstallError = true)
    }

    @Test
    fun unknownCurrentTerminalCallbackCancelsWorkAndDispatchesInstallErrorOnlyOnce() {
        assertTerminalHandledOnce(status = -73, expectFinished = false, expectInstallError = true)
    }

    @Test
    fun validPendingCallbackLaunchesConfirmationAndPromptsWithoutEndingAttempt() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()
        val confirmation = Intent("system.confirm.update")

        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            confirmation = confirmation,
            fileUri = UPDATE_URI,
        )

        assertEquals(1, startedActivities.size)
        assertEquals("system.confirm.update", startedActivities.single().action)
        assertTrue(startedActivities.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, workState(work))
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().promptInstall(Uri.parse(UPDATE_URI), UPDATE_TITLE) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
    }

    @Test
    fun currentPendingCallbackWithoutSessionUsesScopedCleanupAndKeepsAttemptRecord() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()

        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            sessionId = null,
            confirmation = Intent("system.confirm.update"),
        )

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        assertTrue(startedActivities.isEmpty())
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
    }

    @Test
    fun currentPendingCallbackWithoutConfirmationUsesUriRecoveryOnce() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()

        receive(PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation = null, fileUri = UPDATE_URI)
        receive(PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation = null, fileUri = UPDATE_URI)

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        assertTrue(startedActivities.isEmpty())
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().onInstallError(Uri.parse(UPDATE_URI), UPDATE_TITLE) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    @Test
    fun currentPendingCallbackWithoutUriUsesCleanupOnlyOnce() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()

        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            confirmation = Intent("system.confirm.update"),
            fileUri = null,
        )
        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            confirmation = Intent("system.confirm.update"),
            fileUri = null,
        )

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        assertTrue(startedActivities.isEmpty())
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    @Test
    fun failedConfirmationLaunchClearsOnlyTheMatchingAttemptAndDispatchesRecovery() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()
        startActivityFailure = IllegalStateException("confirmation launch failed")

        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            confirmation = Intent("system.confirm.update"),
            fileUri = UPDATE_URI,
        )

        assertFalse(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        assertEquals(1, startedActivities.size)
        assertTrue(startedActivities.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().onInstallError(Uri.parse(UPDATE_URI), UPDATE_TITLE) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    @Test
    fun legacyConfirmationLaunchDoesNotMutateNewerAttemptAndLegacyTerminalIsIgnored() {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()

        receive(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            attemptId = null,
            sessionId = null,
            fileUri = null,
            confirmation = Intent("system.confirm.update"),
        )
        receive(
            PackageInstaller.STATUS_SUCCESS,
            attemptId = null,
            sessionId = SESSION_ID + 1,
            fileUri = UPDATE_URI,
        )

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, workState(work))
        assertEquals(1, startedActivities.size)
        assertTrue(startedActivities.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallFinished() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    private fun assertTerminalHandledOnce(status: Int, expectFinished: Boolean, expectInstallError: Boolean) {
        attemptStore.begin(CURRENT_ATTEMPT)
        val work = enqueueInertUpdaterWork()
        val callback = callbackIntent(status)

        AppUpdateBroadcast().onReceive(context, callback)

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        AppUpdateBroadcast().onReceive(context, callback)

        assertTrue(attemptStore.isCurrent(CURRENT_ATTEMPT))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, workState(work))
        verify(exactly = if (expectFinished) 1 else 0) { anyConstructed<AppUpdateNotifier>().onInstallFinished() }
        verify(exactly = 1) { anyConstructed<AppUpdateNotifier>().cancelInstallNotification() }
        if (expectInstallError) {
            verify(exactly = 1) {
                anyConstructed<AppUpdateNotifier>().onInstallError(Uri.parse(UPDATE_URI), UPDATE_TITLE)
            }
        } else {
            verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().onInstallError(any(), any()) }
        }
        verify(exactly = 0) { anyConstructed<AppUpdateNotifier>().promptInstall(any(), any()) }
    }

    private fun receive(
        status: Int,
        attemptId: String? = CURRENT_ATTEMPT,
        sessionId: Int? = SESSION_ID,
        fileUri: String? = UPDATE_URI,
        confirmation: Intent? = null,
    ) {
        AppUpdateBroadcast().onReceive(context, callbackIntent(status, attemptId, sessionId, fileUri, confirmation))
    }

    private fun callbackIntent(
        status: Int,
        attemptId: String? = CURRENT_ATTEMPT,
        sessionId: Int? = SESSION_ID,
        fileUri: String? = UPDATE_URI,
        confirmation: Intent? = null,
    ): Intent = Intent(AppUpdateDownloadJob.PACKAGE_INSTALLED_ACTION)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE, UPDATE_TITLE)
        .apply {
            attemptId?.let { putExtra(AppUpdateDownloadJob.EXTRA_ATTEMPT_ID, it) }
            sessionId?.let { putExtra(AppUpdateDownloadJob.EXTRA_SESSION_ID, it) }
            fileUri?.let { putExtra(AppUpdateDownloadJob.EXTRA_FILE_URI, it) }
            confirmation?.let { putExtra(Intent.EXTRA_INTENT, it) }
        }

    private fun enqueueInertUpdaterWork(): java.util.UUID {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<InertUpdaterWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .addTag(APP_UPDATE_WORK_NAME)
            .build()
        context.workManager.enqueueUniqueWork(APP_UPDATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, workState(request.id))
        return request.id
    }

    private fun workState(id: java.util.UUID) = context.workManager.getWorkInfoById(id).get()?.state

    private class InertUpdaterWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private companion object {
        const val APP_UPDATE_WORK_NAME = "AppUpdateDownload"
        const val CURRENT_ATTEMPT = "current-host-receiver-attempt"
        const val OBSOLETE_ATTEMPT = "obsolete-host-receiver-attempt"
        const val SESSION_ID = 735
        const val UPDATE_URI = "content://host-test/updates/app.apk"
        const val UPDATE_TITLE = "Host update"
    }
}
