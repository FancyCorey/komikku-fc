package exh.recs.discovery

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import exh.recs.sourceprefs.RecommendationSourcePreferenceStore
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.GetSourceEvaluations
import tachiyomi.domain.taste.interactor.GetSourceRecommendationFit
import tachiyomi.domain.taste.model.RecommendationQualityVerdict
import tachiyomi.domain.taste.model.SourceEvaluation
import tachiyomi.domain.taste.model.SourceEvaluationKeys
import tachiyomi.domain.taste.model.SourceEvaluationMetadataConfidence
import tachiyomi.domain.taste.model.SourceEvaluationVerdict
import tachiyomi.domain.taste.model.SourceRecommendationFit

class GetNonInstalledSourceSuggestionsTest {
    private val signer = "a".repeat(64)

    @Test
    fun `mounted discovery emits every exact fixture and reacts to catalog removal`() = runTest {
        val available = MutableStateFlow(
            listOf(
                extension("ordinary.pkg", 1, "Ordinary"),
                extension("app.komikku.fixture.sources.beta", 910000000000000002L, "Fixture Source Beta"),
                extension("app.komikku.fixture.sources.alpha", 910000000000000001L, "Fixture Source Alpha"),
            ),
        )
        val subject = subject(available, isDebugBuild = true, fixtureSigner = signer)

        assertEquals(
            listOf("app.komikku.fixture.sources.alpha", "app.komikku.fixture.sources.beta"),
            subject.subscribe().first().map { it.extension.pkgName },
        )

        val afterRemoval = async {
            subject.subscribe().first { rows -> rows.size == 1 }
        }
        runCurrent()
        available.value = listOf(extension("app.komikku.fixture.sources.beta", 910000000000000002L, "Fixture Source Beta"))
        assertEquals("app.komikku.fixture.sources.beta", afterRemoval.await().single().extension.pkgName)

        val afterFixtureRemoval = async { subject.subscribe().first { it.isEmpty() } }
        runCurrent()
        available.value = listOf(extension("ordinary.pkg", 1, "Ordinary"))
        assertTrueEmpty(afterFixtureRemoval.await())
    }

    @Test
    fun `mounted release and mode-off paths never activate exact fixture entries`() = runTest {
        val available = MutableStateFlow(
            listOf(extension("app.komikku.fixture.sources.alpha", 910000000000000001L, "Fixture Source Alpha")),
        )

        assertTrueEmpty(subject(available, isDebugBuild = false, fixtureSigner = signer).subscribe().first())
        assertTrueEmpty(subject(available, isDebugBuild = true, fixtureSigner = "").subscribe().first())
    }

