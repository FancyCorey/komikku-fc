package exh.recs

import exh.recs.ScoringReplayHarness.Variant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the replay harness over a synthetic reader and writes `build/reports/scoring-replay.txt`.
 *
 * What this shows: on a library with a hidden taste, taste drift, a power-law tag distribution and some tag-heavy
 * titles, the 0.8.25 weighting ranks a held-out loved title at least as well as the pre-0.8.25 arithmetic. What it does
 * not show: accuracy on real libraries. The corpus is generated and its "appeal" rule is an assumption (summed
 * preference over sqrt of tag count), so the numbers compare the two algorithms; they are not a promise to any user.
 */
class ScoringReplayHarnessTest {

    private val now = 1_800_000_000_000L
    private val year = 365L * 24 * 60 * 60 * 1000

    private data class Row(
        val seed: Long,
        val legacy: ScoringReplayHarness.Result,
        val current: ScoringReplayHarness.Result,
        val weightsOnly: ScoringReplayHarness.Result,
        val scorerOnly: ScoringReplayHarness.Result,
        val ablations: Map<Variant, ScoringReplayHarness.Result>,
    )

    private fun run(libraryPerSeed: Int = 140): List<Row> = (1L..40L).map { seed ->
        val reader = ScoringReplayHarness.SyntheticReader(seed)
        val rated = reader.ratedLibrary(libraryPerSeed, now)
        val distractors = reader.distractors(99)
        Row(
            seed,
            ScoringReplayHarness.replay(Variant.LEGACY, rated, distractors, now, year),
            ScoringReplayHarness.replay(Variant.CURRENT, rated, distractors, now, year),
            ScoringReplayHarness.replay(Variant.NEW_WEIGHTS_PLAIN_SUM, rated, distractors, now, year),
            ScoringReplayHarness.replay(Variant.OLD_WEIGHTS_NEW_SCORER, rated, distractors, now, year),
            listOf(
                Variant.WITHOUT_RARITY, Variant.WITH_AGE, Variant.WITHOUT_SMOOTH_SATURATION, Variant.WITHOUT_DISLIKE_DAMPING,
            ).associateWith { ScoringReplayHarness.replay(it, rated, distractors, now, year) },
        )
    }

    @Test
    fun `the current weighting ranks a held-out loved title at least as well as the legacy arithmetic`() {
        val rows = run()
        fun mean(select: (ScoringReplayHarness.Result) -> Double, pick: (Row) -> ScoringReplayHarness.Result) =
            rows.map { select(pick(it)) }.average()
        val report = buildString {
            appendLine("For You scoring replay (synthetic corpus, 40 seeds, 99 distractors, loved titles from the last 12 months held out)")
            appendLine("variant                         hit@10   MRR     mean rank   held-out")
            listOf<Pair<String, (Row) -> ScoringReplayHarness.Result>>(
                "legacy (before 0.8.25)" to { it.legacy },
                "current (0.8.25)" to { it.current },
                "  new weights, plain sum" to { it.weightsOnly },
                "  old weights, new scorer" to { it.scorerOnly },
                "current without rarity" to { it.ablations.getValue(Variant.WITHOUT_RARITY) },
                "current with age decay" to { it.ablations.getValue(Variant.WITH_AGE) },
                "current without smooth cap" to { it.ablations.getValue(Variant.WITHOUT_SMOOTH_SATURATION) },
                "current without dislike damp" to { it.ablations.getValue(Variant.WITHOUT_DISLIKE_DAMPING) },
            ).forEach { (name, pick) ->
                appendLine(
                    "%-30s  %-6.3f  %-6.3f  %-9.1f   %d".format(
                        name, mean({ it.hitAt10 }, pick), mean({ it.meanReciprocalRank }, pick), mean({ it.meanRank }, pick),
                        rows.sumOf { pick(it).heldOut },
                    ),
                )
            }
            val better = rows.count { it.current.meanRank < it.legacy.meanRank }
            val worse = rows.count { it.current.meanRank > it.legacy.meanRank }
            appendLine("seeds where current ranks the held-out title better / worse / equal: $better / $worse / ${rows.size - better - worse}")
        }
        File("build/reports").mkdirs()
        File("build/reports/scoring-replay.txt").writeText(report)
        println(report)

        assertTrue(rows.all { it.legacy.heldOut > 0 && it.current.heldOut > 0 }, "every seed must hold out some loved titles")
        val legacyRank = rows.map { it.legacy.meanRank }.average()
        val currentRank = rows.map { it.current.meanRank }.average()
        assertTrue(currentRank <= legacyRank, "current mean rank $currentRank should not be worse than legacy $legacyRank")
        val legacyMrr = rows.map { it.legacy.meanReciprocalRank }.average()
        val currentMrr = rows.map { it.current.meanReciprocalRank }.average()
        assertTrue(currentMrr >= legacyMrr, "current MRR $currentMrr should not be worse than legacy $legacyMrr")
    }

    @Test
    fun `replay is deterministic`() {
        assertTrue(run().map { it.current } == run().map { it.current })
    }
}
