package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AppUpdateDownloadJobRuntimeContractTest {

    @Test
    fun `transient download failure retries and keeps partial apk for resume`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val partialApk = File(context.externalCacheDir, "update.apk")
        partialApk.writeText("partial")

        val worker = TestListenableWorkerBuilder.from(
            context,
            AppUpdateDownloadJob::class.java,
        ).setInputData(
            Data.Builder()
                .putString(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL, "https://updates.invalid/app.apk")
                .build(),
        ).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(partialApk.exists())
    }

    @Test
    fun `capped download failure discards partial apk`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val partialApk = File(context.externalCacheDir, "update.apk")
        partialApk.writeText("partial")

        val worker = TestListenableWorkerBuilder.from(
            context,
            AppUpdateDownloadJob::class.java,
        ).setInputData(
            Data.Builder()
                .putString(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL, "https://updates.invalid/app.apk")
                .build(),
        ).setRunAttemptCount(AppUpdateDownloadJob.MAX_RUN_ATTEMPTS).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertFalse(partialApk.exists())
    }

    @Test
    fun `cancelled download completes without discarding partial apk`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val partialApk = File(context.externalCacheDir, "update.apk")
        partialApk.writeText("partial")

        val worker = TestListenableWorkerBuilder.from(
            context,
            AppUpdateDownloadJob::class.java,
        ).setInputData(
            Data.Builder()
                .putString(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL, "https://updates.invalid/cancel.apk")
                .build(),
        ).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(partialApk.exists())
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun bindFailingNetwork() {
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory<NetworkHelper> {
                        mockk {
                            every { downloadFileWithResume(any(), any(), any()) } answers {
                                if (firstArg<String>().contains("cancel")) {
                                    throw CancellationException("cancelled test download")
                                }
                                throw IOException("temporary test failure")
                            }
                        }
                    }
                }
            })
        }

        @AfterClass
        @JvmStatic
        fun clearDownloadedApk() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.externalCacheDir, "update.apk").delete()
        }
    }
}
