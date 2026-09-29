package exh.ocr

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [30])
class OcrPersistedWorkManagerTest {

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
        context.workManager.cancelAllWorkByTag("OcrIndexWorker").result.get()
    }

    @Test
    fun `persisted unique work is visible through the production lookup`() = runBlocking {
        val request = OneTimeWorkRequestBuilder<OcrIndexWorker>()
            .addTag("OcrIndexWorker")
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()

        context.workManager.enqueueUniqueWork(
            "OcrIndexWorker:active",
            ExistingWorkPolicy.KEEP,
            request,
        ).result.get()

        val persisted = OcrIndexWorker.getPersistedWorkInfo(context)

        assertNotNull("unique OCR work should survive the lookup boundary", persisted)
        assertEquals(request.id, persisted?.id)
        assertTrue("delayed work should remain pending", persisted?.state == androidx.work.WorkInfo.State.ENQUEUED)
    }
}
