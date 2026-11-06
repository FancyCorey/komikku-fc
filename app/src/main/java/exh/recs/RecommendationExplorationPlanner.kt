package exh.recs

import java.util.Locale
import kotlin.math.abs

// KMK v0.8.25 -->
/**
 * Pure planning for the two exploration lanes added to For You:
 *
 * - BECAUSE_LOVED: a plain title search for one of the titles the user loved most recently, so "more like this one" can
 *   be answered by the source itself, which knows its own titles, instead of by shared tags alone.
 * - SOMETHING_DIFFERENT: a text search for tags that appear together with the user's strongest tags but that the user has
 *   not shown a clear opinion on yet, to get out of the filter bubble.
 *
 * Both are small and additive: a handful of source searches per refresh (shared across the refresh, never per source), a
 * few cards each, off while saving data and switchable in settings. Nothing is decided here about network or storage.
 */
internal object RecommendationExplorationPlanner {

    data class LovedTitle(val title: String, val tags: Set<String>)

    data class Request(val lane: RecommendationDiscoveryLane, val query: String)

    /** The searches one refresh may spend, in order. Each is claimed by whichever source asks next. */
    data class Plan(val requests: List<Request>) {
        val attempts: Int get() = requests.size

        fun requestFor(index: Int): Request? = requests.getOrNull(index)

        companion object {
            val NONE = Plan(emptyList())
        }
    }

    /** At most this many exploration searches per refresh, however many sources there are. */
    const val MAX_ATTEMPTS = 3

    /** A tag counts as "near" the user's favourites after appearing with their strongest tags in this many liked titles. */
    const val MIN_COOCCURRENCE = 2

    /** A tag is "not yet known" while its learned weight stays within this distance of zero. */
    const val UNKNOWN_WEIGHT_LIMIT = 1.0

    private const val STRONGEST_TAGS = 5
    private const val EXPLORATION_TAGS = 2

    /** Roughly one card in six, never fewer than one or more than three, per source that ran an exploration search. */
    fun slots(displayLimit: Int): Int = (displayLimit / 6).coerceIn(1, 3)

    /** Cards from these lanes may have no taste evidence yet, by design, so they are exempt from the positive-tag gate. */
    fun exemptFromPositiveGate(lane: RecommendationDiscoveryLane): Boolean = lane.isExploration

    fun plan(
        lovedByRecency: List<LovedTitle>,
        positiveTagSets: List<Set<String>>,
        learnedWeights: Map<String, Double>,
        rotation: Int,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Plan {
        if (maxAttempts <= 0) return Plan.NONE
        val usableLoved = lovedByRecency.filter { it.title.isNotBlank() }.distinctBy { it.title.lowercase(Locale.ROOT) }
        val strongest = learnedWeights.filterValues { it >= UNKNOWN_WEIGHT_LIMIT }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(STRONGEST_TAGS)
            .map { it.key }
            .toSet()
        val exploration = explorationTags(positiveTagSets, strongest, learnedWeights)

        fun loved(offset: Int): Request? = usableLoved.takeIf { it.isNotEmpty() }?.let {
            Request(RecommendationDiscoveryLane.BECAUSE_LOVED, it[Math.floorMod(rotation + offset, it.size)].title)
        }

        val different = exploration.takeIf { it.isNotEmpty() }
            ?.let { Request(RecommendationDiscoveryLane.SOMETHING_DIFFERENT, it.joinToString(" ")) }
        val second = if (usableLoved.size >= 2) loved(1) else null
        return Plan(listOfNotNull(loved(0), different, second).take(maxAttempts))
    }

    /**
     * Tags that show up in liked titles together with the strongest tags, that are not themselves strong, and that the user
     * has no clear opinion about. Ordered by how often they co-occur, then alphabetically so the choice is stable.
     */
    internal fun explorationTags(
        positiveTagSets: List<Set<String>>,
        strongest: Set<String>,
        learnedWeights: Map<String, Double>,
    ): List<String> {
        if (strongest.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        for (tags in positiveTagSets) {
            if (tags.none { it in strongest }) continue
            for (tag in tags) {
                if (tag in strongest || tag.length < 3) continue
                if (abs(learnedWeights[tag] ?: 0.0) >= UNKNOWN_WEIGHT_LIMIT) continue
                counts.merge(tag, 1, Int::plus)
            }
        }
        return counts.filterValues { it >= MIN_COOCCURRENCE }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(EXPLORATION_TAGS)
            .map { it.key }
    }

    /** Whether this refresh gets exploration searches at all. */
    fun attemptBudget(enabled: Boolean, dataSaving: Boolean, candidateSourceCount: Int): Int =
        if (!enabled || dataSaving || candidateSourceCount <= 0) 0 else minOf(MAX_ATTEMPTS, candidateSourceCount)
}
// KMK <--
