package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AppUpdateDownloadJobWorkManagerTest {

    @Test
    fun `missing download input fails production worker and removes stale apk`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staleApk = File(context.externalCacheDir, "update.apk")
        staleApk.writeText("stale")

        val worker = TestListenableWorkerBuilder.from(
            context,
            AppUpdateDownloadJob::class.java,
        ).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertFalse(staleApk.exists())
    }
}
