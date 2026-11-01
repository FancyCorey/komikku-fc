package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.TasteProfile

/** v0.8.25: a title with many tags cannot outscore a focused one just by matching more of them. */
class PersonalRecommendationScorerTagCountTest {

    private val profile = TasteProfile(
        learnedTagWeights = mapOf("a" to 4.0, "b" to 4.0, "c" to 1.0, "d" to 1.0, "e" to 1.0),
        explicitTagPreferences = emptyMap(),
        sourceAffinity = emptyMap(),
        blockedGroups = emptySet(),
    )

    private fun score(vararg tags: String): Double =
        PersonalRecommendationScorer.score(Manga.create().copy(ogGenre = tags.toList()), profile, emptyMap()).score

    @Test
    fun `titles with up to five tags keep their full score`() {
        assertEquals(1.0, PersonalRecommendationScorer.tagCountFactor(1), 0.0)
        assertEquals(1.0, PersonalRecommendationScorer.tagCountFactor(5), 0.0)
        assertEquals(8.0, score("a", "b", "x1", "x2", "x3"), 1e-9)
    }

    @Test
    fun `the factor shrinks smoothly after five tags`() {
        assertEquals(1.0 / Math.sqrt(2.0), PersonalRecommendationScorer.tagCountFactor(10), 1e-9)
        assertEquals(0.5, PersonalRecommendationScorer.tagCountFactor(20), 1e-9)
        assertTrue(PersonalRecommendationScorer.tagCountFactor(6) > PersonalRecommendationScorer.tagCountFactor(7))
    }

    @Test
    fun `a focused strong match outranks a long list of weak matches`() {
        val focused = score("a", "b", "x1", "x2")
        val sprawling = score("c", "d", "e", *(1..17).map { "y$it" }.toTypedArray())
        assertTrue(focused > sprawling, "focused=$focused sprawling=$sprawling")
    }

    @Test
    fun `a repeated tag is counted once`() {
        assertEquals(score("a"), score("a", "A".lowercase(), "a"), 1e-9)
    }
}
