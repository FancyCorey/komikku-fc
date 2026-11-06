package exh.recs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Optional: replays the scoring variants on a real export of the user's ratings. It runs only when `KFC_REAL_RATINGS_JSON`
 * names an export file (ratings with tags and timestamps, plus unrated titles as candidates); on any other machine it skips.
 * Nothing about the data is stored in the repository.
 *
 * Two splits: leave-one-out (hide each Love title) and forward (train on the older ratings, rank the later Loves, which is
 * what rating age decay is meant to help). Each runs against two candidate pools. "All" ranks a title against every other
 * title; "matched" ranks it only against titles with about the same number of tags. The matched pool is the fair one: on real
 * libraries the number of tags mostly reflects how much metadata a source provides, so a score that simply favours many tags
 * looks good on "all" without knowing anything about taste.
 */
class ScoringReplayRealRatingsTest {

    private val variants = listOf(
        ScoringReplayHarness.Variant.LEGACY,
        ScoringReplayHarness.Variant.CURRENT,
        ScoringReplayHarness.Variant.WITH_AGE,
        ScoringReplayHarness.Variant.WITHOUT_RARITY,
        ScoringReplayHarness.Variant.WITHOUT_SMOOTH_SATURATION,
        ScoringReplayHarness.Variant.WITHOUT_DISLIKE_DAMPING,
    )

    private class Tally {
        var n = 0
        var hits = 0
        var reciprocal = 0.0
        var rankSum = 0.0
        var percentileSum = 0.0
        fun add(rank: Double, poolSize: Int) {
            n++
            if (rank <= 10.0) hits++
            reciprocal += 1.0 / rank
            rankSum += rank
            percentileSum += rank / (poolSize + 1.0)
        }

        fun line(name: String) = "%-28s held=%d hit@10=%.3f MRR=%.4f meanPercentile=%.3f".format(
            name,
            n,
            if (n == 0) 0.0 else hits.toDouble() / n,
            if (n == 0) 0.0 else reciprocal / n,
            if (n == 0) 0.0 else percentileSum / n,
        )
    }

    private fun rank(
        variant: ScoringReplayHarness.Variant,
        profile: tachiyomi.domain.taste.model.TasteProfile,
        target: List<String>,
        pool: List<ScoringReplayHarness.Candidate>,
    ): Pair<Double, Int> {
        val ts = ScoringReplayHarness.score(variant, target, profile)
        var greater = 0
        var tied = 0
        for (c in pool) {
            val s = ScoringReplayHarness.score(variant, c.tags, profile)
            if (abs(s - ts) < 1e-9) {
                tied++
            } else if (s > ts) {
                greater++
            }
        }
        return (1.0 + greater + tied / 2.0) to pool.size
    }

    @Test
    fun `replay the variants on real ratings when an export is provided`() {
        val path = System.getenv("KFC_REAL_RATINGS_JSON")
        assumeTrue(!path.isNullOrBlank() && File(path).exists(), "no real ratings export provided")
        val root: JsonObject = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val now = root.getValue("now").jsonPrimitive.long
        val rated = (root.getValue("rated") as JsonArray).map {
            val o = it.jsonObject
            ScoringReplayHarness.Rated(
                tags = o.getValue("tags").jsonArray.map { t -> t.jsonPrimitive.content },
                ratingWeight = o.getValue("w").jsonPrimitive.double,
                ratedAtMillis = o.getValue("t").jsonPrimitive.long,
                loved = o.getValue("loved").jsonPrimitive.boolean,
            )
        }.sortedBy { it.ratedAtMillis }
        val candidates = (root.getValue("candidates") as JsonArray).map {
            ScoringReplayHarness.Candidate(it.jsonArray.map { t -> t.jsonPrimitive.content })
        }
        fun matched(tagCount: Int) = candidates.filter { abs(it.tags.size - tagCount) <= 1 }

        val report = StringBuilder()
        report.appendLine("rated=${rated.size} loved=${rated.count { it.loved }} candidates=${candidates.size}")
        report.appendLine("history spans ${(now - rated.first().ratedAtMillis) / DAY} days")
        report.appendLine("meanPercentile: 0.5 means no better than chance, lower is better")

        report.appendLine("\nLeave-one-out, every Love title, against ALL candidates")
        val all = variants.associateWith { Tally() }
        val fair = variants.associateWith { Tally() }
        for ((index, target) in rated.withIndex()) {
            if (!target.loved) continue
            val others = rated.filterIndexed { i, _ -> i != index }
            val pool = matched(target.tags.size)
            for (v in variants) {
                val profile = ScoringReplayHarness.profile(v, others, now)
                val (r, n) = rank(v, profile, target.tags, candidates)
                all.getValue(v).add(r, n)
                val (rm, nm) = rank(v, profile, target.tags, pool)
                fair.getValue(v).add(rm, nm)
            }
        }
        variants.forEach { report.appendLine(all.getValue(it).line(it.name)) }
        report.appendLine("\nLeave-one-out against MATCHED candidates (same tag count +-1)")
        variants.forEach { report.appendLine(fair.getValue(it).line(it.name)) }

        val cut = rated[(rated.size * 0.75).toInt()].ratedAtMillis
        val train = rated.filter { it.ratedAtMillis < cut }
        val later = rated.filter { it.loved && it.ratedAtMillis >= cut }
        val fwdAll = variants.associateWith { Tally() }
        val fwdFair = variants.associateWith { Tally() }
        for (v in variants) {
            val profile = ScoringReplayHarness.profile(v, train, cut)
            for (target in later) {
                val (r, n) = rank(v, profile, target.tags, candidates)
                fwdAll.getValue(v).add(r, n)
                val pool = matched(target.tags.size)
                val (rm, nm) = rank(v, profile, target.tags, pool)
                fwdFair.getValue(v).add(rm, nm)
            }
        }
        report.appendLine("\nForward (train on ${train.size} older ratings, rank ${later.size} later Loves), ALL candidates")
        variants.forEach { report.appendLine(fwdAll.getValue(it).line(it.name)) }
        report.appendLine("\nForward against MATCHED candidates")
        variants.forEach { report.appendLine(fwdFair.getValue(it).line(it.name)) }

        File("build/reports").mkdirs()
        File("build/reports/real-ratings-replay.txt").writeText(report.toString())
        println(report)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
