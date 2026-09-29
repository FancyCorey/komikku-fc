package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.TagPreference
import tachiyomi.domain.taste.model.TasteProfile
import kotlin.system.measureNanoTime

/**
 * Matched, local-only comparison for B4. The alternative orderings are test comparators; they are
 * deliberately not production policy. All variants consume the same scorer output and candidate
 * snapshot, so discovery coverage is not confused with ranking quality.
 */
class RecommendationRankingComparisonTest {

    private data class Row(
        val id: Long,
        val sourceId: Long,
        val confirmedGroup: String?,
        val tags: Set<String>,
        val score: Double,
        val blocked: Boolean,
        val independentlyLabelledRelevant: Boolean?,
    )

    private data class Metrics(
        val relevant: Int,
        val coveredTags: Int,
        val usefulNovelty: Int,
        val sourceCoverage: Int,
        val duplicateExcess: Int,
        val hardNegativeLeakage: Int,
        val medianNanosPerOperation: Long,
        val medianAllocatedBytesPerOperation: Long?,
    )

    private val profile = TasteProfile(
        learnedTagWeights = mapOf("fantasy" to 1.5, "romance" to 1.0, "comedy" to 0.5),
        explicitTagPreferences = mapOf(
            "action" to TagPreference.PREFER.value,
            "psychological" to TagPreference.DISLIKE.value,
        ),
        sourceAffinity = mapOf(2L to 0.25),
        blockedGroups = setOf("harem"),
    )

    // Fixture judgments are declared independently of the scorer under test. Null means unknown,
    // not irrelevant, which keeps missing or unfamiliar metadata out of the negative class.
    private val relevanceLabels = mapOf(
        1L to true,
        2L to true,
        3L to true,
        4L to true,
        5L to true,
        6L to true,
        7L to true,
        8L to null,
        9L to null,
        10L to false,
        11L to true,
        12L to false,
    )

    @Test
    fun `matched ranking comparison preserves safety and measures useful coverage`() {
        val snapshot = listOf(
            manga(1, listOf("Action", "Fantasy")),
            manga(2, listOf("Action", "Fantasy")),
            manga(3, listOf("Action", "Fantasy")),
            manga(4, listOf("Action", "Fantasy")),
            manga(5, listOf("Action", "Romance")),
            manga(6, listOf("Romance", "Comedy")),
            manga(7, listOf("Comedy")),
            manga(8, emptyList()),
            manga(9, listOf("Slice of Life")),
            manga(10, listOf("Action", "Psychological")),
            manga(11, listOf("Action", "Fantasy")),
            manga(12, listOf("Action", "Harem")),
        )
        val confirmedGroups = mapOf(1L to "same-series", 2L to "same-series", 11L to "same-series")
        val scored = snapshot.map { candidate ->
            val result = PersonalRecommendationScorer.score(candidate, profile, emptyMap())
            Row(
                id = candidate.id,
                sourceId = candidate.source,
                confirmedGroup = confirmedGroups[candidate.id],
                tags = candidate.genre.orEmpty().map(String::lowercase).toSet(),
                score = result.score,
                blocked = result.blocked,
                independentlyLabelledRelevant = relevanceLabels[candidate.id],
            )
        }
        val eligible = PersonalRecommendationScorer.rankCandidates(
            candidates = snapshot,
            profile = profile,
            aliasMap = emptyMap(),
            limit = snapshot.size,
        ).map { ranked -> scored.first { it.id == ranked.manga.id } }

        val variants = listOf(
            "current" to { current(eligible) },
            "topic" to { topicCoverage(eligible) },
            "mmr" to { sparseMmr(eligible) },
        )
        val expectedTopic = topicCoverage(eligible)
        val expectedMmr = sparseMmr(eligible)
        val missingTagCount = scored.count { it.tags.isEmpty() }
        println("B4 corpus candidates=${scored.size} eligible=${eligible.size} missingTags=$missingTagCount")
        val performance = measurePerformance(variants)
        val measured = variants.map { (name, rank) ->
            val metrics = measure(rank, scored, performance.getValue(name))
            println(
                "B4 $name relevant=${metrics.relevant} coverage=${metrics.coveredTags} " +
                    "novelty=${metrics.usefulNovelty} sources=${metrics.sourceCoverage} " +
                    "duplicateExcess=${metrics.duplicateExcess} " +
                    "hardNegativeLeakage=${metrics.hardNegativeLeakage} " +
                    "medianNanosPerOperation=${metrics.medianNanosPerOperation} " +
                    "medianAllocatedBytesPerOperation=${metrics.medianAllocatedBytesPerOperation ?: "unavailable"}",
            )
            name to metrics
        }.toMap()

        assertEquals(0, measured.getValue("current").hardNegativeLeakage)
        assertEquals(0, measured.getValue("topic").hardNegativeLeakage)
        assertEquals(0, measured.getValue("mmr").hardNegativeLeakage)
        assertEquals(measured.getValue("current").relevant, measured.getValue("topic").relevant)
        assertEquals(measured.getValue("current").relevant, measured.getValue("mmr").relevant)
        assertEquals(5, measured.getValue("current").relevant)
        assertTrue(measured.getValue("topic").coveredTags >= measured.getValue("current").coveredTags)
        assertTrue(measured.getValue("mmr").coveredTags >= measured.getValue("current").coveredTags)
        assertTrue(measured.values.all { it.sourceCoverage > 0 })
        assertTrue(measured.values.all { it.duplicateExcess >= 0 && it.usefulNovelty >= 0 })
        assertEquals(expectedTopic, topicCoverage(eligible), "topic-coverage tie breaks must be stable")
        assertEquals(expectedMmr, sparseMmr(eligible), "MMR tie breaks must be stable")
    }

