package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.TasteProfile

// KMK --> v0.8.24 recent-reading profile in the scorer
class PersonalRecommendationScorerRecentReadingTest {

    private fun candidate(id: Long, vararg genres: String) =
        Manga.create().copy(id = id, source = 1L, url = "/c$id", ogGenre = genres.toList())

    private fun profile(
        learned: Map<String, Double> = emptyMap(),
        recent: Map<String, Double> = emptyMap(),
        explicit: Map<String, Int> = emptyMap(),
        blocked: Set<String> = emptySet(),
    ) = TasteProfile(
        learnedTagWeights = learned,
        explicitTagPreferences = explicit,
        sourceAffinity = emptyMap(),
        blockedGroups = blocked,
        recentTagWeights = recent,
    )

    @Test
    fun `a recent weight adds to the score, counts as positive evidence and explains itself`() {
        val scored = PersonalRecommendationScorer.score(candidate(1, "romance"), profile(recent = mapOf("romance" to 3.0)), emptyMap())

        assertEquals(3.0, scored.score, 0.0)
        assertTrue("romance" in scored.matchedGroups)
        assertTrue(scored.reasons.any { it.startsWith("Recently read tags") })
        assertFalse(scored.blocked)
    }

    @Test
    fun `recent reading outranks an equal older Love rating and the older rating still counts`() {
        val loveWeight = 2.0
        val prof = profile(learned = mapOf("action" to loveWeight), recent = mapOf("romance" to 3.0))
        val ranked = PersonalRecommendationScorer.rankCandidates(
            listOf(candidate(1, "action"), candidate(2, "romance"), candidate(3, "mystery")),
            prof,
            emptyMap(),
            limit = 10,
        )

        assertEquals(listOf(2L, 1L), ranked.map { it.manga.id }, "recent first, long-term second, no evidence excluded")
    }

    @Test
    fun `a blocked tag still rejects before any recent weight is considered`() {
        val prof = profile(recent = mapOf("horror" to 6.0), blocked = setOf("horror"))

        val scored = PersonalRecommendationScorer.score(candidate(1, "horror", "action"), prof, emptyMap())

        assertTrue(scored.blocked)
        assertEquals(0.0, scored.score, 0.0)
    }

    @Test
    fun `a recent weight is counted once per tag group even when two genres share a group`() {
        val aliasMap = mapOf("wuxia" to "martial arts")
        val scored = PersonalRecommendationScorer.score(
            candidate(1, "Martial Arts", "Wuxia"),
            profile(recent = mapOf("martial arts" to 2.5)),
            aliasMap,
        )

        assertEquals(2.5, scored.score, 0.0)
    }

    @Test
    fun `recent reading alone makes a profile non-empty and an empty recent map changes nothing`() {
        assertFalse(profile(recent = mapOf("action" to 1.0)).isEmpty())
        assertTrue(profile().isEmpty())
        val without = PersonalRecommendationScorer.score(candidate(1, "action"), profile(learned = mapOf("action" to 1.0)), emptyMap())
        val withEmptyRecent = PersonalRecommendationScorer.score(
            candidate(1, "action"),
            profile(learned = mapOf("action" to 1.0), recent = emptyMap()),
            emptyMap(),
        )
        assertEquals(without.score, withEmptyRecent.score, 0.0)
    }
}
// KMK <--
