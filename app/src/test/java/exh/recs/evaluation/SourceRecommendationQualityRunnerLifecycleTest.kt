package exh.recs.evaluation

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.base.ExtensionInstallerPreference
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.GetTagAliases
import tachiyomi.domain.taste.interactor.GetTasteProfile
import tachiyomi.domain.taste.interactor.UpsertSourceRecommendationFit
import tachiyomi.domain.taste.model.SourceEvaluation
import tachiyomi.domain.taste.model.TasteProfile
import tachiyomi.domain.taste.repository.SourceRecommendationFitRepository

class SourceRecommendationQualityRunnerLifecycleTest {
    private val manager = mockk<ExtensionManager>()
    private val taste = mockk<GetTasteProfile>()
    private val aliases = mockk<GetTagAliases>()
    private val repository = mockk<SourceRecommendationFitRepository>()
    private val available = mockk<Extension.Available> {
        every { pkgName } returns "test.reader"
        every { signatureHash } returns "test-signature"
    }
    private val evaluation = mockk<SourceEvaluation>(relaxed = true) {
        every { extensionPkgName } returns "test.reader"
        every { signatureHash } returns "test-signature"
        every { sourceName } returns "Test Reader"
    }
    private val installed = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val installerPreference = mockk<ExtensionInstallerPreference> {
        every { entries } returns listOf(BasePreferences.ExtensionInstaller.PRIVATE)
    }
    private val runner = SourceRecommendationQualityRunner(
        context = mockk(),
        extensionManager = manager,
        basePreferences = mockk { every { extensionInstaller() } returns installerPreference },
        getTasteProfile = taste,
        getTagAliases = aliases,
        upsertSourceRecommendationFit = UpsertSourceRecommendationFit(repository),
    )

    init {
        every { manager.availableExtensionsFlow } returns MutableStateFlow(listOf(available))
        every { manager.installedExtensionsFlow } returns installed
        coEvery { repository.upsert(any()) } returns Unit
    }

    @Test
    fun `installed source runs the real probe and saves its result without installing`() = runBlocking {
        SourceRuntimeFailureRegistry.clearAll()
        coEvery { taste.await() } returns TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 0.9))
        coEvery { aliases.awaitAliasMap() } returns emptyMap()
        coEvery { aliases.awaitGroupToAliasesMap() } returns emptyMap()
        val manga = SManga.create().apply {
            url = "/manga/forest-adventure"
            title = "Forest Adventure"
            genre = "Action"
        }
        val source = mockk<Source> {
            every { id } returns 912L
            every { name } returns "Test Reader"
            every { lang } returns "en"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } returns MangasPage(listOf(manga), false)
        }
        every { evaluation.sourceId } returns 912L
        installed.value = listOf(
            mockk<Extension.Installed> {
                every { pkgName } returns "test.reader"
                every { signatureHash } returns "test-signature"
                every { sources } returns listOf(source)
            },
        )
        try {
            runner.start(listOf(evaluation), SourceEvaluationInstallerPolicy.InstallerMode.PRIVATE)
            awaitStatus(SourceRecommendationQualityQueueState.Status.Completed)
            assertEquals(1, runner.state.value.completedCount)
            coVerify(exactly = 1) {
                repository.upsert(match { it.querySuccessCount > 0 && it.rawResultCount > 0 && it.errorCount == 0 })
            }
            coVerify(atLeast = 1) { source.getSearchManga(any(), any(), any()) }
            coVerify(exactly = 0) { manager.withTemporaryPrivateExtension<Unit>(any(), any()) }
        } finally {
            runner.cancel()
        }
    }

    @Test
    fun `cancelling setup stops before installation or saving`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        coEvery { taste.await() } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        try {
            runner.start(listOf(evaluation), SourceEvaluationInstallerPolicy.InstallerMode.PRIVATE)
            withTimeout(5_000) { entered.await() }
            runner.cancel()
            awaitStatus(SourceRecommendationQualityQueueState.Status.Cancelled)
            assertEquals(0, runner.state.value.completedCount)
            coVerify(exactly = 0) { repository.upsert(any()) }
            coVerify(exactly = 0) { manager.withTemporaryPrivateExtension<Unit>(any(), any()) }
        } finally {
            runner.cancel()
        }
    }

    @Test
    fun `cancelling installation leaves the temporary scope before reporting cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val left = CompletableDeferred<Unit>()
        coEvery { taste.await() } returns mockk<TasteProfile>()
        coEvery { manager.withTemporaryPrivateExtension<Unit>(available, any()) } coAnswers {
            val block = secondArg<suspend (Flow<InstallStep>) -> Unit>()
            try {
                entered.complete(Unit)
                block(MutableStateFlow(InstallStep.Installing))
            } finally {
                left.complete(Unit)
            }
        }
        try {
            runner.start(listOf(evaluation), SourceEvaluationInstallerPolicy.InstallerMode.PRIVATE)
            withTimeout(5_000) { entered.await() }
            runner.cancel()
            awaitStatus(SourceRecommendationQualityQueueState.Status.Cancelled)
            assertTrue(left.isCompleted)
            assertEquals(0, runner.state.value.completedCount)
            coVerify(exactly = 0) { repository.upsert(any()) }
            coVerify(exactly = 1) { manager.withTemporaryPrivateExtension<Unit>(available, any()) }
        } finally {
            runner.cancel()
        }
    }

    @Test
    fun `installed extension with a missing source saves an error without installing`() = runBlocking {
        coEvery { taste.await() } returns mockk<TasteProfile>()
        installed.value = listOf(
            mockk<Extension.Installed> {
                every { pkgName } returns "test.reader"
                every { signatureHash } returns "test-signature"
                every { sources } returns emptyList()
            },
        )
        try {
            runner.start(listOf(evaluation), SourceEvaluationInstallerPolicy.InstallerMode.PRIVATE)
            awaitStatus(SourceRecommendationQualityQueueState.Status.Completed)
            assertEquals(1, runner.state.value.completedCount)
            coVerify(exactly = 1) {
                repository.upsert(match { it.errorMessage == "Source not found in installed extension" })
            }
            coVerify(exactly = 0) { manager.withTemporaryPrivateExtension<Unit>(any(), any()) }
        } finally {
            runner.cancel()
        }
    }

    private suspend fun awaitStatus(status: SourceRecommendationQualityQueueState.Status) {
        withTimeout(5_000) { runner.state.first { it.status == status } }
    }
}
