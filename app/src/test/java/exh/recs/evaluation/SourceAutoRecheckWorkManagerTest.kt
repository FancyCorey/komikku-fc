package exh.recs.evaluation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.workManager
import exh.util.FakePreferenceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

// KMK --> v0.8.24: the automatic source check carries its Wi-Fi/battery requirements into the check it starts, and turning
// the automatic check off cancels that check but never a manual one. Work is enqueued through the production start/sync
// paths into a test WorkManager whose executor never runs workers, so each request's stored state can be inspected.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class SourceAutoRecheckWorkManagerTest {

    private lateinit var context: Context
    private lateinit var preferences: SourcePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .setExecutor(Executor { })
                .build(),
        )
        preferences = SourcePreferences(FakePreferenceStore())
    }

    @After
    fun tearDown() {
        context.workManager.cancelAllWork().result.get()
    }

    private fun qualityJobs(): List<WorkInfo> =
        context.workManager.getWorkInfosByTag("SourceRecommendationQualityJob").get()

    @Test
    fun `an automatic check runs under the schedule's Wi-Fi and battery requirements`() {
        preferences.recommendationAutoRecheckUnmeteredOnly().set(true)
        preferences.recommendationAutoRecheckBatteryGuard().set(true)

        SourceRecommendationQualityJob.start(
            context,
            SourceEvaluationInstallerPolicy.InstallerMode.CURRENT,
            automaticConstraints = SourceAutoRecheckScheduler.constraints(preferences),
        )

        val job = qualityJobs().single()
        assertTrue(job.tags.contains(SourceRecommendationQualityJob.TAG_AUTOMATIC))
        assertEquals(NetworkType.UNMETERED, job.constraints.requiredNetworkType)
        assertTrue(job.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `a manual check has no automatic tag or requirements`() {
        SourceRecommendationQualityJob.start(context, SourceEvaluationInstallerPolicy.InstallerMode.CURRENT)

        val job = qualityJobs().single()
        assertFalse(job.tags.contains(SourceRecommendationQualityJob.TAG_AUTOMATIC))
        assertEquals(NetworkType.NOT_REQUIRED, job.constraints.requiredNetworkType)
        assertFalse(job.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `turning the automatic check off cancels the check it started`() {
        SourceRecommendationQualityJob.start(
            context,
            SourceEvaluationInstallerPolicy.InstallerMode.CURRENT,
            automaticConstraints = SourceAutoRecheckScheduler.constraints(preferences),
        )
        preferences.recommendationAutoRecheckEnabled().set(false)

        SourceAutoRecheckScheduler.sync(context, preferences)

        assertEquals(WorkInfo.State.CANCELLED, qualityJobs().single().state)
    }

    @Test
    fun `turning the automatic check off leaves a manual check running`() {
        SourceRecommendationQualityJob.start(context, SourceEvaluationInstallerPolicy.InstallerMode.CURRENT)
        preferences.recommendationAutoRecheckEnabled().set(false)

        SourceAutoRecheckScheduler.sync(context, preferences)

        val state = qualityJobs().single().state
        assertFalse("manual check must not be cancelled, was $state", state == WorkInfo.State.CANCELLED)
    }
}
// KMK <--
