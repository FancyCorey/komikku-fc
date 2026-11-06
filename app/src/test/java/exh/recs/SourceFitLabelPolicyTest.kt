package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.SourceReactions

/** v0.8.25 option E: a source's fit label also weighs how the user reacted to its titles, with small samples shrunk. */
class SourceFitLabelPolicyTest {

    @Test
    fun `no reactions or too few reactions change nothing`() {
        SourceFitLabel.entries.forEach { label ->
            assertEquals(label, SourceFitLabelPolicy.withReactions(label, null))
            assertEquals(label, SourceFitLabelPolicy.withReactions(label, SourceReactions(positive = 3, negative = 0)))
        }
    }

    @Test
    fun `a small sample is pulled toward neutral instead of reading as a verdict`() {
        assertEquals(0.5, SourceReactions(0, 0).shrunkRate, 1e-9)
        assertTrue(SourceReactions(2, 0).shrunkRate < 0.7, "two likes are not a verdict")
        assertTrue(SourceReactions(12, 0).shrunkRate > 0.85)
        assertTrue(SourceReactions(0, 12).shrunkRate < 0.15)
    }

    @Test
    fun `many positive reactions lift a mixed source to a good fit`() {
        val loved = SourceReactions(positive = 14, negative = 2)
        assertEquals(SourceFitLabel.GoodFit, SourceFitLabelPolicy.withReactions(SourceFitLabel.Mixed, loved))
        assertEquals(SourceFitLabel.GoodFit, SourceFitLabelPolicy.withReactions(SourceFitLabel.NoMatchesRecently, loved))
        assertEquals(SourceFitLabel.GreatFit, SourceFitLabelPolicy.withReactions(SourceFitLabel.GreatFit, loved), "never lowers")
    }

    @Test
    fun `many negative reactions lower a good source to mixed`() {
        val disliked = SourceReactions(positive = 1, negative = 12)
        assertEquals(SourceFitLabel.Mixed, SourceFitLabelPolicy.withReactions(SourceFitLabel.GreatFit, disliked))
        assertEquals(SourceFitLabel.Mixed, SourceFitLabelPolicy.withReactions(SourceFitLabel.GoodFit, disliked))
        assertEquals(SourceFitLabel.NoMatchesRecently, SourceFitLabelPolicy.withReactions(SourceFitLabel.NoMatchesRecently, disliked))
    }

    @Test
    fun `errors and missing data are never overridden by reactions`() {
        val loved = SourceReactions(positive = 20, negative = 0)
        assertEquals(SourceFitLabel.OftenErrors, SourceFitLabelPolicy.withReactions(SourceFitLabel.OftenErrors, loved))
        assertEquals(SourceFitLabel.TooLittleData, SourceFitLabelPolicy.withReactions(SourceFitLabel.TooLittleData, loved))
    }

    @Test
    fun `the stats label uses the attached reactions and the stored form does not carry them`() {
        val stats = SourceFitStats(
            sourceId = 1, runCount = 10, shownCount = 1, noMatchCount = 0, filteredOutCount = 0, errorCount = 0,
            hiddenByDuplicateCount = 0, totalVisibleCandidates = 2, lastSuccessAt = 0, lastErrorAt = 0, updatedAt = 0,
        )
        assertEquals(SourceFitLabel.Mixed, stats.fitLabel)
        val withLove = stats.copy(reactions = SourceReactions(positive = 16, negative = 1))
        assertEquals(SourceFitLabel.GoodFit, withLove.fitLabel)
        assertEquals(
            SourceFitStatsStore.serialize(listOf(stats)),
            SourceFitStatsStore.serialize(listOf(withLove)),
            "reactions are derived on screen, never persisted",
        )
    }
}
