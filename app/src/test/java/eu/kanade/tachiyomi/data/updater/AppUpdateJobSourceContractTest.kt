package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppUpdateJobSourceContractTest {

    @Test
    fun `periodic checker preserves cancellation and delegates failures to retry policy`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateJob.kt").readText()
        val workBlock = source.substringAfter("override suspend fun doWork()")
            .substringBefore("fun NotificationCompat.Builder.update")

        assertTrue(workBlock.contains("catch (e: CancellationException)"))
        assertTrue(workBlock.contains("throw e"))
        assertTrue(workBlock.contains("AppUpdateJobResultPolicy.classify(runAttemptCount, MAX_RUN_ATTEMPTS)"))
        assertTrue(workBlock.contains("AppUpdateJobResultPolicy.Outcome.Retry -> Result.retry()"))
        assertTrue(workBlock.contains("AppUpdateJobResultPolicy.Outcome.Failure -> Result.failure()"))
    }

    @Test
    fun `periodic updater keeps its production scheduling contract`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateJob.kt").readText()

        assertTrue(source.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(source.contains("3,\n                TimeUnit.DAYS,\n                3,\n                TimeUnit.HOURS"))
        assertTrue(source.contains("setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)"))
        assertTrue(source.contains("enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)"))
    }
}
