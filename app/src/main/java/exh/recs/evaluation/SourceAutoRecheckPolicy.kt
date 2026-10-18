package exh.recs.evaluation

import tachiyomi.domain.taste.model.RecommendationQualityVerdict
import tachiyomi.domain.taste.model.SourceRecommendationFit
import java.util.concurrent.TimeUnit

// KMK v0.8.24 -->
/**
 * Pure rules for the optional automatic source re-check (Find new sources > Automatic check).
 *
 * The feature is OFF until the user turns it on. When on, it only re-runs the For You search compatibility check for
 * sources whose result is missing or out of date, and it never installs an extension (the worker always uses the
 * "current installer" mode, under which the runner refuses non-installed sources instead of installing them). It only
 * produces results; it never installs or removes a source or edits the user's source order. When "Use results on For You" is
 * on, For You skips sources whose latest current result shows no usable picks (a separate, visible, reversible layer).
 */
object SourceAutoRecheckPolicy {

    const val DEFAULT_ENABLED = false
    const val DEFAULT_INTERVAL_DAYS = 14
    const val MIN_INTERVAL_DAYS = 1
    const val MAX_INTERVAL_DAYS = 90
    const val DEFAULT_UNMETERED_ONLY = true
    const val DEFAULT_BATTERY_GUARD = true

    /** Whether check results act on For You (only while the automatic check itself is on). */
    const val DEFAULT_APPLY_TO_FOR_YOU = true

    /** Preset choices shown in the picker; anything else is "Custom". */
    val PRESET_DAYS: List<Int> = listOf(7, 14, 30)

    fun coerceIntervalDays(days: Int): Int = days.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)

    fun isPreset(days: Int): Boolean = days in PRESET_DAYS

    /** Epoch millis of the next check, or null when no check has run yet (the first one is due as soon as WorkManager allows). */
    fun nextDueMillis(lastRunMillis: Long, intervalDays: Int): Long? =
        if (lastRunMillis <= 0L) null else lastRunMillis + TimeUnit.DAYS.toMillis(coerceIntervalDays(intervalDays).toLong())

    /** Sources that need a check: missing plus out of date. A source with a current result is left alone. */
    fun <T> targets(missing: List<T>, outdated: List<T>): List<T> = missing + outdated

    enum class SkipReason { NOTHING_TO_CHECK, ANOTHER_CHECK_RUNNING }

    fun skipReason(targetCount: Int, evaluationRunning: Boolean, qualityRunning: Boolean): SkipReason? = when {
        evaluationRunning || qualityRunning -> SkipReason.ANOTHER_CHECK_RUNNING
        targetCount <= 0 -> SkipReason.NOTHING_TO_CHECK
        else -> null
    }

    /**
     * For You must have tried a source at least this many times in its recent window, and shown nothing from it, before a
     * check result may skip it. The check alone is not trusted (user decision 2026-10-17): a source can probe badly because
     * its search or Latest list is poor while still holding plenty of good manga.
     */
    const val MIN_RECENT_FOR_YOU_RUNS_TO_SKIP = 3

    /**
     * Sources For You should skip. Deliberately loose: a source is skipped only when BOTH (1) its latest current check found
     * it unusable (no matches at all, or every probe query failed) AND (2) For You itself tried it at least
     * [MIN_RECENT_FOR_YOU_RUNS_TO_SKIP] times recently and showed nothing from it. Out-of-date, expired, inconclusive or
     * unresolved check results never skip, a weak or mixed result never skips, and any recent success keeps the source.
     * Nothing here edits the user's own source order or disabled list; the skip is a separate, visible and reversible
     * layer (see Troubleshooting), and the next check of the source re-decides it.
     */
    fun skippedSourceIds(
        fits: Collection<SourceRecommendationFit>,
        forYouHistory: Map<Long, exh.recs.SourceFitStats>,
        nowMillis: Long,
    ): Set<Long> = unusableByCheck(fits, nowMillis).filterTo(mutableSetOf()) { sourceId ->
        val history = forYouHistory[sourceId] ?: return@filterTo false
        history.recentRunCount >= MIN_RECENT_FOR_YOU_RUNS_TO_SKIP && history.recentShownCount == 0
    }

    private fun unusableByCheck(fits: Collection<SourceRecommendationFit>, nowMillis: Long): Set<Long> =
        fits.asSequence()
            .filter { it.evaluationVersion == SourceRecommendationFit.CURRENT_VERSION }
            .filter { it.expiresAt == null || it.expiresAt!! > nowMillis }
            .filter {
                it.verdict == RecommendationQualityVerdict.NO_MATCHES ||
                    (it.verdict == RecommendationQualityVerdict.ERROR && it.querySuccessCount == 0 && it.queryCount > 0)
            }
            .mapNotNull { it.sourceId }
            .toSet()

    /** Applies [skipped] to [sourceIds], but never leaves For You with no source at all. */
    fun <T> withoutSkipped(items: List<T>, skipped: Set<Long>, idOf: (T) -> Long): List<T> {
        if (skipped.isEmpty()) return items
        val kept = items.filter { idOf(it) !in skipped }
        return if (kept.isEmpty()) items else kept
    }
}
// KMK <--
