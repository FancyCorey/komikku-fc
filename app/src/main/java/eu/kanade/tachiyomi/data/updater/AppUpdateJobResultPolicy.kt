package eu.kanade.tachiyomi.data.updater

/** Bounds update-check retries while keeping the next periodic run scheduled. */
internal object AppUpdateJobResultPolicy {
    sealed interface Outcome {
        data object Retry : Outcome
        data object Failure : Outcome
    }

    fun classify(runAttemptCount: Int, maxAttempts: Int): Outcome =
        if (runAttemptCount < maxAttempts) Outcome.Retry else Outcome.Failure
}