    private fun current(rows: List<Row>): List<Row> = rows
        .sortedWith(compareByDescending<Row> { it.score }.thenBy { it.id })
        .take(DISPLAY_LIMIT)

    private fun measure(rank: () -> List<Row>, allRows: List<Row>, performance: Performance): Metrics {
        val rows = rank()
        val relevantRows = rows.filter { it.independentlyLabelledRelevant == true }
        val covered = relevantRows.flatMap { it.tags }.filter { it in COVERAGE_TAGS }.toSet().size
        val seenTags = mutableSetOf<String>()
        val novelty = relevantRows.count { row ->
            val newTags = row.tags intersect COVERAGE_TAGS subtract seenTags
            seenTags += row.tags
            newTags.isNotEmpty()
        }
        val groupCounts = rows.mapNotNull { it.confirmedGroup }.groupingBy { it }.eachCount()
        return Metrics(
            relevant = relevantRows.size,
            coveredTags = covered,
            usefulNovelty = novelty,
            sourceCoverage = rows.map { it.sourceId }.distinct().size,
            duplicateExcess = groupCounts.values.sumOf { (it - 1).coerceAtLeast(0) },
            hardNegativeLeakage = rows.count { row -> allRows.first { it.id == row.id }.blocked },
            medianNanosPerOperation = performance.medianNanosPerOperation,
            medianAllocatedBytesPerOperation = performance.medianAllocatedBytesPerOperation,
        )
    }

    private data class Performance(
        val medianNanosPerOperation: Long,
        val medianAllocatedBytesPerOperation: Long?,
    )

