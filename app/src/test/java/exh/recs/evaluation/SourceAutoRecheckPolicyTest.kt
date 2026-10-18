package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.RecommendationQualityVerdict
import tachiyomi.domain.taste.model.SourceRecommendationFit
import java.io.File
import java.util.concurrent.TimeUnit

class SourceAutoRecheckPolicyTest {

    @Test
    fun `automatic re-check is off by default and the safe guards are on`() {
        assertFalse(SourceAutoRecheckPolicy.DEFAULT_ENABLED)
        assertTrue(SourceAutoRecheckPolicy.DEFAULT_UNMETERED_ONLY)
        assertTrue(SourceAutoRecheckPolicy.DEFAULT_BATTERY_GUARD)
        assertEquals(14, SourceAutoRecheckPolicy.DEFAULT_INTERVAL_DAYS)
    }

    @Test
    fun `interval is clamped to 1 to 90 days and presets are recognised`() {
        assertEquals(1, SourceAutoRecheckPolicy.coerceIntervalDays(0))
        assertEquals(90, SourceAutoRecheckPolicy.coerceIntervalDays(400))
        assertEquals(45, SourceAutoRecheckPolicy.coerceIntervalDays(45))
        assertTrue(SourceAutoRecheckPolicy.isPreset(7))
        assertTrue(SourceAutoRecheckPolicy.isPreset(14))
        assertTrue(SourceAutoRecheckPolicy.isPreset(30))
        assertFalse(SourceAutoRecheckPolicy.isPreset(21))
    }

    @Test
    fun `next check is the last run plus the interval, and unknown before the first run`() {
        assertNull(SourceAutoRecheckPolicy.nextDueMillis(0L, 14))
        assertEquals(1_000L + TimeUnit.DAYS.toMillis(14), SourceAutoRecheckPolicy.nextDueMillis(1_000L, 14))
        assertEquals(1_000L + TimeUnit.DAYS.toMillis(90), SourceAutoRecheckPolicy.nextDueMillis(1_000L, 500))
    }

    @Test
    fun `only missing and outdated sources are targeted`() {
        assertEquals(listOf("a", "b", "c"), SourceAutoRecheckPolicy.targets(listOf("a", "b"), listOf("c")))
        assertEquals(emptyList<String>(), SourceAutoRecheckPolicy.targets(emptyList<String>(), emptyList<String>()))
    }

    @Test
    fun `a run is skipped while another check runs or when nothing needs checking`() {
        assertEquals(
            SourceAutoRecheckPolicy.SkipReason.ANOTHER_CHECK_RUNNING,
            SourceAutoRecheckPolicy.skipReason(5, evaluationRunning = true, qualityRunning = false),
        )
        assertEquals(
            SourceAutoRecheckPolicy.SkipReason.ANOTHER_CHECK_RUNNING,
            SourceAutoRecheckPolicy.skipReason(5, evaluationRunning = false, qualityRunning = true),
        )
        assertEquals(
            SourceAutoRecheckPolicy.SkipReason.NOTHING_TO_CHECK,
            SourceAutoRecheckPolicy.skipReason(0, evaluationRunning = false, qualityRunning = false),
        )
        assertNull(SourceAutoRecheckPolicy.skipReason(3, evaluationRunning = false, qualityRunning = false))
    }

    @Test
    fun `the worker never installs extensions and the scheduler honours the preferences`() {
        val worker = File("src/main/java/exh/recs/evaluation/SourceAutoRecheck.kt").readText()
        assertTrue(worker.contains("InstallerMode.CURRENT"), "worker must use the no-install mode")
        assertFalse(worker.contains("InstallerMode.PRIVATE"))
        assertFalse(worker.contains("InstallerMode.SHIZUKU"))
        assertTrue(worker.contains("recommendationAutoRecheckEnabled().get()"))
        assertTrue(worker.contains("setRequiresBatteryNotLow(preferences.recommendationAutoRecheckBatteryGuard().get())"))
        assertTrue(worker.contains("NetworkType.UNMETERED"))
        assertTrue(worker.contains("cancelUniqueWork"), "turning it off cancels the schedule")
    }

    @Test
    fun `the runner refuses non-installed sources unless the private installer is chosen`() {
        val runner = File("src/main/java/exh/recs/evaluation/SourceRecommendationQualityRunner.kt").readText()
        assertTrue(runner.contains("NON_INSTALLED_REFUSED_KEY"))
        assertTrue(runner.contains("installerOverride"))
    }

