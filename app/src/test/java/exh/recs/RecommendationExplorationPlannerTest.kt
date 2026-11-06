package exh.recs

import exh.recs.RecommendationExplorationPlanner.LovedTitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.25 options G and H: a few "because you loved X" and "something different" searches, bounded and stable. */
class RecommendationExplorationPlannerTest {

    private val loved = listOf(LovedTitle("Solo Leveling", setOf("action")), LovedTitle("Tower of God", setOf("fantasy")), LovedTitle("Omniscient Reader", setOf("action")))
    private val weights = mapOf("action" to 9.0, "fantasy" to 7.0, "drama" to 0.0, "psychological" to 0.3, "comedy" to -6.0)
    private val liked = listOf(
        setOf("action", "psychological", "thriller"),
        setOf("fantasy", "psychological", "thriller"),
        setOf("action", "comedy"),
        setOf("romance", "school"),
    )

    @Test
    fun `the plan asks about a loved title, then something different, then another loved title`() {
        val plan = RecommendationExplorationPlanner.plan(loved, liked, weights, rotation = 0)
        assertEquals(
            listOf(RecommendationDiscoveryLane.BECAUSE_LOVED, RecommendationDiscoveryLane.SOMETHING_DIFFERENT, RecommendationDiscoveryLane.BECAUSE_LOVED),
            plan.requests.map { it.lane },
        )
        assertEquals("Solo Leveling", plan.requests[0].query)
        assertEquals("Tower of God", plan.requests[2].query)
    }

    @Test
    fun `rotation changes which loved title is asked about, and wraps around`() {
        assertEquals("Tower of God", RecommendationExplorationPlanner.plan(loved, liked, weights, rotation = 1).requests[0].query)
        assertEquals("Solo Leveling", RecommendationExplorationPlanner.plan(loved, liked, weights, rotation = 3).requests[0].query)
        assertEquals("Omniscient Reader", RecommendationExplorationPlanner.plan(loved, liked, weights, rotation = -1).requests[0].query)
    }

    @Test
    fun `exploration tags are unknown, appear with the strongest tags at least twice, and are never already strong or disliked`() {
        val tags = RecommendationExplorationPlanner.explorationTags(liked, strongest = setOf("action", "fantasy"), learnedWeights = weights)
        assertEquals(listOf("psychological", "thriller"), tags)
        assertTrue("comedy" !in tags, "a tag the user has a clear opinion on is not new")
        assertTrue("romance" !in tags, "a tag that never appears with the favourites is not near them")
    }

    @Test
    fun `the plan never exceeds the attempt budget and is empty with nothing to ask about`() {
        assertEquals(1, RecommendationExplorationPlanner.plan(loved, liked, weights, 0, maxAttempts = 1).attempts)
        assertEquals(0, RecommendationExplorationPlanner.plan(loved, liked, weights, 0, maxAttempts = 0).attempts)
        assertEquals(0, RecommendationExplorationPlanner.plan(emptyList(), emptyList(), emptyMap(), 0).attempts)
        assertEquals(1, RecommendationExplorationPlanner.plan(listOf(loved[0]), emptyList(), weights, 0).attempts, "one loved title, no near tags")
    }

    @Test
    fun `the budget is off while saving data or switched off, and capped at three searches`() {
        assertEquals(0, RecommendationExplorationPlanner.attemptBudget(enabled = false, dataSaving = false, candidateSourceCount = 10))
        assertEquals(0, RecommendationExplorationPlanner.attemptBudget(enabled = true, dataSaving = true, candidateSourceCount = 10))
        assertEquals(0, RecommendationExplorationPlanner.attemptBudget(enabled = true, dataSaving = false, candidateSourceCount = 0))
        assertEquals(3, RecommendationExplorationPlanner.attemptBudget(enabled = true, dataSaving = false, candidateSourceCount = 40))
        assertEquals(2, RecommendationExplorationPlanner.attemptBudget(enabled = true, dataSaving = false, candidateSourceCount = 2))
    }

    @Test
    fun `about one card in six per search, between one and three`() {
        assertEquals(1, RecommendationExplorationPlanner.slots(3))
        assertEquals(3, RecommendationExplorationPlanner.slots(18))
        assertEquals(3, RecommendationExplorationPlanner.slots(60))
        assertEquals(2, RecommendationExplorationPlanner.slots(12))
    }

    @Test
    fun `the lanes are stored under their own keys and are exempt from the positive tag gate`() {
        assertEquals(RecommendationDiscoveryLane.BECAUSE_LOVED, RecommendationDiscoveryLane.fromStorageKey("BECAUSE_LOVED"))
        assertEquals(RecommendationDiscoveryLane.SOMETHING_DIFFERENT, RecommendationDiscoveryLane.fromStorageKey("SOMETHING_DIFFERENT"))
        assertTrue(RecommendationExplorationPlanner.exemptFromPositiveGate(RecommendationDiscoveryLane.BECAUSE_LOVED))
        assertEquals(false, RecommendationExplorationPlanner.exemptFromPositiveGate(RecommendationDiscoveryLane.PERSONALIZED))
        assertEquals(false, RecommendationExplorationPlanner.exemptFromPositiveGate(RecommendationDiscoveryLane.LATEST_CATALOGUE))
    }

    @Test
    fun `the refresh claims the searches in order, shares one budget, and the row says what it includes`() {
        val model = File("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt").readText()
        assertTrue(model.contains("state.explorationAttemptsUsed.getAndIncrement()"))
        assertTrue(model.contains("exploration = buildExplorationPlan(profile, aliasMap, dataSaving, candidateSources.size)"))
        assertTrue(model.contains("val candidatePool = allNew + latestAdditive + explorationAdditive"))
        val tab = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText()
        assertTrue(tab.contains("rec_row_includes_because_loved") && tab.contains("rec_row_includes_something_different"))
    }
}
