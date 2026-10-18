package exh.recs.bestversion

import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import exh.recs.matching.ConfirmedGroupTasteRatingActions
import exh.recs.matching.CrossSourceIdentityDecisionController
import exh.recs.matching.MangaIdentityKey
import exh.recs.matching.NoOpConfirmedGroupTasteRatingActions
import exh.recs.matching.SameMangaCandidateResult
import exh.util.CrossSourceIdentityUndoJournal
import exh.util.DispatcherHandle
import exh.util.FakePreferenceStore
import exh.util.FakeTasteRepository
import exh.util.MigrationReceiptJournal
import exh.util.NonUndoableEventJournal
import exh.util.NonUndoableEventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.migration.usecases.MigrationOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.RestoreCrossSourceGroupState
import tachiyomi.domain.taste.interactor.UpsertMangaSourceQualitySignal
import tachiyomi.domain.taste.model.CrossSourceGroupPrimary
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import java.util.concurrent.Executors

// KMK Confirmed Blocker Remediation Corrective Completion Plan V2 2026-07-29 -->
/**
 * Direct caller tests for [BestVersionCompareScreenModel.confirmMigration] -- previously untestable
 * since the class's own hardcoded `Executors.newFixedThreadPool(5).asCoroutineDispatcher()` property
 * initializer spun up 5 real OS threads at construction time, with no injection seam and no lifecycle
 * cleanup. The class now accepts `dispatcherHandle` (a [DispatcherHandle] pairing a dispatcher with an
 * explicit ownership/close contract -- V3 Phase B) as a constructor parameter, defaulting to the exact
 * same production fixed-thread-pool dispatcher, and closes it in `onDispose()` via that handle's own
 * `close` action rather than by inferring ownership from the dispatcher's runtime type.
 *
 * `confirmMigration()`'s own logic runs on `screenModelScope` (`Dispatchers.Main`-redirectable via
 * [Dispatchers.setMain]), so it is directly testable. Reaching a state where `confirmMigration()` has
 * a resolvable target requires populating `candidates`/`selectedKeys`/`selectedBestKey`, which are only
 * ever written by the *unrelated* candidate-search pipeline (`startSearch()`, routed through a
 * `ioCoroutineScope` hardcoded to `Dispatchers.IO` with no injection seam of its own, and explicitly out
 * of scope for this pass -- see the V2 report for why). Rather than drive that real search pipeline (and
 * inherit its real-thread-hop nondeterminism just to set up preconditions unrelated to what these tests
 * actually verify), these tests construct the model with `getMangaInteractor.await()` stubbed to return
 * `null` -- which makes `init {}` set an Error state and return *before* it ever calls `startSearch()` --
 * then use reflection purely to seed the private `originManga` field and the inherited `mutableState`
 * flow with a ready-to-migrate [BestVersionCompareScreenModel.State], the same shape `startSearch()`
 * would eventually produce. This changes no production code and exercises confirmMigration() exactly as
 * written; it only avoids re-deriving, through a real background pipeline, state this test suite doesn't
 * exist to verify.
 */
