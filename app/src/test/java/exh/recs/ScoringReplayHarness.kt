package exh.recs

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.TasteWeighting
import tachiyomi.domain.taste.model.TasteProfile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * v0.8.25 replay harness for the For You scoring: "hide one title you loved and see where the scorer ranks it among other
 * titles". Test-only; it changes nothing in the app.
 *
 * The profile and the scorer under test are the real ones ([TasteWeighting], [PersonalRecommendationScorer]). The
 * [legacy] variant is a frozen copy of the pre-0.8.25 arithmetic (per-occurrence sum, hard clip at +-10, no age, no
 * rarity, no tag-count scaling) so every future change can be compared against the same baseline.
 *
 * It takes plain data ([Rated], [Candidate]) so it can be fed a synthetic corpus, as the test does, or an export of real
 * ratings and tags later.
 */
internal object ScoringReplayHarness {

    data class Rated(val tags: List<String>, val ratingWeight: Double, val ratedAtMillis: Long, val loved: Boolean)
    data class Candidate(val tags: List<String>)

    /**
     * LEGACY = frozen pre-0.8.25. CURRENT = real code. The two ablations isolate which half helps:
     * NEW_WEIGHTS_PLAIN_SUM uses the new learned weights with the old plain tag sum (no tag-count scaling);
     * OLD_WEIGHTS_NEW_SCORER uses the old clipped weights with the new scorer.
     */
    enum class Variant(val options: TasteWeighting.Options?) {
        LEGACY(null),
        CURRENT(TasteWeighting.Options.DEFAULT),
        NEW_WEIGHTS_PLAIN_SUM(TasteWeighting.Options.DEFAULT),
        OLD_WEIGHTS_NEW_SCORER(null),

        /** Current minus one change each (and WITH_AGE, which is off by default), still with the new scorer. */
        WITHOUT_RARITY(TasteWeighting.Options(rarity = false)),
        WITH_AGE(TasteWeighting.Options(age = true)),
        WITHOUT_SMOOTH_SATURATION(TasteWeighting.Options(smoothSaturation = false)),
        WITHOUT_DISLIKE_DAMPING(TasteWeighting.Options(singleDislikeDamping = false)),
    }

    data class Result(val heldOut: Int, val hitAt10: Double, val meanReciprocalRank: Double, val meanRank: Double)

    private fun manga(tags: List<String>): Manga = Manga.create().copy(ogGenre = tags)

    fun profile(variant: Variant, rated: List<Rated>, nowMillis: Long): TasteProfile {
        val weights = when (variant) {
            Variant.LEGACY, Variant.OLD_WEIGHTS_NEW_SCORER -> {
                val raw = HashMap<String, Double>()
                for (r in rated) for (tag in r.tags) raw.merge(tag, r.ratingWeight, Double::plus)
                raw.mapValues { (_, v) -> v.coerceIn(-10.0, 10.0) }
            }
            else -> TasteWeighting.learnedWeights(
                rated.map { TasteWeighting.RatedTitle(it.ratingWeight, it.ratedAtMillis, it.tags.toSet()) },
                nowMillis,
                variant.options!!,
            )
        }
        return TasteProfile(
            learnedTagWeights = weights,
            explicitTagPreferences = emptyMap(),
            sourceAffinity = emptyMap(),
            blockedGroups = emptySet(),
        )
    }

    fun score(variant: Variant, tags: List<String>, profile: TasteProfile): Double = when (variant) {
        // Frozen pre-0.8.25 scorer: plain sum over the title's tags.
        Variant.LEGACY, Variant.NEW_WEIGHTS_PLAIN_SUM -> tags.sumOf { profile.learnedTagWeights[it] ?: 0.0 }
        else -> PersonalRecommendationScorer.score(manga(tags), profile, emptyMap()).score
    }

