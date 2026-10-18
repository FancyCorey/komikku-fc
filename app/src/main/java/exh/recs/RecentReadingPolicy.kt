package exh.recs

import tachiyomi.domain.history.model.RecentReadingActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// KMK -->
/**
 * v0.8.24 recent-reading profile: the single place that owns the setting ranges and every weighting constant.
 *
 * ## What the signal is
 * For each manga read inside the recent window, a strength is derived from
 *  1. how much of the manga was read, as a fraction of its length, where the length is never taken as shorter than
 *     the configurable minimum chapter count (so a one-chapter manga cannot score 100%, and a long series is not
 *     favoured just because it is long);
 *  2. how many distinct days the reading was spread over (a bounded bonus; `history` keeps one row per chapter, so
 *     real "opens" cannot be counted);
 *  3. how recent the last read was (whole days, so the value is stable within a day and the For You cache is not
 *     invalidated by the clock alone).
 *
 * ## Provisional constants
 * [SPREAD_BONUS_MAX], [RECENCY_FLOOR], [PER_MANGA_GENRE_MAX] and [GROUP_CAP] are ROOT-chosen defaults, not tuned on
 * real data. They are chosen so a manga read through recently contributes more per tag than a Love rating (+2.0) and
 * a typical partial read about as much as a Like (+1.0): recent reading is stronger than long-term ratings, and the
 * ratings still contribute. They are named constants so they can be reviewed and changed in one place.
 *
 * The signal is only ever positive. Explicit Block and explicit tag Dislike are enforced outside this policy and
 * always win; titles rated Dislike or Not interested are not counted.
 */
internal object RecentReadingPolicy {

    const val DEFAULT_ENABLED = true

    const val MIN_WINDOW_DAYS = 1
    const val MAX_WINDOW_DAYS = 30
    const val DEFAULT_WINDOW_DAYS = 7

    /** The configurable "minimum chapters" floor: a manga is treated as at least this many chapters long. */
    const val MIN_MIN_CHAPTERS = 1
    const val MAX_MIN_CHAPTERS = 50
    const val DEFAULT_MIN_CHAPTERS = 3

    /** Largest extra share granted for reading on many different days (0.5 = up to +50%). */
    const val SPREAD_BONUS_MAX = 0.5

    /** A read at the very end of the window still counts for this share of a read today. */
    const val RECENCY_FLOOR = 0.5

    /** Per-tag contribution of one manga whose strength is 1.0. */
    const val PER_MANGA_GENRE_MAX = 3.0

    /** Upper bound of the summed recent weight for one tag group (long-term learned weights cap at 10). */
    const val GROUP_CAP = 6.0

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    fun resolveWindowDays(configuredValue: Int): Int =
        if (configuredValue in MIN_WINDOW_DAYS..MAX_WINDOW_DAYS) configuredValue else DEFAULT_WINDOW_DAYS

    fun resolveMinChapters(configuredValue: Int): Int =
        if (configuredValue in MIN_MIN_CHAPTERS..MAX_MIN_CHAPTERS) configuredValue else DEFAULT_MIN_CHAPTERS

    fun windowStartMillis(nowMillis: Long, windowDays: Int): Long =
        nowMillis - resolveWindowDays(windowDays) * DAY_MILLIS

    /** Strength of one manga's recent reading, in 0.0..(1.0 + [SPREAD_BONUS_MAX]); 0.0 when nothing was read. */
    fun strength(activity: RecentReadingActivity, windowDays: Int, minChapters: Int, nowMillis: Long): Double {
        if (activity.chaptersRead <= 0L) return 0.0
        val window = resolveWindowDays(windowDays)
        val floorLength = resolveMinChapters(minChapters)
        val length = max(max(activity.totalChapters, floorLength.toLong()), activity.chaptersRead).toDouble()
        val fraction = activity.chaptersRead / length

        val spread = if (window <= 1 || activity.activeDays <= 1L) {
            1.0
        } else {
            1.0 + SPREAD_BONUS_MAX * min(1.0, (activity.activeDays - 1).toDouble() / (window - 1))
        }

        val ageDays = max(0L, Math.floorDiv(nowMillis - activity.lastReadAt, DAY_MILLIS))
        val recency = if (ageDays >= window) {
            RECENCY_FLOOR
        } else {
            RECENCY_FLOOR + (1.0 - RECENCY_FLOOR) * (1.0 - ageDays.toDouble() / window)
        }

        return round3(fraction * spread * recency)
    }

    /** The per-tag weight one manga of the given [strength] adds to each of its tag groups. */
    fun perGenreWeight(strength: Double): Double = round3(strength * PER_MANGA_GENRE_MAX)

    /** Caps a group's summed weight and rounds it to two decimals (stable for the cache fingerprint). */
    fun finalGroupWeight(sum: Double): Double = round2(min(sum, GROUP_CAP))

    private fun round3(value: Double): Double = (value * 1000.0).roundToLong() / 1000.0
    private fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0
}
// KMK <--
