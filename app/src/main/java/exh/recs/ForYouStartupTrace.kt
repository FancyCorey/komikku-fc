package exh.recs

import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicLong

// KMK v0.8.25 -->
/**
 * Debug-build stage timings for the For You start-up path (model creation to first visible rows), so a pause when opening
 * Browse or For You can be traced to a stage and a thread instead of guessed at. Logs `ForYouStartup stage=... t=...ms
 * thread=...`; times are relative to the last [begin]. Does nothing in release builds and never changes behaviour.
 */
internal object ForYouStartupTrace {
    private val origin = AtomicLong(0L)

    fun begin(stage: String) {
        if (!BuildConfig.DEBUG) return
        origin.set(System.nanoTime())
        emit(stage)
    }

    fun mark(stage: String) {
        if (!BuildConfig.DEBUG) return
        emit(stage)
    }

    private fun emit(stage: String) {
        val elapsedMs = (System.nanoTime() - origin.get()) / 1_000_000
        logcat(LogPriority.INFO, tag = "ForYouStartup") {
            "stage=$stage t=${elapsedMs}ms thread=${Thread.currentThread().name}"
        }
    }
}
// KMK <--
