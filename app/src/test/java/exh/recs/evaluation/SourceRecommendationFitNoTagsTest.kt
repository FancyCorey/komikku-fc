package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.RecommendationQualityVerdict

/** v0.8.25 option F: a source whose search results carry no genres is "cannot judge", not Weak or Mixed. */
class SourceRecommendationFitNoTagsTest {

    private fun outcome(raw: Int, weak: Int, visible: Int) = SourceRecommendationFitProbeOutcome(
        queryCount = 3,
        querySuccessCount = 3,
        rawResultCount = raw,
        visibleCandidateCount = visible,
        weakMetadataCandidateCount = weak,
    )

    @Test
    fun `mostly tagless results with a low score cannot be judged instead of weak`() {
        assertEquals(RecommendationQualityLabel.NO_TAGS, outcome(raw = 20, weak = 16, visible = 1).label())
    }

    @Test
    fun `the same low score with tagged results is still weak or mixed`() {
        val label = outcome(raw = 20, weak = 2, visible = 1).label()
        assertEquals(true, label == RecommendationQualityLabel.WEAK || label == RecommendationQualityLabel.MIXED)
    }

    @Test
    fun `tagless results never override a source that scored well anyway`() {
        val label = outcome(raw = 20, weak = 14, visible = 12).label()
        assertEquals(true, label == RecommendationQualityLabel.GREAT || label == RecommendationQualityLabel.GOOD)
    }

    @Test
    fun `a handful of results is too few to blame the source's metadata`() {
        assertEquals(false, outcome(raw = 2, weak = 2, visible = 0).resultsCarryNoTags)
    }

    @Test
    fun `an unknown stored verdict still reads as too little evidence and the new one round trips`() {
        assertEquals(RecommendationQualityVerdict.NO_TAGS, RecommendationQualityVerdict.fromSerialized("no_tags"))
        assertEquals(RecommendationQualityVerdict.TOO_LITTLE_EVIDENCE, RecommendationQualityVerdict.fromSerialized("future_verdict"))
    }

    @Test
    fun `the display policy keeps it out of the automatic skip of unusable sources`() {
        val src = java.io.File("src/main/java/exh/recs/evaluation/SourceAutoRecheckPolicy.kt").readText()
        assertEquals(false, src.contains("RecommendationQualityVerdict.NO_TAGS"))
        assertEquals(
            CompatibilityDisplayState.NO_TAGS,
            when (RecommendationQualityVerdict.NO_TAGS) {
                RecommendationQualityVerdict.NO_TAGS -> CompatibilityDisplayState.NO_TAGS
                else -> CompatibilityDisplayState.NO_MATCHES
            },
        )
    }
}