    private fun measurePerformance(variants: List<Pair<String, () -> List<Row>>>): Map<String, Performance> {
        repeat(WARMUP_OPERATIONS) {
            variants.forEach { (_, rank) -> rank() }
        }

        val elapsedSamples = variants.associate { it.first to mutableListOf<Long>() }
        val allocationSamples = variants.associate { it.first to mutableListOf<Long>() }
        val allocatedBytes = currentThreadAllocationReader()

        repeat(MEASUREMENT_ROUNDS) { round ->
            val shift = round % variants.size
            val orderedVariants = variants.drop(shift) + variants.take(shift)
            orderedVariants.forEach { (name, rank) ->
                var finalRows = emptyList<Row>()
                val beforeBytes = allocatedBytes?.invoke()
                val elapsed = measureNanoTime {
                    repeat(OPERATIONS_PER_ROUND) { finalRows = rank() }
                }
                check(finalRows.isNotEmpty())
                elapsedSamples.getValue(name) += elapsed / OPERATIONS_PER_ROUND
                val afterBytes = allocatedBytes?.invoke()
                if (beforeBytes != null && afterBytes != null && afterBytes >= beforeBytes) {
                    allocationSamples.getValue(name) += (afterBytes - beforeBytes) / OPERATIONS_PER_ROUND
                }
            }
        }

        return variants.associate { (name, _) ->
            name to Performance(
                medianNanosPerOperation = elapsedSamples.getValue(name).median(),
                medianAllocatedBytesPerOperation = allocationSamples.getValue(name).takeIf { it.isNotEmpty() }?.median(),
            )
        }
    }

    private fun currentThreadAllocationReader(): (() -> Long)? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null)
        val allocationBean = Class.forName("com.sun.management.ThreadMXBean")
        val supported = allocationBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
        val enabled = allocationBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean
        if (!supported || !enabled) {
            null
        } else {
            val allocationMethod = allocationBean.getMethod("getCurrentThreadAllocatedBytes")
            val readBytes: () -> Long = { (allocationMethod.invoke(bean) as Number).toLong() }
            readBytes
        }
    }.getOrNull()

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private fun topicCoverage(rows: List<Row>): List<Row> {
        val remaining = rows.toMutableList()
        val selected = mutableListOf<Row>()
        val covered = mutableSetOf<String>()
        repeat(minOf(DISPLAY_LIMIT, remaining.size)) {
            val next = remaining.maxWithOrNull(
                compareBy<Row> { it.score + (it.tags intersect COVERAGE_TAGS subtract covered).size * COVERAGE_BONUS }
                    .thenByDescending { it.score }
                    .thenByDescending { -it.id },
            ) ?: return@repeat
            remaining.remove(next)
            selected += next
            covered += next.tags
        }
        return selected
    }

    private fun sparseMmr(rows: List<Row>): List<Row> {
        val remaining = rows.toMutableList()
        val selected = mutableListOf<Row>()
        repeat(minOf(DISPLAY_LIMIT, remaining.size)) {
            val next = remaining.maxWithOrNull(
                compareBy<Row> { row ->
                    val similarity = selected.maxOfOrNull { other -> jaccard(row.tags, other.tags) } ?: 0.0
                    row.score - MMR_PENALTY * similarity
                }.thenByDescending { it.score }.thenByDescending { -it.id },
            ) ?: return@repeat
            remaining.remove(next)
            selected += next
        }
        return selected
    }

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        val union = left union right
        return if (union.isEmpty()) 0.0 else (left intersect right).size.toDouble() / union.size
    }

    private fun manga(id: Long, tags: List<String>): Manga = Manga.create().copy(
        id = id,
        source = if (id % 3L == 0L) {
            3L
        } else if (id % 2L == 0L) {
            2L
        } else {
            1L
        },
        url = "/b4-$id",
        ogTitle = "B4 candidate $id",
        ogGenre = tags,
    )

    private companion object {
        const val DISPLAY_LIMIT = 5
        const val WARMUP_OPERATIONS = 100
        const val MEASUREMENT_ROUNDS = 7
        const val OPERATIONS_PER_ROUND = 80
        const val COVERAGE_BONUS = 0.35
        const val MMR_PENALTY = 0.75
        val COVERAGE_TAGS = setOf("action", "fantasy", "romance", "comedy")
    }
}
