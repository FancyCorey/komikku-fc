package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Decides whether a failed APK download should be retried or discarded. */
internal object AppUpdateDownloadResultPolicy {
    sealed interface Outcome {
        data object Retry : Outcome
        data object Failure : Outcome
        data object Cancelled : Outcome
    }

    /** [maxAttempts] is the retry count; the initial run is attempt zero. */
    fun classify(error: Throwable, runAttemptCount: Int, maxAttempts: Int): Outcome {
        if (error is CancellationException) return Outcome.Cancelled

        val transient = when (error) {
            is HttpException -> error.code == 408 || error.code == 429 || error.code >= 500
            is IOException -> true
            else -> false
        }
        return if (transient && runAttemptCount < maxAttempts) Outcome.Retry else Outcome.Failure
    }
}
