package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateJobResultPolicyTest {
    @Test
    fun `update checks retry below the attempt cap`() {
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Retry,
            AppUpdateJobResultPolicy.classify(runAttemptCount = 0, maxAttempts = 3),
        )
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Retry,
            AppUpdateJobResultPolicy.classify(runAttemptCount = 2, maxAttempts = 3),
        )
    }

    @Test
    fun `update checks stop retrying at and beyond the cap`() {
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Failure,
            AppUpdateJobResultPolicy.classify(runAttemptCount = 3, maxAttempts = 3),
        )
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Failure,
            AppUpdateJobResultPolicy.classify(runAttemptCount = 99, maxAttempts = 3),
        )
    }

    @Test
    fun `test boundary follows the production cap`() {
        val cap = AppUpdateJob.MAX_RUN_ATTEMPTS
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Retry,
            AppUpdateJobResultPolicy.classify(cap - 1, cap),
        )
        assertEquals(
            AppUpdateJobResultPolicy.Outcome.Failure,
            AppUpdateJobResultPolicy.classify(cap, cap),
        )
    }
}
