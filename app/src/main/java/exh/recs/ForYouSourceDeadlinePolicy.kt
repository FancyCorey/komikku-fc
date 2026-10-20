package exh.recs

// KMK -->
/**
 * v0.8.24: how long For You waits for one source, adapted to this refresh. A fixed limit would make For You fail on a
 * slow connection, where every source is slow. Instead the limit follows how long the sources that already finished in
 * this refresh took: [FACTOR] times their median, never below [MIN_MS] and never above [MAX_MS]. Until a source has
 * finished there is nothing to compare with, so only [MAX_MS] applies. So on a slow network the limit grows with
 * everyone; only a source stuck far behind the others (on the user's tablet: 13 sources within 6 s, one at 116 s) is cut.
 */
internal class ForYouSourceDeadlinePolicy {

    private val finishedDurationsMs = mutableListOf<Long>()

    /** A source finished (with results, nothing or an error of its own) after [durationMs]. */
    fun recordFinished(durationMs: Long) {
        synchronized(finishedDurationsMs) { finishedDurationsMs += durationMs.coerceAtLeast(0L) }
    }

    /** The current limit for a source still running. */
    fun limitMs(): Long {
        val durations = synchronized(finishedDurationsMs) { finishedDurationsMs.sorted() }
        if (durations.isEmpty()) return MAX_MS
        val median = durations[durations.size / 2]
        return (median * FACTOR).coerceIn(MIN_MS, MAX_MS)
    }

    companion object {
        /** Provisional. */
        const val FACTOR = 4L
        const val MIN_MS = 30_000L
        const val MAX_MS = 180_000L

        /** How often a waiting source is compared against the current limit. */
        const val CHECK_INTERVAL_MS = 250L
    }
}
// KMK <--
