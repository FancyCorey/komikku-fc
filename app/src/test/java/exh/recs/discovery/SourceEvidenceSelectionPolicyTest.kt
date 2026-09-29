package exh.recs.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.RecommendationQualityVerdict
import tachiyomi.domain.taste.model.SourceEvaluation
import tachiyomi.domain.taste.model.SourceEvaluationMetadataConfidence
import tachiyomi.domain.taste.model.SourceEvaluationVerdict
import tachiyomi.domain.taste.model.SourceRecommendationFit

class SourceEvidenceSelectionPolicyTest {
    @Test
    fun `evaluation selection prefers version then timestamp over input order`() {
        val older = evaluation(version = 3, evaluatedAt = 20, sourceName = "older")
        val newer = evaluation(version = 4, evaluatedAt = 10, sourceName = "newer")

        assertEquals("newer", SourceEvidenceSelectionPolicy.latestEvaluations(listOf(newer, older))["key"]!!.sourceName)
    }

    @Test
    fun `fit selection prefers timestamp when versions match`() {
        val older = fit(evaluatedAt = 20, score = 0.2)
        val newer = fit(evaluatedAt = 30, score = 0.8)

        assertEquals(0.8, SourceEvidenceSelectionPolicy.latestFits(listOf(newer, older))["key"]!!.recommendationQualityScore)
    }

    @Test
    fun `exact evidence ties use a stable final value instead of input order`() {
        val first = fit(evaluatedAt = 20, score = 0.2)
        val second = first.copy(reasonsJson = "[\"other\"]", recommendationQualityScore = 0.9)

        val forward = SourceEvidenceSelectionPolicy.latestFits(listOf(first, second))["key"]
        val reversed = SourceEvidenceSelectionPolicy.latestFits(listOf(second, first))["key"]

        assertEquals(forward, reversed)
    }

    private fun evaluation(version: Int, evaluatedAt: Long, sourceName: String) = SourceEvaluation(
        evaluationKey = "key",
        sourceId = 1,
        extensionPkgName = "pkg",
        signatureHash = "sig",
        extensionName = "Extension",
        sourceName = sourceName,
        lang = "en",
        baseUrl = null,
        repoName = null,
        sourceCount = 1,
        isNsfw = false,
        evaluationVersion = version,
        evaluatedAt = evaluatedAt,
        expiresAt = null,
        sampleCount = 1,
        popularCount = 1,
        latestCount = 0,
        searchCount = 0,
        searchSuccessCount = 0,
        likedTitleMatchCount = 0,
        preferredTagMatchCount = 0,
        blockedTagMatchCount = 0,
        explicitSignalCount = 0,
        ecchiSignalCount = 0,
        errorCount = 0,
        qualityScore = 0.5,
        recommendationFitScore = 0.5,
        searchReliabilityScore = 0.0,
        explicitScore = 0.0,
        ecchiScore = 0.0,
        verdict = SourceEvaluationVerdict.NEUTRAL,
        sampledTitlesJson = null,
        sampledTagsJson = null,
        errorMessage = null,
        catalogueMetadataConfidence = SourceEvaluationMetadataConfidence.HIGH,
    )

    private fun fit(evaluatedAt: Long, score: Double) = SourceRecommendationFit(
        fitKey = "key::rec_fit",
        evaluationKey = "key",
        sourceId = 1,
        extensionPkgName = "pkg",
        signatureHash = "sig",
        extensionName = "Extension",
        sourceName = "Source",
        lang = "en",
        evaluatedAt = evaluatedAt,
        queryCount = 1,
        querySuccessCount = 1,
        rawResultCount = 1,
        visibleCandidateCount = 1,
        filteredOutCount = 0,
        blockedTagCandidateCount = 0,
        matchedGroupCount = 1,
        topPicksContribution = 1,
        noMatchesCount = 0,
        errorCount = 0,
        avgCandidateScore = score,
        recommendationQualityScore = score,
        verdict = RecommendationQualityVerdict.GOOD,
        reasonsJson = "[]",
        errorMessage = null,
    )
}
