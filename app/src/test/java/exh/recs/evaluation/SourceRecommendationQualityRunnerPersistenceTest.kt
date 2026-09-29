package exh.recs.evaluation

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.UpsertSourceRecommendationFit
import tachiyomi.domain.taste.model.SourceEvaluation
import tachiyomi.domain.taste.repository.SourceRecommendationFitRepository

class SourceRecommendationQualityRunnerPersistenceTest {

    private val repository = mockk<SourceRecommendationFitRepository>()
    private val evaluation = mockk<SourceEvaluation>(relaxed = true)
    private val runner = SourceRecommendationQualityRunner(
        context = mockk(),
        extensionManager = mockk(),
        basePreferences = mockk(),
        getTasteProfile = mockk(),
        getTagAliases = mockk(),
        upsertSourceRecommendationFit = UpsertSourceRecommendationFit(repository),
    )

    @Test
    fun `cancellation during error saving reaches the caller`() = runTest {
        val cancellation = CancellationException("check cancelled")
        coEvery { repository.upsert(any()) } throws cancellation

        try {
            runner.writeErrorFit(evaluation, "Probe error")
            fail("Cancellation must reach the caller")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        coVerify(exactly = 1) { repository.upsert(any()) }
    }

    @Test
    fun `ordinary database failure remains isolated`() = runTest {
        coEvery { repository.upsert(any()) } throws IllegalStateException("database unavailable")

        runner.writeErrorFit(evaluation, "Probe error")

        coVerify(exactly = 1) { repository.upsert(any()) }
    }

    @Test
    fun `error record keeps the reported reason`() = runTest {
        coEvery { repository.upsert(any()) } returns Unit

        runner.writeErrorFit(evaluation, "Probe error")

        coVerify(exactly = 1) { repository.upsert(match { it.errorMessage == "Probe error" && it.errorCount == 1 }) }
    }
}