    @Test
    fun `flow ignores expired and old evidence while retaining current evidence`() = runTest {
        val oldExt = extension("old.pkg", 10L, "Old Source")
        val expiredExt = extension("expired.pkg", 11L, "Expired Source")
        val oldFitExt = extension("old-fit.pkg", 12L, "Old Fit Source")
        val currentExt = extension("current.pkg", 13L, "Current Source")
        val expiredFitExt = extension("expired-fit.pkg", 14L, "Expired Fit Source")

        val oldEvaluation = evaluation(oldExt, 10L, SourceEvaluationKeys.CURRENT_VERSION - 1, null, SourceEvaluationVerdict.REJECTED)
        val expiredEvaluation = evaluation(expiredExt, 11L, SourceEvaluationKeys.CURRENT_VERSION, 0L, SourceEvaluationVerdict.REJECTED)
        val oldFitEvaluation = evaluation(oldFitExt, 12L, SourceEvaluationKeys.CURRENT_VERSION, null, SourceEvaluationVerdict.STRONG_FIT)
        val currentEvaluation = evaluation(currentExt, 13L, SourceEvaluationKeys.CURRENT_VERSION, null, SourceEvaluationVerdict.STRONG_FIT)
        val expiredFitEvaluation = evaluation(
            expiredFitExt,
            14L,
            SourceEvaluationKeys.CURRENT_VERSION,
            null,
            SourceEvaluationVerdict.STRONG_FIT,
        )

        val rows = subject(
            available = MutableStateFlow(listOf(oldExt, expiredExt, oldFitExt, currentExt, expiredFitExt)),
            isDebugBuild = true,
            fixtureSigner = signer,
            likedKeys = setOf(
                availableKey(oldExt),
                availableKey(expiredExt),
            ),
            evaluationRows = listOf(oldEvaluation, expiredEvaluation, oldFitEvaluation, currentEvaluation, expiredFitEvaluation),
            fitRows = listOf(
                fit(oldFitEvaluation, SourceRecommendationFit.CURRENT_VERSION - 1, null, 99),
                fit(currentEvaluation, SourceRecommendationFit.CURRENT_VERSION, null, 7),
                fit(expiredFitEvaluation, SourceRecommendationFit.CURRENT_VERSION, 0L, 99),
            ),
        ).subscribe().first()

        assertEquals(
            setOf("old.pkg", "expired.pkg", "old-fit.pkg", "current.pkg", "expired-fit.pkg"),
            rows.map { it.extension.pkgName }.toSet(),
        )
        assertTrue(
            rows.single { it.extension.pkgName == "old.pkg" }.reasons.any {
                it is NonInstalledSuggestionReason.UserLikedSource
            },
        )
        assertTrue(
            rows.single { it.extension.pkgName == "expired.pkg" }.reasons.any {
                it is NonInstalledSuggestionReason.UserLikedSource
            },
        )
        assertEquals(
            0,
            rows.single { it.extension.pkgName == "old-fit.pkg" }
                .rankingEvidence.visibleCandidateCount,
        )
        assertEquals(
            7,
            rows.single { it.extension.pkgName == "current.pkg" }
                .rankingEvidence.visibleCandidateCount,
        )
        assertEquals(0, rows.single { it.extension.pkgName == "expired-fit.pkg" }.rankingEvidence.visibleCandidateCount)
    }

    private fun subject(
        available: MutableStateFlow<List<Extension.Available>>,
        isDebugBuild: Boolean,
        fixtureSigner: String,
        likedKeys: Set<String> = emptySet(),
        evaluationRows: List<SourceEvaluation> = emptyList(),
        fitRows: List<SourceRecommendationFit> = emptyList(),
    ): GetNonInstalledSourceSuggestions {
        val preferences = SourcePreferences(FakePreferenceStore()).apply {
            sourcesToTryFixtureMode().set(SourcesToTryDebugFixtureMode.SUGGESTION_VISIBLE.prefValue)
            recommendationSourceLanguages().set(setOf("en"))
            likedRecommendationSourceKeys().set(RecommendationSourcePreferenceStore.serialize(likedKeys))
            showNsfwSource().set(true)
            blockExplicitPornHentaiSources().set(false)
        }
        val extensionManager = mockk<ExtensionManager>().also { manager ->
            every { manager.availableExtensionsFlow } returns available
            every { manager.installedExtensionsFlow } returns MutableStateFlow(emptyList())
            every { manager.untrustedExtensionsFlow } returns MutableStateFlow(emptyList())
        }
        val evaluations = mockk<GetSourceEvaluations>().also { interactor ->
            every { interactor.subscribeAll() } returns MutableStateFlow(evaluationRows)
        }
        val fits = mockk<GetSourceRecommendationFit>().also { interactor ->
            coEvery { interactor.awaitAll() } returns fitRows
        }
        return GetNonInstalledSourceSuggestions(
            extensionManager = extensionManager,
            sourcePreferences = preferences,
            getSourceEvaluations = evaluations,
            getSourceRecommendationFit = fits,
            isDebugBuild = isDebugBuild,
            fixtureSignerSha256 = fixtureSigner,
        )
    }