    private fun fit(
        sourceId: Long?,
        verdict: RecommendationQualityVerdict,
        queries: Int = 3,
        successes: Int = 0,
        version: Int = SourceRecommendationFit.CURRENT_VERSION,
        expiresAt: Long? = null,
    ) = SourceRecommendationFit(
        fitKey = "k$sourceId", evaluationKey = "e$sourceId", sourceId = sourceId, extensionPkgName = "p", signatureHash = "s",
        extensionName = "x", sourceName = "n$sourceId", lang = "en", evaluatedAt = 0, queryCount = queries, querySuccessCount = successes,
        rawResultCount = 0, visibleCandidateCount = 0, filteredOutCount = 0, blockedTagCandidateCount = 0, matchedGroupCount = 0,
        topPicksContribution = 0, noMatchesCount = 0, errorCount = 0, avgCandidateScore = 0.0, recommendationQualityScore = 0.0,
        verdict = verdict, reasonsJson = "[]", errorMessage = null, evaluationVersion = version, expiresAt = expiresAt,
    )

    private fun history(sourceId: Long, recentRuns: Int, recentShown: Int) = exh.recs.SourceFitStats(
        sourceId = sourceId, runCount = recentRuns, shownCount = recentShown, noMatchCount = 0, filteredOutCount = 0, errorCount = 0,
        hiddenByDuplicateCount = 0, totalVisibleCandidates = 0, lastSuccessAt = 0L, lastErrorAt = 0L, updatedAt = 0L,
        recentRunCount = recentRuns, recentShownCount = recentShown,
    )

    private val emptyHistory = (1L..9L).associateWith { history(it, recentRuns = 5, recentShown = 0) }

    @Test
    fun `only current no-match or all-queries-failed results skip a source`() {
        val now = 1_000L
        val skipped = SourceAutoRecheckPolicy.skippedSourceIds(
            listOf(
                fit(1, RecommendationQualityVerdict.NO_MATCHES),
                fit(2, RecommendationQualityVerdict.ERROR, queries = 3, successes = 0),
                fit(3, RecommendationQualityVerdict.ERROR, queries = 3, successes = 1),
                fit(4, RecommendationQualityVerdict.GOOD),
                fit(5, RecommendationQualityVerdict.WEAK),
                fit(6, RecommendationQualityVerdict.TOO_LITTLE_EVIDENCE),
                fit(7, RecommendationQualityVerdict.NO_MATCHES, version = 0),
                fit(8, RecommendationQualityVerdict.NO_MATCHES, expiresAt = 500L),
                fit(null, RecommendationQualityVerdict.NO_MATCHES),
                fit(9, RecommendationQualityVerdict.ERROR, queries = 0, successes = 0),
            ),
            emptyHistory,
            now,
        )
        assertEquals(setOf(1L, 2L), skipped)
    }

    @Test
    fun `a bad check alone never skips a source that For You has not also found empty`() {
        val fits = listOf(fit(1, RecommendationQualityVerdict.NO_MATCHES))
        val now = 1_000L
        assertEquals(emptySet<Long>(), SourceAutoRecheckPolicy.skippedSourceIds(fits, emptyMap(), now), "no For You history")
        assertEquals(
            emptySet<Long>(),
            SourceAutoRecheckPolicy.skippedSourceIds(fits, mapOf(1L to history(1, recentRuns = 2, recentShown = 0)), now),
            "too few recent For You refreshes",
        )
        assertEquals(
            emptySet<Long>(),
            SourceAutoRecheckPolicy.skippedSourceIds(fits, mapOf(1L to history(1, recentRuns = 10, recentShown = 1)), now),
            "For You showed something from it recently",
        )
        assertEquals(
            setOf(1L),
            SourceAutoRecheckPolicy.skippedSourceIds(fits, mapOf(1L to history(1, recentRuns = 3, recentShown = 0)), now),
        )
    }

    @Test
    fun `skipping never leaves For You without a source`() {
        assertEquals(listOf(2L, 3L), SourceAutoRecheckPolicy.withoutSkipped(listOf(1L, 2L, 3L), setOf(1L)) { it })
        assertEquals(listOf(1L, 2L), SourceAutoRecheckPolicy.withoutSkipped(listOf(1L, 2L), setOf(1L, 2L)) { it })
        assertEquals(listOf(1L, 2L), SourceAutoRecheckPolicy.withoutSkipped(listOf(1L, 2L), emptySet()) { it })
    }

    @Test
    fun `results only act while the automatic check is on, and the screen model fails open`() {
        val model = File("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt").readText()
        assertTrue(model.contains("recommendationAutoRecheckEnabled().get()"))
        assertTrue(model.contains("recommendationAutoRecheckApplyToForYou().get()"))
        assertTrue(model.contains("SourceAutoRecheckPolicy.withoutSkipped(orderedEnabledSources, checkSkippedIds, Source::id)"))
        assertTrue(model.substringAfter("private suspend fun loadCheckSkippedSourceIds").substringBefore("searchSource(").contains("emptySet()"))
        assertTrue(SourceAutoRecheckPolicy.DEFAULT_APPLY_TO_FOR_YOU)
        assertFalse(SourceAutoRecheckPolicy.DEFAULT_ENABLED)
    }
}