class BestVersionCompareScreenModelConfirmMigrationTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        NonUndoableEventJournal.clear()
        MigrationReceiptJournal.clear()
        CrossSourceIdentityUndoJournal.clear()
    }

    private fun manga(source: Long, url: String, id: Long = source) =
        Manga.create().copy(id = id, source = source, url = url, ogTitle = "Manga $url", favorite = false)

    @Suppress("UNCHECKED_CAST")
    private fun BestVersionCompareScreenModel.forceState(newState: BestVersionCompareScreenModel.State) {
        val field = StateScreenModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        (field.get(this) as MutableStateFlow<BestVersionCompareScreenModel.State>).value = newState
    }

    private fun BestVersionCompareScreenModel.forceOriginManga(manga: Manga) {
        val field = BestVersionCompareScreenModel::class.java.getDeclaredField("originManga")
        field.isAccessible = true
        field.set(this, manga)
    }

    /** Seeds a Preview/ConfirmCandidates-shaped state where [target] is already selected and ready. */
    private fun BestVersionCompareScreenModel.seedReadyToMigrate(origin: Manga, target: Manga) {
        forceOriginManga(origin)
        val key = MangaIdentityKey(target.source, target.url)
        forceState(
            BestVersionCompareScreenModel.State(
                step = BestVersionStep.ConfirmCandidates,
                originManga = origin,
                candidates = persistentMapOf(mockk<Source>(relaxed = true) to SameMangaCandidateResult.Success(listOf(target))),
                selectedKeys = setOf(key),
                selectedBestKey = key,
            ),
        )
    }

    private fun buildModel(
        originMangaId: Long = 1L,
        getManga: GetManga = mockk(relaxed = true),
        migrateMangaUseCase: MigrateMangaUseCase = mockk(relaxed = true),
        sourcePreferences: SourcePreferences = SourcePreferences(FakePreferenceStore()),
        sourceManager: SourceManager = mockk(relaxed = true),
        upsertQualitySignal: UpsertMangaSourceQualitySignal = mockk(relaxed = true),
        dispatcherHandle: DispatcherHandle = DispatcherHandle(StandardTestDispatcher()),
        candidateSearchGateway: BestVersionCandidateSearchGateway = mockk(relaxed = true),
        getCrossSourceMangaLinks: GetCrossSourceMangaLinks = mockk(relaxed = true),
        getCrossSourceGroupPrimary: GetCrossSourceGroupPrimary = mockk(relaxed = true),
        restoreCrossSourceGroupState: RestoreCrossSourceGroupState = mockk(relaxed = true),
        identityDecisionController: CrossSourceIdentityDecisionController = identityController(),
        confirmedGroupTasteRatingActions: ConfirmedGroupTasteRatingActions = NoOpConfirmedGroupTasteRatingActions,
        defaultOriginMissing: Boolean = true,
    ): BestVersionCompareScreenModel {
        if (defaultOriginMissing) {
            coEvery { getManga.await(originMangaId) } returns null
        }
        return BestVersionCompareScreenModel(
            originMangaId = originMangaId,
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            getMangaInteractor = getManga,
            getChaptersByMangaId = mockk(relaxed = true),
            networkToLocalManga = mockk(relaxed = true),
            migrateMangaUseCase = migrateMangaUseCase,
            upsertQualitySignal = upsertQualitySignal,
            isLowRamDevice = false,
            dispatcherHandle = dispatcherHandle,
            candidateSearchGateway = candidateSearchGateway,
            getCrossSourceMangaLinks = getCrossSourceMangaLinks,
            getCrossSourceGroupPrimary = getCrossSourceGroupPrimary,
            restoreCrossSourceGroupState = restoreCrossSourceGroupState,
            identityController = identityDecisionController,
            confirmedGroupTasteRatingActions = confirmedGroupTasteRatingActions,
        )
    }

    private fun identityController(): CrossSourceIdentityDecisionController {
        val repository = FakeTasteRepository()
        return CrossSourceIdentityDecisionController(
            GetCrossSourceIdentityDecisions(repository),
            ReplaceCrossSourceIdentityDecisions(repository),
        )
    }

    @Test
    fun `retryCandidate retries every failed preview while preserving loaded and skipped rows`() = runTest {
        val model = buildModel()
        val failed = manga(2L, "failed")
        val loaded = manga(3L, "loaded")
        val skipped = manga(4L, "skipped")
        val failedKey = MangaIdentityKey(failed.source, failed.url)
        val loadedKey = MangaIdentityKey(loaded.source, loaded.url)
        val skippedKey = MangaIdentityKey(skipped.source, skipped.url)
        val loadedPreview = CandidatePreviewState.Loaded(emptyList())
        val origin = manga(1L, "origin")
        model.forceOriginManga(origin)

        model.forceState(
            BestVersionCompareScreenModel.State(
                step = BestVersionStep.ComparePreview,
                originManga = origin,
                candidates = persistentMapOf(
                    mockk<Source>(relaxed = true) to SameMangaCandidateResult.Success(listOf(failed, loaded, skipped)),
                ),
                selectedKeys = setOf(failedKey, loadedKey, skippedKey),
                candidateChapters = persistentMapOf(
                    failedKey to CandidateChapterState.Available(SChapter.create(), 1),
                    loadedKey to CandidateChapterState.Available(SChapter.create(), 1),
                    skippedKey to CandidateChapterState.Unavailable,
                ),
                candidatePreviews = persistentMapOf(
                    failedKey to CandidatePreviewState.PreviewError(BestVersionErrorReason.OriginMissing),
                    loadedKey to loadedPreview,
                    skippedKey to CandidatePreviewState.Skipped,
                ),
            ),
        )

        model.retryCandidate(failedKey)

        assertEquals(CandidatePreviewState.Loading, model.state.value.candidatePreviews[failedKey])
        assertEquals(loadedPreview, model.state.value.candidatePreviews[loadedKey])
        assertEquals(CandidatePreviewState.Skipped, model.state.value.candidatePreviews[skippedKey])
        model.onDispose()
    }

    @Test
    fun `missing origin uses a typed localized error reason`() = runTest {
        val model = buildModel()

        advanceUntilIdle()

        assertEquals(BestVersionStep.Error(BestVersionErrorReason.OriginMissing), model.state.value.step)
    }

    @Test
    fun `origin lookup remains loading while suspended and starts search when released`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val lookup = CompletableDeferred<Manga?>()
        val getManga = mockk<GetManga>()
        val searchGateway = mockk<BestVersionCandidateSearchGateway>(relaxed = true)
        coEvery { getManga.await(1L) } coAnswers { lookup.await() }

        val model = buildModel(
            getManga = getManga,
            candidateSearchGateway = searchGateway,
            defaultOriginMissing = false,
        )
        assertEquals(BestVersionStep.LoadingOrigin, model.state.value.step)
        assertEquals(null, model.state.value.originManga)
        coVerify(exactly = 0) { searchGateway.getGlobalSearchSources() }

        lookup.complete(origin)
        advanceUntilIdle()

        assertEquals(BestVersionStep.ConfirmCandidates, model.state.value.step)
        assertEquals(origin, model.state.value.originManga)
        coVerify(exactly = 1) { searchGateway.getGlobalSearchSources() }
        model.onDispose()
    }

    @Test
    fun `late origin result after disposal does not publish or start candidate search`() = runTest {
        val lookup = CompletableDeferred<Manga?>()
        val getManga = mockk<GetManga>()
        val searchGateway = mockk<BestVersionCandidateSearchGateway>(relaxed = true)
        coEvery { getManga.await(1L) } coAnswers {
            withContext(NonCancellable) {
                lookup.await()
            }
        }

        val model = buildModel(
            getManga = getManga,
            candidateSearchGateway = searchGateway,
            defaultOriginMissing = false,
        )
        assertEquals(BestVersionStep.LoadingOrigin, model.state.value.step)

        model.onDispose()
        lookup.complete(manga(source = 1L, url = "/late-origin"))
        advanceUntilIdle()

        assertEquals(BestVersionStep.LoadingOrigin, model.state.value.step)
        assertEquals(null, model.state.value.originManga)
        coVerify(exactly = 0) { searchGateway.getGlobalSearchSources() }
    }

    @Test
    fun `keepCurrentVersion reaches Done without invoking the migration use case or recording a receipt`() = runTest {
        // Direct call
        // coverage for the "keep current" path (plan item: "candidate preview, keep-current,
        // successful local migration..."). Previously only the resulting State shape was asserted
        // (BestVersionOriginAndUnavailablePreviewTest); this exercises the real
        // keepCurrentVersion() function.
        val origin = manga(source = 1L, url = "/origin", id = 42L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>(relaxed = true)
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase, sourcePreferences = sourcePreferences)
        model.forceOriginManga(origin)

        model.keepCurrentVersion()

        assertEquals(BestVersionStep.Done, model.state.value.step)
        assertTrue(model.state.value.keptCurrentVersion)
        assertTrue(model.state.value.migrationComplete)
        assertEquals(42L, model.state.value.completedTargetMangaId)
        assertEquals(null, model.state.value.selectedBestKey, "keeping current must never leave a migrate-dialog target selected")
        coVerify(exactly = 0) { migrateMangaUseCase(any(), any(), any()) }
        assertTrue(NonUndoableEventJournal.isEmpty(), "keeping the current version is not a migration and must never record a receipt")
    }

    @Test
    fun `a successful migration reaches Done and stores the actual target manga id`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } returns
            MigrationOutcome.Success(requestedFlags = emptySet(), completedFlags = emptySet(), skippedFlags = emptySet())
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase, sourcePreferences = sourcePreferences)
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        assertEquals(BestVersionStep.Done, model.state.value.step)
        assertEquals(99L, model.state.value.completedTargetMangaId)
        assertTrue(model.state.value.migrationComplete)
        assertTrue(!model.state.value.isMigrating)
    }

    @Test
    fun `successful Best Version migration retries rating propagation after tracking can be created`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val originLink = CrossSourceMangaLink(1L, "/origin", "origin-group", "Origin", 1L, 1L)
        val targetLink = CrossSourceMangaLink(2L, "/target", "target-group", "Target", 2L, 2L)
        val events = mutableListOf<String>()
        var migrationHasCreatedTargetTracking = false
        val getLinks = mockk<GetCrossSourceMangaLinks>()
        val getPrimary = mockk<GetCrossSourceGroupPrimary>(relaxed = true)
        val restoreGroups = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        coEvery { getLinks.awaitBySourceUrl(1L, "/origin") } returns originLink
        coEvery { getLinks.awaitBySourceUrl(2L, "/target") } returns targetLink
        coEvery { getLinks.awaitByGroupId("target-group") } returns listOf(targetLink)
        coEvery { restoreGroups.await(any(), any(), any(), any()) } coAnswers {
            events += "group-persisted"
            Unit
        }
        coEvery {
            migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any())
        } coAnswers {
            events += "migration-success"
            migrationHasCreatedTargetTracking = true
            MigrationOutcome.Success(requestedFlags = emptySet(), completedFlags = emptySet(), skippedFlags = emptySet())
        }
        val ratingActions = object : ConfirmedGroupTasteRatingActions {
            override suspend fun propagateToConfirmedMember(origin: Manga, member: Manga) {
                events += if (migrationHasCreatedTargetTracking) "rating-after-tracking" else "rating-before-tracking"
            }

            override suspend fun currentRating(origin: Manga): tachiyomi.domain.taste.model.MangaRating? = null
            override suspend fun setGroupRating(origin: Manga, rating: tachiyomi.domain.taste.model.MangaRating?) = Unit
        }
        val model = buildModel(
            migrateMangaUseCase = migrateMangaUseCase,
            getCrossSourceMangaLinks = getLinks,
            getCrossSourceGroupPrimary = getPrimary,
            restoreCrossSourceGroupState = restoreGroups,
            confirmedGroupTasteRatingActions = ratingActions,
        )
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        assertEquals(
            listOf("group-persisted", "rating-before-tracking", "migration-success", "rating-after-tracking"),
            events,
        )
        assertEquals(BestVersionStep.Done, model.state.value.step)
    }

    @Test
    fun `a successful migration records exactly one MIGRATION_COMPLETED receipt when Evaluation Mode is on`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } returns
            MigrationOutcome.Success(requestedFlags = emptySet(), completedFlags = emptySet(), skippedFlags = emptySet())
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase, sourcePreferences = sourcePreferences)
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        val events = NonUndoableEventJournal.snapshot()
        assertEquals(1, events.size, "exactly one receipt must be recorded, not zero or duplicated")
        assertEquals(NonUndoableEventType.MIGRATION_COMPLETED, events.first().eventType)
        coVerify(exactly = 1) { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) }
    }

    @Test
    fun `a successful migration records a correlated event and receipt when Evaluation Mode is off`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(false)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } returns
            MigrationOutcome.Success(requestedFlags = emptySet(), completedFlags = emptySet(), skippedFlags = emptySet())
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase, sourcePreferences = sourcePreferences)
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        val event = NonUndoableEventJournal.snapshot().single()
        val receipt = MigrationReceiptJournal.snapshot().single()
        assertEquals(NonUndoableEventType.MIGRATION_COMPLETED, event.eventType)
        assertEquals(event.id, receipt.id, "the event and recovery receipt must share one correlation id")
        assertEquals(origin.id, receipt.originMangaId)
        assertEquals(target.id, receipt.targetMangaId)
        assertEquals(BestVersionStep.Done, model.state.value.step, "the migration itself must still succeed")
    }

    @Test
    fun `a PartialFailure outcome stays in an error state and records nothing`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        val ratingActions = mockk<ConfirmedGroupTasteRatingActions>(relaxed = true)
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } returns
            MigrationOutcome.PartialFailure(
                requestedFlags = emptySet(),
                completedFlags = emptySet(),
                skippedFlags = emptySet(),
                failedAt = null,
                finalUpdateFailed = true,
                cause = IllegalStateException("boom"),
            )
        val model = buildModel(
            migrateMangaUseCase = migrateMangaUseCase,
            sourcePreferences = sourcePreferences,
            confirmedGroupTasteRatingActions = ratingActions,
        )
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        assertTrue(model.state.value.step is BestVersionStep.Error, "expected Error, got ${model.state.value.step}")
        assertTrue(!model.state.value.isMigrating)
        assertTrue(!model.state.value.migrationComplete)
        assertTrue(NonUndoableEventJournal.isEmpty(), "a partial failure must never record a success receipt")
        coVerify(exactly = 1) { ratingActions.propagateToConfirmedMember(origin, target) }
    }

    @Test
    fun `a NotStarted outcome stays in an error state and records nothing`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } returns
            MigrationOutcome.NotStarted(cause = IllegalStateException("could not resolve source"))
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase, sourcePreferences = sourcePreferences)
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        assertTrue(model.state.value.step is BestVersionStep.Error, "expected Error, got ${model.state.value.step}")
        assertTrue(!model.state.value.isMigrating)
        assertTrue(NonUndoableEventJournal.isEmpty(), "a NotStarted outcome must never record a success receipt")
    }

    @Test
    fun `confirmed pair is saved into origin group before a failed migration without folding unrelated members`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val originLink = CrossSourceMangaLink(1L, "/origin", "origin-group", "Origin", 1L, 1L)
        val targetLink = CrossSourceMangaLink(2L, "/target", "target-group", "Target", 2L, 2L)
        val unrelatedLink = CrossSourceMangaLink(3L, "/unrelated", "target-group", "Unrelated", 3L, 3L)
        val getLinks = mockk<GetCrossSourceMangaLinks>()
        val getPrimary = mockk<GetCrossSourceGroupPrimary>()
        val restoreGroups = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        val events = mutableListOf<String>()
        coEvery { getLinks.awaitBySourceUrl(1L, "/origin") } returns originLink
        coEvery { getLinks.awaitBySourceUrl(2L, "/target") } returns targetLink
        coEvery { getLinks.awaitByGroupId("target-group") } returns listOf(targetLink, unrelatedLink)
        coEvery { getPrimary.awaitByGroupId("target-group") } returns CrossSourceGroupPrimary(
            groupId = "target-group",
            source = 2L,
            url = "/target",
            updatedAt = 2L,
        )
        coEvery {
            migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any())
        } coAnswers {
            events += "migration"
            MigrationOutcome.NotStarted(cause = IllegalStateException("copy unavailable"))
        }
        coEvery { restoreGroups.await(any(), any(), any(), any()) } coAnswers {
            events += "group"
            Unit
        }
        val ratingActions = object : ConfirmedGroupTasteRatingActions {
            override suspend fun propagateToConfirmedMember(origin: Manga, member: Manga) {
                events += "rating-attempt"
            }

            override suspend fun currentRating(origin: Manga): tachiyomi.domain.taste.model.MangaRating? = null
            override suspend fun setGroupRating(origin: Manga, rating: tachiyomi.domain.taste.model.MangaRating?) = Unit
        }
        val model = buildModel(
            migrateMangaUseCase = migrateMangaUseCase,
            getCrossSourceMangaLinks = getLinks,
            getCrossSourceGroupPrimary = getPrimary,
            restoreCrossSourceGroupState = restoreGroups,
            confirmedGroupTasteRatingActions = ratingActions,
        )
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        val linkWrites = slot<List<CrossSourceMangaLink>>()
        val primaryWrites = slot<List<CrossSourceGroupPrimary>>()
        coVerify {
            restoreGroups.await(
                capture(linkWrites),
                emptyList(),
                capture(primaryWrites),
                emptyList(),
            )
        }
        assertEquals(setOf(1L to "/origin", 2L to "/target"), linkWrites.captured.map { it.source to it.url }.toSet())
        assertEquals(setOf("origin-group"), linkWrites.captured.map { it.groupId }.toSet())
        assertEquals(listOf(unrelatedLink.source to unrelatedLink.url), primaryWrites.captured.map { it.source to it.url })
        assertEquals(listOf("group", "rating-attempt", "migration"), events)
        coVerify(exactly = 1) { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) }
        assertTrue(model.state.value.step is BestVersionStep.Error)
    }

    @Test
    fun `failed identity confirmation does not add group membership or run migration`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target")
        val identityController = mockk<CrossSourceIdentityDecisionController>()
        coEvery { identityController.mutate(any(), any()) } returns
            exh.recs.matching.CrossSourceIdentityMutationResult.FAILED
        val getLinks = mockk<GetCrossSourceMangaLinks>(relaxed = true)
        val restoreGroups = mockk<RestoreCrossSourceGroupState>(relaxed = true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>(relaxed = true)
        val ratingActions = mockk<ConfirmedGroupTasteRatingActions>(relaxed = true)
        val model = buildModel(
            migrateMangaUseCase = migrateMangaUseCase,
            getCrossSourceMangaLinks = getLinks,
            restoreCrossSourceGroupState = restoreGroups,
            identityDecisionController = identityController,
            confirmedGroupTasteRatingActions = ratingActions,
        )
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { getLinks.awaitBySourceUrl(any(), any()) }
        coVerify(exactly = 0) { restoreGroups.await(any(), any(), any(), any()) }
        coVerify(exactly = 0) { migrateMangaUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { ratingActions.propagateToConfirmedMember(any(), any()) }
    }

    @Test
    fun `confirmMigration is a no-op when no target resolves for the selected key`() = runTest {
        val origin = manga(source = 1L, url = "/origin")
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>(relaxed = true)
        val model = buildModel(migrateMangaUseCase = migrateMangaUseCase)
        model.forceOriginManga(origin)
        model.forceState(
            BestVersionCompareScreenModel.State(
                step = BestVersionStep.ConfirmCandidates,
                originManga = origin,
                // selectedBestKey points at a candidate that was never actually added to `candidates` --
                // e.g. it disappeared from the result set between selection and confirmation.
                selectedBestKey = MangaIdentityKey(source = 999L, url = "/missing"),
            ),
        )

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { migrateMangaUseCase(any(), any(), any()) }
        assertTrue(NonUndoableEventJournal.isEmpty())
        assertEquals(null, model.state.value.selectedBestKey, "a missing target must dismiss the migration dialog")
    }

    @Test
    fun `a cancelled migration propagates CancellationException out of the launched coroutine and records no receipt`() = runTest {
        // KMK Confirmed Blocker Remediation Corrective Completion Plan V3 2026-07-29 Phase A: the prior
        // version of this test (see V2's history) only asserted "no receipt recorded", which does not
        // distinguish a real rethrow from a broad catch(Exception) silently converting the cancellation
        // into a BestVersionStep.Error -- both leave the journal empty. This test instead asserts on the
        // launched coroutine's own terminal Job state (model.migrationJob), which can only end up
        // cancelled if confirmMigration() actually rethrows CancellationException instead of catching it.
        // It also positively asserts that the swallowing behavior's specific symptoms (an Error step, a
        // completed migration) never occur, which is exactly what the old broad catch produced instead.
        val origin = manga(source = 1L, url = "/origin")
        val target = manga(source = 2L, url = "/target", id = 99L)
        val sourcePreferences = SourcePreferences(FakePreferenceStore())
        sourcePreferences.evaluationMode().set(true)
        val migrateMangaUseCase = mockk<MigrateMangaUseCase>()
        val ratingActions = mockk<ConfirmedGroupTasteRatingActions>(relaxed = true)
        coEvery { migrateMangaUseCase(current = eq(origin), target = eq(target), replace = eq(false), presetFlags = any(), throttleFunc = any()) } throws
            CancellationException("migration cancelled")
        val model = buildModel(
            migrateMangaUseCase = migrateMangaUseCase,
            sourcePreferences = sourcePreferences,
            confirmedGroupTasteRatingActions = ratingActions,
        )
        model.seedReadyToMigrate(origin, target)

        model.confirmMigration(replace = false)
        advanceUntilIdle()

        val job = model.migrationJob
        assertTrue(job != null, "confirmMigration() must have launched a coroutine")
        assertTrue(job!!.isCancelled, "the CancellationException must propagate out of the coroutine, not be caught")
        assertTrue(!job.isActive, "the cancelled job must have actually completed, not be hung")
        assertTrue(NonUndoableEventJournal.isEmpty(), "a cancelled migration must never record a receipt")
        assertTrue(model.state.value.step !is BestVersionStep.Error, "a real cancellation must never be turned into a fake Error step")
        assertTrue(!model.state.value.migrationComplete, "a cancelled migration must never be marked complete")
        assertEquals(null, model.state.value.completedTargetMangaId, "a cancelled migration must never resolve a completed target")
        coVerify(exactly = 1) { ratingActions.propagateToConfirmedMember(origin, target) }
    }

    @Test
    fun `onDispose closes an executor-backed dispatcher this instance owns by default`() = runTest {
        // KMK V3 Phase B: the production default (ownedFixedThreadPoolDispatcherHandle()) is the only
        // handle that legitimately owns its executor -- proven here by exercising that exact default
        // rather than manually constructing an "owned" DispatcherHandle, so this test also stands as
        // regression coverage for the production wiring itself.
        val model = BestVersionCompareScreenModel(
            originMangaId = 1L,
            getMangaInteractor = mockk(relaxed = true) { coEvery { await(1L) } returns null },
            getChaptersByMangaId = mockk(relaxed = true),
            networkToLocalManga = mockk(relaxed = true),
            migrateMangaUseCase = mockk(relaxed = true),
            upsertQualitySignal = mockk(relaxed = true),
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            sourceManager = mockk(relaxed = true),
            isLowRamDevice = false,
            candidateSearchGateway = mockk(relaxed = true),
            getCrossSourceMangaLinks = mockk(relaxed = true),
            getCrossSourceGroupPrimary = mockk(relaxed = true),
            restoreCrossSourceGroupState = mockk(relaxed = true),
            identityController = identityController(),
            confirmedGroupTasteRatingActions = NoOpConfirmedGroupTasteRatingActions,
        )
        val handle = model.javaClass.getDeclaredField("dispatcherHandle").apply { isAccessible = true }.get(model) as DispatcherHandle
        val executorService = (handle.dispatcher as kotlinx.coroutines.ExecutorCoroutineDispatcher).executor as java.util.concurrent.ExecutorService

        model.onDispose()

        assertTrue(executorService.isShutdown, "onDispose must close the real executor this instance created and owns by default")
    }

    @Test
    fun `onDispose never closes a caller-supplied dispatcher unless its handle explicitly owns it`() = runTest {
        // KMK V3 Phase B: an externally-supplied executor-backed dispatcher, wrapped in a DispatcherHandle
        // that does NOT pass a close action (the default no-op), must never be shut down by this
        // instance -- ownership is decided by the handle's own contract, not by the dispatcher's runtime
        // type. This directly proves the bug V3 flagged: the old `is ExecutorCoroutineDispatcher` check
        // would have closed this dispatcher even though this instance never created it.
        val externalExecutor = Executors.newFixedThreadPool(1)
        val externalDispatcher = externalExecutor.asCoroutineDispatcher()
        val model = buildModel(dispatcherHandle = DispatcherHandle(externalDispatcher))

        model.onDispose()

        assertTrue(!externalExecutor.isShutdown, "a non-owning DispatcherHandle must never close a caller-supplied executor")
        externalExecutor.shutdown()
    }

    @Test
    fun `onDispose closes a caller-supplied dispatcher when its handle explicitly marks it owned`() = runTest {
        // KMK V3 Phase B: the flip side of the previous test -- a caller CAN opt an externally-created
        // executor into being closed by this instance, by supplying its own `close` action in the handle
        // it passes at construction. Ownership is explicit either way; never inferred.
        val externalExecutor = Executors.newFixedThreadPool(1)
        val externalDispatcher = externalExecutor.asCoroutineDispatcher()
        val model = buildModel(dispatcherHandle = DispatcherHandle(externalDispatcher, close = externalExecutor::shutdown))

        model.onDispose()

        assertTrue(externalExecutor.isShutdown, "a DispatcherHandle that explicitly wires close must be closed by onDispose")
    }

    @Test
    fun `onDispose never attempts to close a caller-supplied test dispatcher`() = runTest {
        // A TestDispatcher wrapped in the default no-op-close DispatcherHandle must never be closed --
        // this test exists to prove onDispose() doesn't throw or otherwise misbehave for it either.
        val model = buildModel(dispatcherHandle = DispatcherHandle(StandardTestDispatcher()))

        model.onDispose()
    }

    @Test
    fun `onDispose is idempotent -- a second call never invokes the close action twice`() = runTest {
        // KMK V3 Phase B: disposal must remain safe to call more than once. Counts close invocations
        // directly rather than relying on ExecutorService#shutdown's own idempotency, so this test would
        // fail if the guard in onDispose() were ever removed even for a close action that isn't naturally
        // idempotent.
        var closeCount = 0
        val model = buildModel(dispatcherHandle = DispatcherHandle(StandardTestDispatcher()) { closeCount++ })

        model.onDispose()
        model.onDispose()

        assertEquals(1, closeCount, "the close action must run exactly once even if onDispose() is called twice")
    }

    @Test
    fun `onDispose still cancels searchJob alongside the dispatcher handle close`() = runTest {
        // KMK V3 Phase B: guards against a refactor that accidentally drops the pre-existing
        // searchJob?.cancel() behavior while reworking dispatcher disposal.
        val model = buildModel()
        val job = kotlinx.coroutines.Job()
        val field = BestVersionCompareScreenModel::class.java.getDeclaredField("searchJob")
        field.isAccessible = true
        field.set(model, job)

        model.onDispose()

        assertTrue(job.isCancelled, "onDispose must still cancel searchJob")
    }
}
// KMK <--
