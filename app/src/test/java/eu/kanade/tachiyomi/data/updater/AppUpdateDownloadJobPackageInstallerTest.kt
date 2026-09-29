package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import eu.kanade.tachiyomi.util.storage.getUriCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class AppUpdateDownloadJobPackageInstallerTest {

    private lateinit var context: Application
    private lateinit var packageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller
    private lateinit var session: PackageInstaller.Session
    private lateinit var notifier: AppUpdateNotifier
    private val attemptId = "package-installer-test-attempt"

    @Before
    fun setUp() {
        context = spyk(ApplicationProvider.getApplicationContext<Application>())
        packageManager = mockk()
        packageInstaller = mockk(relaxed = true)
        session = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        AppUpdateAttemptStore(context).clear()
        AppUpdateAttemptStore(context).begin(attemptId)

        every { context.packageManager } returns packageManager
        every { context.applicationContext } returns context
        every { packageManager.packageInstaller } returns packageInstaller

        mockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
        every { any<File>().getUriCompat(any()) } returns Uri.parse("content://updates/app.apk")
    }

    @After
    fun tearDown() {
        unmockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
    }

    @Test
    fun `openWrite failure closes session abandons it and uses manual fallback`() = runBlocking {
        val worker = worker()
        val apk = apk()
        every { packageInstaller.createSession(any()) } returns 11
        every { packageInstaller.openSession(11) } returns session
        every { session.openWrite(any(), any(), any()) } throws IOException("open failed")

        invokeStartInstalling(worker, apk)

        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { packageInstaller.abandonSession(11) }
        verify(exactly = 1) { notifier.promptInstall(any(), any()) }
    }

    @Test
    fun `commit failure closes session abandons it and uses manual fallback`() = runBlocking {
        val worker = worker()
        val apk = apk()
        every { packageInstaller.createSession(any()) } returns 12
        every { packageInstaller.openSession(12) } returns session
        every { session.openWrite(any(), any(), any()) } returns ByteArrayOutputStream()
        every { session.commit(any()) } throws IOException("commit failed")

        invokeStartInstalling(worker, apk)

        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { packageInstaller.abandonSession(12) }
        verify(exactly = 1) { notifier.promptInstall(any(), any()) }
    }

    @Test
    fun `successful commit closes session without abandoning it`() = runBlocking {
        val worker = worker()
        val apk = apk()
        every { packageInstaller.createSession(any()) } returns 13
        every { packageInstaller.openSession(13) } returns session
        every { session.openWrite(any(), any(), any()) } returns ByteArrayOutputStream()

        invokeStartInstalling(worker, apk)

        verify(exactly = 1) { session.commit(any()) }
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { packageInstaller.abandonSession(any()) }
        verify(exactly = 1) { notifier.onInstalling(any()) }
    }

    @Test
    fun `cancellation closes and abandons precommit session without fallback`() {
        val worker = worker()
        val apk = apk()
        every { packageInstaller.createSession(any()) } returns 14
        every { packageInstaller.openSession(14) } returns session
        every { session.openWrite(any(), any(), any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                invokeStartInstalling(worker, apk)
            }
        }

        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { packageInstaller.abandonSession(14) }
        verify(exactly = 0) { notifier.promptInstall(any(), any()) }
    }

    @Test
    fun `superseded session write failure does not change install notifications`() = runBlocking {
        val worker = worker()
        val apk = apk()
        every { packageInstaller.createSession(any()) } returns 15
        every { packageInstaller.openSession(15) } returns session
        every { session.openWrite(any(), any(), any()) } answers {
            AppUpdateAttemptStore(context).begin("replacement-attempt")
            throw IOException("write failed after replacement")
        }

        invokeStartInstalling(worker, apk)

        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { packageInstaller.abandonSession(15) }
        verify(exactly = 0) { notifier.cancelInstallNotification() }
        verify(exactly = 0) { notifier.promptInstall(any(), any()) }
        verify(exactly = 0) { notifier.onInstalling(any()) }
    }

    private fun worker(): AppUpdateDownloadJob {
        return TestListenableWorkerBuilder.from(
            context,
            AppUpdateDownloadJob::class.java,
        ).setInputData(
            Data.Builder().putString(AppUpdateDownloadJob.EXTRA_ATTEMPT_INPUT, attemptId).build(),
        ).build().also { worker ->
            AppUpdateDownloadJob::class.java.getDeclaredField("notifier").apply {
                isAccessible = true
                set(worker, notifier)
            }
        }
    }

    private fun apk(): File {
        return File.createTempFile("update-", ".apk", context.cacheDir).apply {
            writeText("apk")
        }
    }

    private suspend fun invokeStartInstalling(
        worker: AppUpdateDownloadJob,
        apk: File,
    ) {
        val function = AppUpdateDownloadJob::class.declaredFunctions
            .single { it.name == "startInstalling" }
            .apply { isAccessible = true }

        try {
            function.callSuspend(worker, apk, "Update", attemptId)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }
}