    /**
     * For each Love title rated within [heldOutWithinMillis] of [nowMillis]: rebuild the profile without it, then rank it
     * among [distractors]. Ties count as half a rank each, which is the expected rank under a random tie-break.
     */
    fun replay(
        variant: Variant,
        rated: List<Rated>,
        distractors: List<Candidate>,
        nowMillis: Long,
        heldOutWithinMillis: Long,
    ): Result {
        var heldOut = 0
        var hits = 0
        var reciprocal = 0.0
        var rankSum = 0.0
        for ((index, target) in rated.withIndex()) {
            if (!target.loved || nowMillis - target.ratedAtMillis > heldOutWithinMillis) continue
            val profile = profile(variant, rated.filterIndexed { i, _ -> i != index }, nowMillis)
            val targetScore = score(variant, target.tags, profile)
            var greater = 0
            var tied = 0
            for (candidate in distractors) {
                val s = score(variant, candidate.tags, profile)
                if (abs(s - targetScore) < 1e-9) {
                    tied++
                } else if (s > targetScore) {
                    greater++
                }
            }
            val rank = 1.0 + greater + tied / 2.0
            heldOut++
            if (rank <= 10.0) hits++
            reciprocal += 1.0 / rank
            rankSum += rank
        }
        return if (heldOut == 0) {
            Result(0, 0.0, 0.0, 0.0)
        } else {
            Result(heldOut, hits.toDouble() / heldOut, reciprocal / heldOut, rankSum / heldOut)
        }
    }

    // ---- synthetic corpus -------------------------------------------------------------------------------------

    /**
     * A reader with a hidden taste: a few niche tags loved, a few disliked, one popular tag mildly liked. Two of the loved
     * tags only apply to ratings newer than 18 months (taste drift). Appeal of a title is its summed preference divided
     * by sqrt(tag count) plus noise, a standard "attention is spread over the tags" assumption; ratings follow appeal.
     */
    class SyntheticReader(seed: Long, private val tagCount: Int = 60) {
        private val random = java.util.Random(seed)
        private val tagNames = (0 until tagCount).map { "tag$it" }
        private val cumulative: DoubleArray = run {
            var sum = 0.0
            DoubleArray(tagCount) { rank ->
                sum += 1.0 / Math.pow(rank + 1.0, 0.9)
                sum
            }
        }
        private val currentPreference = HashMap<String, Double>()
        private val oldPreference = HashMap<String, Double>()

        init {
            val niche = (8 until 40).shuffled(random).take(6)
            val loved = niche.map { tagNames[it] }
            val disliked = (8 until 40).filter { it !in niche }.shuffled(random).take(4).map { tagNames[it] }
            loved.forEach { currentPreference[it] = 2.0 }
            disliked.forEach { currentPreference[it] = -2.0 }
            currentPreference[tagNames[0]] = 0.4
            oldPreference.putAll(currentPreference)
            // drift: the last two loved tags were not loved before
            loved.takeLast(2).forEach { oldPreference.remove(it) }
            val replacement = (8 until 40).map { tagNames[it] }.filter { it !in currentPreference }.shuffled(random).take(2)
            replacement.forEach { oldPreference[it] = 2.0 }
        }

        private fun drawTag(): String {
            val x = random.nextDouble() * cumulative.last()
            var lo = 0
            var hi = tagCount - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cumulative[mid] < x) lo = mid + 1 else hi = mid
            }
            return tagNames[lo]
        }

        /** 3 tags plus a heavy tail, so some titles carry 15-24 tags. */
        fun title(): List<String> {
            val n = (3 + (-ln(1.0 - random.nextDouble()) * 4.0).toInt()).coerceAtMost(24)
            val set = LinkedHashSet<String>()
            var guard = 0
            while (set.size < n && guard++ < 200) set += drawTag()
            return set.toList()
        }

        private fun appeal(tags: List<String>, preference: Map<String, Double>): Double =
            tags.sumOf { preference[it] ?: 0.0 } / sqrt(tags.size.toDouble()) + random.nextGaussian() * 0.3

        fun ratedLibrary(size: Int, nowMillis: Long): List<Rated> {
            val month = 30L * 24 * 60 * 60 * 1000
            val titles = (0 until size).map { title() }
            val ageMonths = titles.map { random.nextInt(36) }
            val scored = titles.mapIndexed { i, tags ->
                val preference = if (ageMonths[i] > 18) oldPreference else currentPreference
                Triple(tags, ageMonths[i], appeal(tags, preference))
            }
            val sorted = scored.sortedByDescending { it.third }
            val loveCut = (size * 0.25).toInt()
            val likeCut = loveCut + (size * 0.30).toInt()
            val dislikeFrom = size - (size * 0.15).toInt()
            return sorted.mapIndexedNotNull { rank, (tags, age, _) ->
                val at = nowMillis - age * month
                when {
                    rank < loveCut -> Rated(tags, 2.0, at, loved = true)
                    rank < likeCut -> Rated(tags, 1.0, at, loved = false)
                    rank >= dislikeFrom -> Rated(tags, -2.0, at, loved = false)
                    else -> null
                }
            }.shuffled(random)
        }

        fun distractors(count: Int): List<Candidate> = (0 until count).map { Candidate(title()) }
    }
}