    private fun extension(packageName: String, sourceId: Long, sourceName: String): Extension.Available {
        val baseUrl = "http://127.0.0.1:38291"
        return Extension.Available(
            name = sourceName,
            pkgName = packageName,
            versionName = "1.6.0",
            versionCode = 1,
            libVersion = 1.6,
            lang = "en",
            isNsfw = false,
            signatureHash = signer,
            storeName = SourcesToTryDebugFixture.STORE_NAME,
            sources = listOf(Extension.Available.Source(sourceId, "en", sourceName, "")),
            apkUrl = "$baseUrl/apks/$packageName.apk",
            iconUrl = "$baseUrl/icons/$packageName.png",
            store = ExtensionStore(
                indexUrl = "$baseUrl/index.json",
                name = SourcesToTryDebugFixture.STORE_NAME,
                badgeLabel = SourcesToTryDebugFixture.STORE_BADGE,
                signingKey = signer,
                contact = ExtensionStore.Contact(baseUrl, null),
                isLegacy = false,
                extensionListUrl = null,
            ),
        )
    }

    private fun availableKey(extension: Extension.Available): String =
        RecommendationSourcePreferenceStore.availableKey(
            extension.signatureHash,
            extension.pkgName,
            extension.sources.single().id,
        )

    private fun evaluation(
        extension: Extension.Available,
        sourceId: Long,
        version: Int,
        expiresAt: Long?,
        verdict: SourceEvaluationVerdict,
    ) = SourceEvaluation(
        evaluationKey = buildDismissalKey(extension.signatureHash, extension.pkgName, sourceId),
        sourceId = sourceId,
        extensionPkgName = extension.pkgName,
        signatureHash = extension.signatureHash,
        extensionName = extension.name,
        sourceName = extension.sources.single().name,
        lang = extension.sources.single().lang,
        baseUrl = extension.sources.single().baseUrl,
        repoName = extension.storeName,
        sourceCount = extension.sources.size,
        isNsfw = extension.isNsfw,
        evaluationVersion = version,
        evaluatedAt = 1L,
        expiresAt = expiresAt,
        sampleCount = 10,
        popularCount = 10,
        latestCount = 0,
        searchCount = 0,
        searchSuccessCount = 0,
        likedTitleMatchCount = 0,
        preferredTagMatchCount = 0,
        blockedTagMatchCount = 0,
        explicitSignalCount = 0,
        ecchiSignalCount = 0,
        errorCount = 0,
        qualityScore = 0.9,
        recommendationFitScore = 0.8,
        searchReliabilityScore = 0.0,
        explicitScore = 0.0,
        ecchiScore = 0.0,
        verdict = verdict,
        sampledTitlesJson = "Title 1",
        sampledTagsJson = null,
        errorMessage = null,
        catalogueMetadataConfidence = SourceEvaluationMetadataConfidence.HIGH,
    )

    private fun fit(
        evaluation: SourceEvaluation,
        version: Int,
        expiresAt: Long?,
        visibleCandidateCount: Int,
    ) = SourceRecommendationFit(
        fitKey = SourceRecommendationFit.fitKeyFor(evaluation.evaluationKey),
        evaluationKey = evaluation.evaluationKey,
        sourceId = evaluation.sourceId,
        extensionPkgName = evaluation.extensionPkgName,
        signatureHash = evaluation.signatureHash,
        extensionName = evaluation.extensionName,
        sourceName = evaluation.sourceName,
        lang = evaluation.lang,
        evaluatedAt = 1L,
        queryCount = 1,
        querySuccessCount = 1,
        rawResultCount = visibleCandidateCount,
        visibleCandidateCount = visibleCandidateCount,
        filteredOutCount = 0,
        blockedTagCandidateCount = 0,
        matchedGroupCount = 1,
        topPicksContribution = 1,
        noMatchesCount = 0,
        errorCount = 0,
        avgCandidateScore = 0.9,
        recommendationQualityScore = 0.9,
        verdict = RecommendationQualityVerdict.GREAT,
        reasonsJson = "[]",
        errorMessage = null,
        evaluationVersion = version,
        expiresAt = expiresAt,
    )

    private fun assertTrueEmpty(rows: List<NonInstalledSourceSuggestion>) {
        assertEquals(emptyList<NonInstalledSourceSuggestion>(), rows)
    }
}
