package exh.recs

import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// KMK -->
/**
 * v0.8.24: what For You suggests for a source row that failed. The cause is read from the error; [Advice.keepsFailing]
 * comes from the source's For You history using the same rule as the "Often errors" label, so the strongest suggestion
 * ("Don't use in For You") only appears for sources that fail most of the time.
 */
object ForYouSourceFailurePolicy {

    enum class Cause { CONNECTION, SITE_CHECK, SITE_PROBLEM, EXTENSION, UNKNOWN }

    /**
     * Result of "Check source": one load of the source's Popular page, outside For You's searches. [WORKS] means the
     * source is fine for browsing and only For You's searches fail; [BROKEN] means it does not work at all.
     */
    enum class Check { CHECKING, WORKS, BROKEN }

    /** Provisional: how long "Check source" waits for the Popular page. */
    const val CHECK_TIMEOUT_MS = 20_000L

    data class Advice(
        val cause: Cause,
        val keepsFailing: Boolean,
        val failedRuns: Int,
        val totalRuns: Int,
    ) {
        /** Opening the site in the in-app browser only helps a browser check. */
        val suggestsOpenSite: Boolean get() = cause == Cause.SITE_CHECK
    }

    fun cause(error: Throwable): Cause = when {
        // CloudflareInterceptor throws a private CloudflareBypassException; match it by name
        error.javaClass.simpleName == "CloudflareBypassException" -> Cause.SITE_CHECK
        error is HttpException && (error.code == 403 || error.code == 503) -> Cause.SITE_CHECK
        error is HttpException && (error.code == 429 || error.code in 500..599) -> Cause.SITE_PROBLEM
        // the For You per-source deadline: the site answered too slowly or not at all
        error is ForYouSourceTimeoutException -> Cause.SITE_PROBLEM
        error is UnknownHostException || error is SocketTimeoutException -> Cause.CONNECTION
        error is LinkageError -> Cause.EXTENSION
        error is HttpException -> Cause.UNKNOWN
        error is IOException -> Cause.CONNECTION
        else -> Cause.UNKNOWN
    }

    fun advise(error: Throwable, stats: SourceFitStats?): Advice {
        // The counts shown must be the ones the label was judged on: the recent window when it has enough runs
        val useRecent = stats != null && stats.recentRunCount >= SourceFitStats.MIN_RUNS_FOR_LABEL
        val runs = if (useRecent) stats!!.recentRunCount else stats?.runCount ?: 0
        val errors = if (useRecent) stats!!.recentErrorCount else stats?.errorCount ?: 0
        return Advice(
            cause = cause(error),
            keepsFailing = stats != null && stats.fitLabel == SourceFitLabel.OftenErrors,
            failedRuns = errors,
            totalRuns = runs,
        )
    }
}
/** v0.8.24: a For You source search that did not finish within the per-source deadline. */
class ForYouSourceTimeoutException(seconds: Long) :
    java.util.concurrent.TimeoutException("Took longer than $seconds seconds")
// KMK <--
