package tachiyomi.domain.taste.interactor

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tanh

// KMK v0.8.25 -->
/**
 * Pure arithmetic behind the learned tag weights, kept out of [GetTasteProfile] so it can be replayed on its own.
 *
 * It replaces "sum every rating, then clip to -10..+10" with three changes, each bounded so the weights stay inside the
 * same -10..+10 range the rest of For You already expects:
 *
 * - **Rarity.** A tag on almost every rated title says little about taste; a tag on few says more. Each tag's
 *   contribution is scaled by a factor between [RARITY_MIN] and [RARITY_MAX] from how many of the rated titles carry it.
 * - **Smooth saturation.** The total goes through `SOFT_CAP * tanh(total / SOFT_CAP)` instead of a hard clip, so a tag
 *   backed by many ratings keeps getting stronger than one backed by a few instead of every strong tag tying at the cap.
 * - **Age (off by default).** A rating would count for half as much after [HALF_LIFE_MONTHS] months, never below
 *   [DECAY_FLOOR]. Age is counted in whole 30-day months so the weights, and therefore the For You cache fingerprint, do
 *   not change every day. A rating with no timestamp (0) is not aged. The replay in `ScoringReplayHarnessTest` measured
 *   this as slightly worse than no decay on its synthetic corpus (mean rank 40.6 with it, 39.6 without), so it ships off
 *   until real ratings show otherwise; see [Options.age].
 *
 * A single Dislike is not enough to turn a tag negative at full strength: when exactly one disliked title carries a tag,
 * that tag's negative part counts for [SINGLE_DISLIKE_FACTOR]. A second Dislike on the tag makes it count fully. A Dislike
 * may be about the art or the translation rather than the genre, and an explicit tag Dislike or Block remains the strong
 * tool for "never this".
 */
object TasteWeighting {

    const val SOFT_CAP = 10.0
    const val RARITY_MIN = 0.75
    const val RARITY_MAX = 1.25
    const val HALF_LIFE_MONTHS = 6.0
    const val DECAY_FLOOR = 0.3
    const val SINGLE_DISLIKE_FACTOR = 0.5

    private const val MONTH_MILLIS = 30L * 24L * 60L * 60L * 1000L

    /** Switches for each of the four changes; production always uses [DEFAULT]. They exist so a replay can ablate one at a time. */
    data class Options(
        val rarity: Boolean = true,
        val age: Boolean = false,
        val smoothSaturation: Boolean = true,
        val singleDislikeDamping: Boolean = true,
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    /** One rated title: its rating weight (Love +2, Like +1, Dislike -2), when it was rated, and its tag groups. */
    data class RatedTitle(
        val ratingWeight: Double,
        val ratedAtMillis: Long,
        val groups: Set<String>,
    )

    fun learnedWeights(titles: List<RatedTitle>, nowMillis: Long, options: Options = Options.DEFAULT): Map<String, Double> {
        if (titles.isEmpty()) return emptyMap()
        val total = titles.size
        val frequency = HashMap<String, Int>()
        for (title in titles) for (group in title.groups) frequency.merge(group, 1, Int::plus)

        val positive = HashMap<String, Double>()
        val negative = HashMap<String, Double>()
        val dislikes = HashMap<String, Int>()
        for (title in titles) {
            if (title.ratingWeight == 0.0) continue
            val decayed = title.ratingWeight * if (options.age) ageFactor(title.ratedAtMillis, nowMillis) else 1.0
            for (group in title.groups) {
                val contribution = decayed * if (options.rarity) rarityFactor(frequency.getValue(group), total) else 1.0
                if (contribution > 0.0) {
                    positive.merge(group, contribution, Double::plus)
                } else {
                    negative.merge(group, contribution, Double::plus)
                    dislikes.merge(group, 1, Int::plus)
                }
            }
        }

        val result = HashMap<String, Double>()
        for (group in positive.keys + negative.keys) {
            val damped = options.singleDislikeDamping && (dislikes[group] ?: 0) == 1
            val negativePart = (negative[group] ?: 0.0) * if (damped) SINGLE_DISLIKE_FACTOR else 1.0
            val raw = (positive[group] ?: 0.0) + negativePart
            result[group] = if (options.smoothSaturation) saturate(raw) else raw.coerceIn(-SOFT_CAP, SOFT_CAP)
        }
        return result
    }

    fun saturate(raw: Double): Double = SOFT_CAP * tanh(raw / SOFT_CAP)

    /** Between [RARITY_MIN] (on every rated title) and [RARITY_MAX] (on one title, in a large set). */
    fun rarityFactor(titlesWithTag: Int, totalTitles: Int): Double {
        if (totalTitles <= 1) return 1.0
        val rarity = ln((totalTitles + 1.0) / (titlesWithTag + 1.0)) / ln(totalTitles + 1.0)
        return (RARITY_MIN + (RARITY_MAX - RARITY_MIN) * rarity).coerceIn(RARITY_MIN, RARITY_MAX)
    }

    fun ageFactor(ratedAtMillis: Long, nowMillis: Long): Double {
        if (ratedAtMillis <= 0L || nowMillis <= ratedAtMillis) return 1.0
        val months = ((nowMillis - ratedAtMillis) / MONTH_MILLIS).toDouble()
        return 0.5.pow(months / HALF_LIFE_MONTHS).coerceAtLeast(DECAY_FLOOR)
    }
}
// KMK <--
