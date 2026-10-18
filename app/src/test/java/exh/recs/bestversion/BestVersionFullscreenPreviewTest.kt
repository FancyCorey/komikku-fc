package exh.recs.bestversion

// KMK independent_codex_recheck_2026-08-26 -->
import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.recs.RecommendationErrorKind
import exh.recs.matching.CrossSourceIdentityDecisionController
import exh.recs.matching.MangaIdentityKey
import exh.recs.matching.NoOpConfirmedGroupTasteRatingActions
import exh.recs.matching.SameMangaCandidateResult
import exh.util.DispatcherHandle
import exh.util.FakePreferenceStore
import exh.util.FakeTasteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.migration.usecases.MigrateMangaUseCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.UpsertMangaSourceQualitySignal

/**
 * Production-bound tests for [BestVersionCompareScreenModel.openFullscreenCandidate],
 * [BestVersionCompareScreenModel.requestFullscreenPageImage], and
 * [BestVersionCompareScreenModel.retryFullscreenPageImage] -- the fix for the
 * independent_codex_recheck_2026-08-26 finding that the fullscreen candidate preview only ever
 * rendered [BestVersionPageSampler]'s bounded 5-page sample and that Retry never re-invoked real
 * page-list/image-URL resolution.
 *
 * Uses the exact same construction pattern as [BestVersionCompareScreenModelConfirmMigrationTest]:
 * `getMangaInteractor.await()` stubbed to return null so `init {}` sets an Error state and returns
 * before ever calling `startSearch()`, then reflection seeds `originManga`/`mutableState` directly
 * with a ready-to-preview [BestVersionCompareScreenModel.State]. This exercises the real production
 * functions under test exactly as written; it only avoids re-deriving unrelated state through the
 * real (and here, irrelevant) candidate-search pipeline.
 */
class BestVersionFullscreenPreviewTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manga(source: Long, url: String, id: Long = source) =
        Manga.create().copy(id = id, source = source, url = url, ogTitle = "Manga $url", favorite = false)

    private fun sourceChapter(number: Float = 5f): SChapter = SChapter.create().apply {
        url = "/chapter/$number"
        name = "Chapter $number"
        chapter_number = number
    }

    // KMK: SourceRuntime.run's suppression registry and failure recording key off source.id/name/
    // lang, not off the manga's own `source` field -- a bare mockk<HttpSource>(relaxed = true) with
    // no stubbed `.id` defaults every mock's id to 0L, which made every test's mock source share the
    // SAME suppression identity and silently cross-contaminate each other's SourceRuntime failure
    // state. Mirrors the identical httpSource(id, name) helper already established in
    // BestVersionHostRouteFixtureTest.kt.
    private fun httpSource(id: Long, name: String = "Candidate"): HttpSource = mockk<HttpSource>(relaxed = true).also { source ->
        every { source.id } returns id
        every { source.name } returns name
        every { source.lang } returns "en"
    }

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

    /** Seeds a SelectChapter/ComparePreview-shaped state where [target] has an Available chapter. */
    private fun BestVersionCompareScreenModel.seedPreviewable(origin: Manga, target: Manga, chapter: SChapter) {
        forceOriginManga(origin)
        val key = MangaIdentityKey(target.source, target.url)
        forceState(
            BestVersionCompareScreenModel.State(
                step = BestVersionStep.ComparePreview,
                originManga = origin,
                candidates = persistentMapOf(mockk<Source>(relaxed = true) to SameMangaCandidateResult.Success(listOf(target))),
                selectedKeys = setOf(key),
                candidateChapters = persistentMapOf(
                    key to CandidateChapterState.Available(chapter = chapter, totalChapters = 1),
                ),
            ),
        )
    }

    @Test
    fun `starting preview again cancels the previous run and keeps its result out of state`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val source = httpSource(2L)
        val page = Page(0, "", "https://example.com/preview.jpg")
        var pageListCalls = 0
        var firstRequestCancelled = false
        coEvery { source.getPageList(any()) } coAnswers {
            pageListCalls++
            if (pageListCalls == 1) {
                try {
                    awaitCancellation()
                } finally {
                    firstRequestCancelled = true
                }
                error("the first preview request should be cancelled")
            } else {
                listOf(page)
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.startPreview()
        runCurrent()
        model.startPreview()
        advanceUntilIdle()

        val key = MangaIdentityKey(2L, "/candidate")
        assertEquals(2, pageListCalls)
        assertTrue(firstRequestCancelled)
        assertEquals(BestVersionStep.ComparePreview, model.state.value.step)
        assertTrue(model.state.value.candidatePreviews[key] is CandidatePreviewState.Loaded)
    }

    private fun identityController(): CrossSourceIdentityDecisionController {
        val repository = FakeTasteRepository()
        return CrossSourceIdentityDecisionController(
            GetCrossSourceIdentityDecisions(repository),
            ReplaceCrossSourceIdentityDecisions(repository),
        )
    }

    private fun buildModel(
        sourceManager: SourceManager,
        dispatcher: TestDispatcher = StandardTestDispatcher(),
        originMangaId: Long = 1L,
    ): BestVersionCompareScreenModel {
        val getManga = mockk<GetManga>(relaxed = true)
        coEvery { getManga.await(originMangaId) } returns null
        return BestVersionCompareScreenModel(
            originMangaId = originMangaId,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            sourceManager = sourceManager,
            getMangaInteractor = getManga,
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
            migrateMangaUseCase = mockk<MigrateMangaUseCase>(relaxed = true),
            upsertQualitySignal = mockk<UpsertMangaSourceQualitySignal>(relaxed = true),
            isLowRamDevice = false,
            dispatcherHandle = DispatcherHandle(dispatcher),
            candidateSearchGateway = mockk(relaxed = true),
            getCrossSourceMangaLinks = mockk(relaxed = true),
            getCrossSourceGroupPrimary = mockk(relaxed = true),
            restoreCrossSourceGroupState = mockk(relaxed = true),
            identityController = identityController(),
            confirmedGroupTasteRatingActions = NoOpConfirmedGroupTasteRatingActions,
        )
    }

    // --- openFullscreenCandidate: the full, unsampled page list ---

    @Test
    fun `openFullscreenCandidate loads every page, not BestVersionPageSampler's bounded sample`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val fullPageList = (0 until 23).map { Page(it, "", "https://example.com/$it.jpg") }
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns fullPageList
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())

        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as? FullscreenPreviewState.Loaded
        assertTrue(loaded != null, "expected FullscreenPreviewState.Loaded")
        assertEquals(23, loaded!!.pages.size, "the fullscreen preview must show every fetched page, not a 5-page sample")
        assertEquals(key, loaded.key)
    }

    @Test
    fun `openFullscreenCandidate reports a typed error when the source is missing`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns null
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())

        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        assertEquals(
            FullscreenPreviewState.Error(BestVersionErrorReason.SourceUnavailable),
            model.state.value.fullscreenPreview,
        )
    }

    @Test
    fun `openFullscreenCandidate reports an error rather than a silent empty preview when the page list is empty`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns emptyList()
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())

        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        assertTrue(model.state.value.fullscreenPreview is FullscreenPreviewState.Error)
    }

    @Test
    fun `openFullscreenCandidate times out a stalled page list and retry can succeed`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", "https://example.com/0.jpg"))
        val source = httpSource(2L)
        var attempts = 0
        coEvery { source.getPageList(any()) } coAnswers {
            attempts += 1
            if (attempts == 1) awaitCancellation() else pages
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        assertEquals(
            FullscreenPreviewState.Error(
                BestVersionErrorReason.Recommendation(RecommendationErrorKind.Timeout),
            ),
            model.state.value.fullscreenPreview,
        )

        model.retryFullscreenCandidate()
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as? FullscreenPreviewState.Loaded
        assertEquals(key, loaded?.key)
        assertEquals(pages, loaded?.pages)
        coVerify(exactly = 2) { source.getPageList(any()) }
    }

    @Test
    fun `retryFullscreenCandidate reloads a failed full page list for the same candidate`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        val pages = listOf(Page(0, "", "https://example.com/0.jpg"))
        coEvery { source.getPageList(any()) } returnsMany listOf(emptyList(), pages)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        assertTrue(model.state.value.fullscreenPreview is FullscreenPreviewState.Error)

        model.retryFullscreenCandidate()
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as? FullscreenPreviewState.Loaded
        assertEquals(key, loaded?.key)
        assertEquals(pages, loaded?.pages)
        coVerify(exactly = 2) { source.getPageList(any()) }
    }

    // --- requestFullscreenPageImage: genuinely lazy, per-page ---

    @Test
    fun `requestFullscreenPageImage resolves only the requested page, leaving others untouched`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = (0 until 5).map { Page(it, "", null) }
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        coEvery { source.getImageUrl(any()) } answers { "https://example.com/${(firstArg<Page>()).index}.jpg" }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(2)
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertTrue(loaded.pageImages[2] is PreviewPageImageState.Resolved, "the requested page must resolve")
        assertNull(loaded.pageImages[0], "an unrequested page must remain untouched (never eagerly resolved)")
        assertNull(loaded.pageImages[1], "an unrequested page must remain untouched (never eagerly resolved)")
        assertNull(loaded.pageImages[3], "an unrequested page must remain untouched (never eagerly resolved)")
        assertNull(loaded.pageImages[4], "an unrequested page must remain untouched (never eagerly resolved)")
        coVerify(exactly = 1) { source.getImageUrl(pages[2]) }
        coVerify(exactly = 0) { source.getImageUrl(pages[0]) }
    }

    @Test
    fun `fullscreen image URL resolution times out and retry can succeed`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "", null))
        var attempts = 0
        coEvery { source.getImageUrl(any()) } coAnswers {
            attempts += 1
            if (attempts == 1) awaitCancellation() else "https://example.com/0.jpg"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        model.requestFullscreenPageImage(0)
        advanceUntilIdle()

        val failed = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertEquals(
            PreviewPageImageState.Failed(
                BestVersionErrorReason.Recommendation(RecommendationErrorKind.Timeout),
            ),
            failed.pageImages[0],
        )

        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        val retried = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertTrue(retried.pageImages[0] is PreviewPageImageState.Resolved)
        coVerify(exactly = 2) { source.getPageList(any()) }
        coVerify(exactly = 2) { source.getImageUrl(any()) }
    }

    @Test
    fun `requesting an already-resolved page again does not re-invoke getImageUrl`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", null))
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        coEvery { source.getImageUrl(any()) } returns "https://example.com/0.jpg"
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()
        model.requestFullscreenPageImage(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { source.getImageUrl(any()) }
    }

    @Test
    fun `a page whose image URL fails to resolve becomes a Failed state, never silently dropped`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", null))
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        coEvery { source.getImageUrl(any()) } throws RuntimeException("boom")
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertTrue(loaded.pageImages[0] is PreviewPageImageState.Failed, "a page must never disappear from pageImages just because resolution failed")
    }

    // --- retryFullscreenPageImage: a genuine re-fetch, not a Compose-only generation bump ---

    @Test
    fun `retryFullscreenPageImage genuinely re-invokes getImageUrl a second time`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", null))
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        var callCount = 0
        coEvery { source.getImageUrl(any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("first attempt fails") else "https://example.com/recovered.jpg"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()
        var loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertTrue(loaded.pageImages[0] is PreviewPageImageState.Failed, "precondition: first attempt must fail")

        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        coVerify(exactly = 2) { source.getImageUrl(any()) }
        loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        val resolved = loaded.pageImages[0] as? PreviewPageImageState.Resolved
        assertTrue(resolved != null, "retry must genuinely recover once the underlying call succeeds")
        assertEquals("https://example.com/recovered.jpg", resolved!!.preview.preview.imageUrl)
    }

    @Test
    fun `Coil byte failure and typed failure retry together while success and loading stay untouched`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(
            Page(0, "", "https://example.com/success.jpg"),
            Page(1, "", "https://example.com/byte-fails.jpg"),
            Page(2, "", null),
            Page(3, "", "https://example.com/still-loading.jpg"),
        )
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        var imageUrlCalls = 0
        coEvery { source.getImageUrl(any()) } answers {
            imageUrlCalls++
            if (imageUrlCalls == 1) throw RuntimeException("typed URL failure")
            "https://example.com/recovered.jpg"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        model.requestFullscreenPageImage(0)
        model.requestFullscreenPageImage(1)
        model.requestFullscreenPageImage(2)
        advanceUntilIdle()

        val beforeRetry = model.state.value
        val loaded = beforeRetry.fullscreenPreview as FullscreenPreviewState.Loaded
        val successfulPage = loaded.pageImages[0] as PreviewPageImageState.Resolved
        assertTrue(loaded.pageImages[2] is PreviewPageImageState.Failed)
        model.forceState(
            beforeRetry.copy(
                fullscreenPreview = loaded.copy(
                    pageImages = loaded.pageImages.put(3, PreviewPageImageState.Loading),
                ),
            ),
        )
        val generation = model.state.value.fullscreenPageGenerations[1]!!
        model.reportFullscreenPageImageLoadFailed(
            candidateKey = key,
            pageIndex = 1,
            expectedOpenGeneration = model.state.value.fullscreenOpenGeneration,
            expectedPageGeneration = generation,
        )
        assertTrue(
            (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded)
                .pageImages[1] is PreviewPageImageState.ImageLoadFailed,
        )

        model.retryFullscreenPageImage(1)
        advanceUntilIdle()

        val afterRetry = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertEquals(successfulPage, afterRetry.pageImages[0], "a successfully loaded page must remain unchanged")
        assertTrue(afterRetry.pageImages[1] is PreviewPageImageState.Resolved, "the Coil byte-failed page must be resolved again")
        assertTrue(afterRetry.pageImages[2] is PreviewPageImageState.Resolved, "the other typed failure must recover in the same retry")
        assertEquals(PreviewPageImageState.Loading, afterRetry.pageImages[3], "an in-flight page must not join the retry scope")
        assertEquals(2, imageUrlCalls, "the typed failure is resolved once initially and once on retry")
        coVerify(exactly = 3) { source.getPageList(any()) }
    }

    @Test
    fun `Coil failure callback ignores stale candidate page and fullscreen generations`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "", "https://example.com/page.jpg"))
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        model.requestFullscreenPageImage(0)
        advanceUntilIdle()

        val opened = model.state.value
        val pageGeneration = opened.fullscreenPageGenerations[0]!!
        fun report(candidate: MangaIdentityKey = key, openGeneration: Int = opened.fullscreenOpenGeneration, generation: Int = pageGeneration) {
            model.reportFullscreenPageImageLoadFailed(candidate, 0, openGeneration, generation)
        }
        report(openGeneration = opened.fullscreenOpenGeneration - 1)
        report(candidate = MangaIdentityKey(99L, "/other"))
        report(generation = pageGeneration - 1)
        assertTrue(
            (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded)
                .pageImages[0] is PreviewPageImageState.Resolved,
        )

        report()
        assertTrue(
            (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded)
                .pageImages[0] is PreviewPageImageState.ImageLoadFailed,
        )
        model.retryFullscreenPageImage(0)
        advanceUntilIdle()
        report() // The retry has superseded the callback's page generation.
        assertTrue(
            (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded)
                .pageImages[0] is PreviewPageImageState.Resolved,
        )

        model.closeFullscreenCandidate()
        report() // A delayed callback after dismissal cannot reopen or mutate the preview.
        assertNull(model.state.value.fullscreenPreview)
        model.onDispose()
        report() // Disposal also makes a delayed callback inert.
        assertNull(model.state.value.fullscreenPreview)
    }

    @Test
    fun `retryFullscreenPageImage leaves an already successful page untouched`() = runTest {
        // Retry is limited to pages currently exposing a retryable failure. A successful page is
        // already usable and must not be re-requested as collateral work.
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", null))
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        var callCount = 0
        coEvery { source.getImageUrl(any()) } answers {
            callCount++
            "https://example.com/call-$callCount.jpg"
        }
        // KMK: source is a plain relaxed mockk<HttpSource>, so `this is EhBasedSource` in
        // isEhBasedSource() is naturally false -- no stub needed; retryFullscreenPageImage's
        // forceRetry=true path is exercised regardless of the EH special case here.
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()
        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { source.getImageUrl(any()) }
    }

    // --- a failed page becomes retryable only after its first attempt completes ---

    @Test
    fun `a loading page is not included in the retry scope`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val pages = listOf(Page(0, "", null))
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns pages
        var callCount = 0
        coEvery { source.getImageUrl(any()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("initial preview failure") else "https://example.com/fresh.jpg"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        // A StandardTestDispatcher makes the initial request's loading state observable before the
        // queued operation runs, so a premature Retry can be proven to do nothing.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = buildModel(sourceManager, dispatcher = dispatcher)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        model.retryFullscreenPageImage(0) // still Loading, therefore not retryable
        advanceUntilIdle()

        val loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        val failed = loaded.pageImages[0] as? PreviewPageImageState.Failed
        assertTrue(failed != null)
        assertEquals(1, callCount)
        model.retryFullscreenPageImage(0)
        advanceUntilIdle()
        val resolved = (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded).pageImages[0]
            as? PreviewPageImageState.Resolved
        assertTrue(resolved != null)
        assertEquals(
            "https://example.com/fresh.jpg",
            resolved!!.preview.preview.imageUrl,
            "the failed page should recover only after an explicit retry",
        )
    }

    // --- closeFullscreenCandidate ---

    @Test
    fun `closeFullscreenCandidate clears preview and generation state`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "", "https://example.com/0.jpg"))
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        // KMK: SourceRuntimeFailureRegistry is a process-global singleton not reset between tests --
        // clear this source id's entry first so an earlier test's recorded failure can never
        // spuriously suppress this test's own SourceRuntime.run call (same convention SourceRuntimeTest
        // and RecommendationHostRouteFixtureTest already use).
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        assertTrue(model.state.value.fullscreenPreview != null)

        model.closeFullscreenCandidate()

        assertNull(model.state.value.fullscreenPreview)
        assertTrue(model.state.value.fullscreenPageGenerations.isEmpty())
    }

    @Test
    fun `requestFullscreenPageImage and retryFullscreenPageImage are no-ops when no preview is open`() = runTest {
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val model = buildModel(sourceManager)

        model.requestFullscreenPageImage(0)
        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        assertNull(model.state.value.fullscreenPreview)
        assertTrue(model.state.value.fullscreenPageGenerations.isEmpty())
    }

    // --- C2 (E4.2): overlapping openFullscreenCandidate requests must be generation-owned ---

    @Test
    fun `an older overlapping open never populates the dialog after a newer candidate was opened`() = runTest {
        val origin = manga(1L, "/origin")
        val candidateA = manga(2L, "/candidate-a")
        val candidateB = manga(3L, "/candidate-b")
        val keyA = MangaIdentityKey(2L, "/candidate-a")
        val keyB = MangaIdentityKey(3L, "/candidate-b")
        val sourceA = httpSource(2L, "A")
        val sourceB = httpSource(3L, "B")
        // KMK: sourceA's page-list fetch is held open (a real, never-completing suspend point, not
        // just "queued but not yet run" like the StandardTestDispatcher tests above) so its
        // completion can be driven strictly AFTER sourceB's newer open has already resolved -- this
        // is what actually distinguishes "an older overlapping open" from "a request queued before a
        // later one on the same dispatcher."
        val sourceAGate = kotlinx.coroutines.CompletableDeferred<List<Page>>()
        var sourceACancelled = false
        coEvery { sourceA.getPageList(any()) } coAnswers {
            try {
                sourceAGate.await()
            } finally {
                sourceACancelled = true
            }
        }
        coEvery { sourceB.getPageList(any()) } returns listOf(Page(0, "", "https://example.com/b-0.jpg"))
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns sourceA
        every { sourceManager.get(3L) } returns sourceB
        val model = buildModel(sourceManager, dispatcher = StandardTestDispatcher(testScheduler))
        forceOriginMangaAndCandidates(model, origin, candidateA, candidateB)
        SourceRuntimeFailureRegistry.clear(2L)
        SourceRuntimeFailureRegistry.clear(3L)

        model.openFullscreenCandidate(keyA) // generation 1, page-list fetch never completes
        advanceUntilIdle()
        model.openFullscreenCandidate(keyB) // generation 2, supersedes A before A ever resolves
        advanceUntilIdle()

        val loadedAfterB = model.state.value.fullscreenPreview as? FullscreenPreviewState.Loaded
        assertTrue(loadedAfterB != null, "candidate B's open must have resolved")
        assertEquals(keyB, loadedAfterB!!.key)
        assertTrue(sourceACancelled, "opening B should cancel A's cooperative page-list request")

        // Now let A's stale fetch finally complete -- it must be silently dropped, not overwrite B.
        sourceAGate.complete(listOf(Page(0, "", "https://example.com/a-0.jpg")))
        advanceUntilIdle()

        val loadedAfterStaleA = model.state.value.fullscreenPreview as? FullscreenPreviewState.Loaded
        assertTrue(loadedAfterStaleA != null, "B's loaded state must survive A's late-arriving response")
        assertEquals(keyB, loadedAfterStaleA!!.key, "an older overlapping open must never populate a newer candidate's dialog")
    }

    @Test
    fun `an in-flight open never repopulates the dialog after closeFullscreenCandidate`() = runTest {
        val origin = manga(1L, "/origin")
        val candidateA = manga(2L, "/candidate-a")
        val keyA = MangaIdentityKey(2L, "/candidate-a")
        val sourceA = httpSource(2L, "A")
        val gate = kotlinx.coroutines.CompletableDeferred<List<Page>>()
        var sourceCancelled = false
        coEvery { sourceA.getPageList(any()) } coAnswers {
            try {
                gate.await()
            } finally {
                sourceCancelled = true
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns sourceA
        val model = buildModel(sourceManager, dispatcher = StandardTestDispatcher(testScheduler))
        forceOriginMangaAndCandidates(model, origin, candidateA, candidateA)
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(keyA)
        advanceUntilIdle()
        model.closeFullscreenCandidate()
        assertNull(model.state.value.fullscreenPreview)
        assertTrue(sourceCancelled, "closing the dialog should cancel its cooperative page-list request")

        gate.complete(listOf(Page(0, "", "https://example.com/a-0.jpg")))
        advanceUntilIdle()

        assertNull(model.state.value.fullscreenPreview, "a stale open must never repopulate the dialog after it was explicitly closed")
    }

    @Test
    fun `disposing the screen model cancels its in-flight fullscreen page list`() = runTest {
        val origin = manga(1L, "/origin")
        val candidate = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        var sourceCancelled = false
        coEvery { source.getPageList(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                sourceCancelled = true
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager, dispatcher = StandardTestDispatcher(testScheduler))
        forceOriginMangaAndCandidates(model, origin, candidate, candidate)
        SourceRuntimeFailureRegistry.clear(2L)

        model.openFullscreenCandidate(key)
        advanceUntilIdle()
        model.onDispose()
        advanceUntilIdle()

        assertTrue(sourceCancelled, "disposing the screen model should cancel its cooperative page-list request")
    }

    // --- C2 (E4.1): a forced non-EH retry must obtain a genuinely fresh URL, not reuse a stale one ---

    @Test
    fun `retryFullscreenPageImage on a non-EH source refetches a failed page`() = runTest {
        val origin = manga(1L, "/origin")
        val target = manga(2L, "/candidate")
        val key = MangaIdentityKey(2L, "/candidate")
        val source = httpSource(2L)
        var pageListCallCount = 0
        coEvery { source.getPageList(any()) } answers {
            pageListCallCount++
            listOf(
                if (pageListCallCount == 1) {
                    Page(0, "", null)
                } else {
                    Page(0, "", "https://example.com/refreshed.jpg")
                },
            )
        }
        coEvery { source.getImageUrl(any()) } throws RuntimeException("initial preview failure")
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(2L) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(2L)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()
        var loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertEquals(
            PreviewPageImageState.Failed(BestVersionErrorReason.Recommendation(RecommendationErrorKind.Internal)),
            loaded.pageImages[0],
        )

        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        assertEquals(2, pageListCallCount, "a forced non-EH retry must refetch the page list rather than reusing the original response")
        // The first failed attempt uses getImageUrl; the retry receives a page-list URL and must
        // not invoke getImageUrl again for that refreshed page.
        coVerify(exactly = 1) { source.getImageUrl(any()) }
        loaded = model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded
        assertEquals(
            "https://example.com/refreshed.jpg",
            (loaded.pageImages[0] as PreviewPageImageState.Resolved).preview.preview.imageUrl,
            "retry must surface the freshly refetched URL, not the original expired one",
        )
    }

    @Test
    fun `retryFullscreenPageImage on an EH source still refreshes via getImageUrl, not a page-list refetch`() = runTest {
        val ehSourceId = exh.source.EH_SOURCE_ID
        val origin = manga(1L, "/origin")
        val target = manga(ehSourceId, "/candidate")
        val key = MangaIdentityKey(ehSourceId, "/candidate")
        // KMK: isEhBasedSource() requires both `this is EhBasedSource` AND `id in eHentaiSourceIds`
        // -- mockk's moreInterfaces lets one mock satisfy the marker interface while HttpSource
        // supplies the rest of the surface this screen model actually calls.
        val source = mockk<HttpSource>(
            relaxed = true,
            moreInterfaces = arrayOf(eu.kanade.tachiyomi.source.online.all.EhBasedSource::class),
        ).also {
            every { it.id } returns ehSourceId
            every { it.name } returns "EH-like"
            every { it.lang } returns "en"
        }
        // KMK: imageUrl left null so both the initial request and the forced retry each genuinely
        // need to call getImageUrl -- a non-null seeded URL would make the initial request skip
        // resolution entirely (nothing to compare the retry's behavior against).
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "", null))
        var imageUrlCallCount = 0
        coEvery { source.getImageUrl(any()) } answers {
            imageUrlCallCount++
            if (imageUrlCallCount == 1) throw RuntimeException("initial preview failure")
            "https://example.com/eh-rotated-$imageUrlCallCount.jpg"
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(ehSourceId) } returns source
        val model = buildModel(sourceManager)
        model.seedPreviewable(origin, target, sourceChapter())
        SourceRuntimeFailureRegistry.clear(ehSourceId)
        model.openFullscreenCandidate(key)
        advanceUntilIdle()

        model.requestFullscreenPageImage(0)
        advanceUntilIdle()
        assertEquals(
            PreviewPageImageState.Failed(BestVersionErrorReason.Recommendation(RecommendationErrorKind.Internal)),
            (model.state.value.fullscreenPreview as FullscreenPreviewState.Loaded).pageImages[0],
        )
        model.retryFullscreenPageImage(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { source.getPageList(any()) }
        assertEquals(2, imageUrlCallCount, "EH sources must keep using getImageUrl for a forced retry, as before")
    }

    /** Seeds a ComparePreview-shaped state with two selectable candidates for the overlapping-open tests. */
    private fun forceOriginMangaAndCandidates(model: BestVersionCompareScreenModel, origin: Manga, a: Manga, b: Manga) {
        model.forceOriginManga(origin)
        val keyA = MangaIdentityKey(a.source, a.url)
        val keyB = MangaIdentityKey(b.source, b.url)
        model.forceState(
            BestVersionCompareScreenModel.State(
                step = BestVersionStep.ComparePreview,
                originManga = origin,
                candidates = persistentMapOf(mockk<Source>(relaxed = true) to SameMangaCandidateResult.Success(listOf(a, b))),
                selectedKeys = setOf(keyA, keyB),
                candidateChapters = persistentMapOf(
                    keyA to CandidateChapterState.Available(chapter = sourceChapter(), totalChapters = 1),
                    keyB to CandidateChapterState.Available(chapter = sourceChapter(), totalChapters = 1),
                ),
            ),
        )
    }
}
// KMK <--
