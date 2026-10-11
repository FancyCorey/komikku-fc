package tachiyomi.macrobenchmark

/** Waits for userspace tracing before launch; a kernel tracing flag alone is not sufficient. */
internal fun awaitStartupTraceReady(
    isTraceEnabled: () -> Boolean,
    uptimeMillis: () -> Long,
    sleepMillis: (Long) -> Unit,
    timeoutMillis: Long = 5_000,
) {
    require(timeoutMillis > 0)
    val startedAt = uptimeMillis()
    while (!isTraceEnabled()) {
        val remaining = timeoutMillis - (uptimeMillis() - startedAt)
        check(remaining > 0) { "Startup trace was not ready; refusing an untraceable launch" }
        sleepMillis(minOf(50L, remaining))
    }
}

/**
 * Skips trace readiness for Macrobenchmark's intentionally unmeasured setup launches, where
 * [iteration] is null. Measured startup iterations still use the fail-closed readiness barrier.
 */
internal fun awaitStartupTraceReadyForIteration(
    iteration: Int?,
    isTraceEnabled: () -> Boolean,
    uptimeMillis: () -> Long,
    sleepMillis: (Long) -> Unit,
    timeoutMillis: Long = 5_000,
) {
    if (iteration == null) return
    awaitStartupTraceReady(isTraceEnabled, uptimeMillis, sleepMillis, timeoutMillis)
}
