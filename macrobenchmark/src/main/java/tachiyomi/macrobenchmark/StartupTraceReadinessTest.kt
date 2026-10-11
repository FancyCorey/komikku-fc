package tachiyomi.macrobenchmark

import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class StartupTraceReadinessTest {
    @Test
    fun readyTraceDoesNotSleep() {
        awaitStartupTraceReadyForIteration(0, { true }, { 0L }, { error("Unexpected sleep") })
    }

    @Test
    fun unmeasuredWarmupSkipsTraceChecksAndWaits() {
        var traceChecks = 0
        awaitStartupTraceReadyForIteration(
            iteration = null,
            isTraceEnabled = {
                traceChecks++
                false
            },
            uptimeMillis = { error("Unmeasured warmup must not read the clock") },
            sleepMillis = { error("Unmeasured warmup must not sleep") },
        )
        assertEquals(0, traceChecks)
    }

    @Test
    fun delayedUserspaceReadinessIsObservedBeforeReturning() {
        var now = 0L
        awaitStartupTraceReadyForIteration(0, { now >= 150 }, { now }, { now += it })
        assertEquals(150L, now)
    }

    @Test
    fun timeoutFailsClosedAndCapsLastSleep() {
        var now = 0L
        assertThrows(IllegalStateException::class.java) {
            awaitStartupTraceReadyForIteration(0, { false }, { now }, { now += it }, timeoutMillis = 125)
        }
        assertEquals(125L, now)
    }

    @Test
    fun invalidTimeoutIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            awaitStartupTraceReadyForIteration(0, { true }, { 0L }, {}, timeoutMillis = 0)
        }
    }

    @Test
    fun sleepInterruptionPropagates() {
        assertThrows(InterruptedException::class.java) {
            awaitStartupTraceReadyForIteration(0, { false }, { 0L }, { throw InterruptedException("cancelled") })
        }
    }

    @Test
    fun coldWarmAndHotBenchmarksShareTheSameReadinessCaller() {
        assertTrue(ColdStartupBenchmark::class.java.superclass == AbstractStartupBenchmark::class.java)
        assertTrue(WarmStartupBenchmark::class.java.superclass == AbstractStartupBenchmark::class.java)
        assertTrue(HotStartupBenchmark::class.java.superclass == AbstractStartupBenchmark::class.java)
    }
}
