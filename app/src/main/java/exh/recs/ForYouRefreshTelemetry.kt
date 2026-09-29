package exh.recs

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded diagnostics for the installed recommendation corpus.
 *
 * This is deliberately log-only and only active for the debug corpus route. It gives the
 * runtime acceptance pass enough information to distinguish a slow source from a stale or
 * cancelled refresh without adding state to the release recommendation model.
 */
object ForYouRefreshTelemetry {
    private val activeGeneration = AtomicLong(-1L)
    private val firstResultGeneration = AtomicLong(-1L)
    private val cacheHitCount = AtomicLong(0L)

    fun begin(mode: ForYouDebugFixtureMode, generation: Long) {
        activeGeneration.set(if (mode.usesInstalledCorpus) generation else -1L)
        if (!mode.usesInstalledCorpus) return
        firstResultGeneration.set(-1L)
        cacheHitCount.set(0L)
        logcat(LogPriority.INFO, tag = "ForYouTelemetry") {
            "refresh_start generation=$generation mode=${mode.prefValue}"
        }
    }

    fun cacheHit(generation: Long, candidateCount: Int) {
        if (activeGeneration.get() != generation) return
        cacheHitCount.incrementAndGet()
        logcat(LogPriority.INFO, tag = "ForYouTelemetry") {
            "cache_hit generation=$generation candidates=$candidateCount"
        }
    }

    fun firstResult(generation: Long, candidateCount: Int) {
        if (activeGeneration.get() != generation) return
        if (firstResultGeneration.compareAndSet(-1L, generation)) {
            logcat(LogPriority.INFO, tag = "ForYouTelemetry") {
                "first_result generation=$generation candidates=$candidateCount"
            }
        }
    }

    fun superseded(generation: Long) {
        if (!activeGeneration.compareAndSet(generation, -1L)) return
        logcat(LogPriority.INFO, tag = "ForYouTelemetry") {
            "refresh_cancelled generation=$generation reason=superseded"
        }
    }

    fun finish(generation: Long, evaluatedCandidates: Int, visibleCandidates: Int) {
        if (activeGeneration.get() != generation) return
        logcat(LogPriority.INFO, tag = "ForYouTelemetry") {
            "refresh_finish generation=$generation evaluated=$evaluatedCandidates " +
                "visible=$visibleCandidates cache_hits=${cacheHitCount.get()} " +
                "first_result=${firstResultGeneration.get() == generation}"
        }
        activeGeneration.compareAndSet(generation, -1L)
    }
}
