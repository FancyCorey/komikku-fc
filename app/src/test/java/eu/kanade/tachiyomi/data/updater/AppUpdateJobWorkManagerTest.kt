package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AppUpdateJobWorkManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
    }

    @After
    fun tearDown() {
        context.workManager.cancelAllWorkByTag("AppUpdateChecker").result.get()
    }

    @Test
    fun `production setup enqueues one periodic updater job`() {
        AppUpdateJob.setupTask(context)

        val jobs = context.workManager.getWorkInfosByTag("AppUpdateChecker").get()

        assertEquals("setup should keep one updater job", 1, jobs.size)
        val job = jobs.singleOrNull()
        assertNotNull(job)
        assertEquals(WorkInfo.State.ENQUEUED, job?.state)
    }

    @Test
    fun `setup updates an existing periodic updater without duplicating it`() {
        val legacyRequest = PeriodicWorkRequestBuilder<AppUpdateJob>(1, TimeUnit.DAYS)
            .addTag("legacy-updater")
            .build()
        context.workManager.enqueueUniquePeriodicWork(
            "AppUpdateChecker",
            ExistingPeriodicWorkPolicy.KEEP,
            legacyRequest,
        ).result.get()

        AppUpdateJob.setupTask(context)

        val jobs = context.workManager.getWorkInfosForUniqueWork("AppUpdateChecker").get()

        assertEquals("migration should retain one updater job", 1, jobs.size)
        assertTrue(jobs.single().tags.contains("AppUpdateChecker"))
        assertFalse(jobs.single().tags.contains("legacy-updater"))
    }

    @Test
    fun `disabled debug updater worker reports success through production doWork`() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(context, AppUpdateJob::class.java).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }
}
