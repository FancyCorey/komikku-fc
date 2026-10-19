package exh.recs

import android.content.Context
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.recs.matching.ConfirmedMangaGroupTargets
import exh.recs.sources.GenreFilterMapper
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.ClearMangaTaste
import tachiyomi.domain.taste.interactor.ClearRecommendationCache
import tachiyomi.domain.taste.interactor.GetChapterCountsByMangaIds
import tachiyomi.domain.taste.interactor.GetDisabledRecommendationSources
import tachiyomi.domain.taste.interactor.GetKnownRecommendationMangaIds
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.GetRecommendationCache
import tachiyomi.domain.taste.interactor.GetRecommendationCandidateMemory
import tachiyomi.domain.taste.interactor.GetRecommendationDiscoveryProgress
import tachiyomi.domain.taste.interactor.GetTagAliases
import tachiyomi.domain.taste.interactor.GetTasteProfile
import tachiyomi.domain.taste.interactor.PruneRecommendationCandidateMemory
import tachiyomi.domain.taste.interactor.PruneRecommendationExposure
import tachiyomi.domain.taste.interactor.RecordRecommendationExposure
import tachiyomi.domain.taste.interactor.SetMangaTasteBatch
import tachiyomi.domain.taste.interactor.UpsertRecommendationCache
import tachiyomi.domain.taste.interactor.UpsertRecommendationCandidateMemory
import tachiyomi.domain.taste.interactor.UpsertRecommendationDiscoveryProgress
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.taste.model.RatedMangaVisibility
import tachiyomi.domain.taste.model.RecommendationCacheEntry
import tachiyomi.domain.taste.model.TasteProfile
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Direct production-path validation for the real BrowsePersonalRecommendationsScreenModel.
 *
 * The model is constructed with [autoLoad] disabled only to prevent the Android/network load from
 * starting before a unit test has installed its collaborators. No production method is replaced:
 * tracker resolution and visible-exposure recording execute on the real ScreenModel instance.
 * The full search assembly remains separately classified as partial because it requires the
 * application context, source runtime, and 25-collaborator load graph.
 */
class BrowsePersonalRecommendationsScreenModelDirectValidationTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class Harness(
        val model: BrowsePersonalRecommendationsScreenModel,
        val recordExposure: RecordRecommendationExposure,
    )

    private fun buildHarness(
        trackRepository: TrackRepository = mockk(relaxed = true),
        recordExposure: RecordRecommendationExposure = mockk(relaxed = true),
        getTasteProfile: GetTasteProfile = mockk(relaxed = true),
        getTagAliases: GetTagAliases = mockk(relaxed = true),
        getDisabledSources: GetDisabledRecommendationSources = mockk(relaxed = true),
        getMangaTaste: GetMangaTaste = mockk(relaxed = true),
        sourceManager: SourceManager = mockk(relaxed = true),
        networkToLocalManga: NetworkToLocalManga = mockk(relaxed = true),
        isOnline: () -> Boolean = { true },
        clock: () -> Long = { 1_000L },
        searchDispatcher: CoroutineDispatcher = Dispatchers.Main,
        getManga: GetManga = mockk(relaxed = true),
        getCache: GetRecommendationCache = mockk(relaxed = true),
        getKnownManga: GetKnownRecommendationMangaIds = mockk(relaxed = true),
        getChapterCounts: GetChapterCountsByMangaIds = mockk(relaxed = true),
        getMemory: GetRecommendationCandidateMemory = mockk(relaxed = true),
        getRecentReadingTagWeights: GetRecentReadingTagWeights? = null,
        isNetworkConstrained: () -> Boolean = { false },
        getSourceRecommendationFit: tachiyomi.domain.taste.interactor.GetSourceRecommendationFit? = null,
        upsertDiscovery: UpsertRecommendationDiscoveryProgress = mockk(relaxed = true),
        preferences: SourcePreferences = SourcePreferences(FakePreferenceStore()),
        naturalObservationSink: ((String) -> Unit)? = null,
        debugBuild: Boolean = eu.kanade.tachiyomi.BuildConfig.DEBUG,
    ): Harness {
        val model = BrowsePersonalRecommendationsScreenModel(
            context = mockk<Context>(relaxed = true),
            isOnline = isOnline,
            clock = clock,
            searchDispatcher = searchDispatcher,
            isLowRamDevice = false,
            autoLoad = false,
            naturalObservationSink = naturalObservationSink,
            isDebugBuild = debugBuild,
            getTasteProfile = getTasteProfile,
            getRecentReadingTagWeights = getRecentReadingTagWeights,
            isNetworkConstrained = isNetworkConstrained,
            getSourceRecommendationFit = getSourceRecommendationFit,
            getTagAliases = getTagAliases,
            getDisabledSources = getDisabledSources,
            sourceManager = sourceManager,
            networkToLocalManga = networkToLocalManga,
            getMangaInteractor = getManga,
            getMangaTaste = getMangaTaste,
            sourcePreferences = preferences,
            getRecommendationCache = getCache,
            upsertRecommendationCache = mockk<UpsertRecommendationCache>(relaxed = true),
            clearRecommendationCache = mockk<ClearRecommendationCache>(relaxed = true),
            getKnownMangaIds = getKnownManga,
            getChapterCounts = getChapterCounts,
            getMemory = getMemory,
            upsertMemory = mockk<UpsertRecommendationCandidateMemory>(relaxed = true),
            pruneMemory = mockk<PruneRecommendationCandidateMemory>(relaxed = true),
            getDiscoveryProgress = mockk<GetRecommendationDiscoveryProgress>(relaxed = true),
            upsertDiscoveryProgress = upsertDiscovery,
            setMangaTasteBatch = mockk<SetMangaTasteBatch>(relaxed = true),
            clearMangaTaste = mockk<ClearMangaTaste>(relaxed = true),
            getTracks = GetTracks(trackRepository),
            localTrackerRepository = mockk<LocalTrackerRepository>(relaxed = true),
            confirmedMangaGroupTargets = mockk<ConfirmedMangaGroupTargets>(relaxed = true),
            getRecommendationExposure = mockk(relaxed = true),
            recordRecommendationExposure = recordExposure,
            pruneRecommendationExposure = mockk<PruneRecommendationExposure>(relaxed = true),
        )
        return Harness(model, recordExposure)
    }

    private fun candidate(
        sourceId: Long,
        mangaId: Long,
        url: String,
        title: String = "Manga $url",
    ): PersonalRecommendation =
        PersonalRecommendation(
            manga = Manga.create().copy(
                id = mangaId,
                source = sourceId,
                url = url,
                ogTitle = title,
            ),
            score = 1.0,
            matchedGroups = emptyList(),
        )

    private fun track(mangaId: Long) = Track(
        id = 1L,
        mangaId = mangaId,
        trackerId = 2L,
        remoteId = 3L,
        libraryId = null,
        title = "Tracked",
        lastChapterRead = 0.0,
        totalChapters = 10L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    @Suppress("UNCHECKED_CAST")
    private fun forceState(
        model: BrowsePersonalRecommendationsScreenModel,
        state: BrowsePersonalRecommendationsScreenModel.State,
    ) {
        val field = StateScreenModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        (field.get(model) as kotlinx.coroutines.flow.MutableStateFlow<BrowsePersonalRecommendationsScreenModel.State>).value = state
    }

    private suspend fun resolveTrackedExposureKeys(
        model: BrowsePersonalRecommendationsScreenModel,
        sourceId: Long,
        candidates: List<PersonalRecommendation>,
    ): RecommendationDisplayReranker.TrackedState {
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "resolveTrackedExposureKeys" }
            .apply { isAccessible = true }
        return try {
            function.callSuspend(model, sourceId, candidates) as RecommendationDisplayReranker.TrackedState
        } catch (error: InvocationTargetException) {
            throw (error.cause ?: error)
        }
    }

    @Test
    fun `cached manga keep saved order duplicates and score positions after batch lookup`() = runTest {
        val repository = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        val first = candidate(7L, 1L, "/first").manga
        val second = candidate(7L, 2L, "/second").manga
        coEvery { repository.getMangaByIds(listOf(2L, 99L, 1L)) } returns listOf(first, second)
        val cache = mockk<GetRecommendationCache>()
        coEvery { cache.await(any()) } returns RecommendationCacheEntry(
            cacheKey = "test", sourceId = 7L, profileFingerprint = "profile", queryKey = "query",
            resultMangaIds = "2,99,1,2", resultScores = "9,8,7", resultReasons = "action",
            createdAt = 0L, expiresAt = 2_000L,
        )
        val model = buildHarness(getManga = GetManga(repository), getCache = cache).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCache" }.apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val result = function.callSuspend(
            model, 7L, "query", "profile", emptyMap<Any, Any>(),
            RatedMangaVisibility.SHOW_ALL_RATED, false, emptySet<Any>(), 0,
        ) as List<PersonalRecommendation>

        assertEquals(listOf(2L, 1L, 2L), result.map { it.manga.id })
        assertEquals(listOf(9.0, 7.0, 0.0), result.map { it.score })
        assertTrue(result.all { it.matchedGroups == listOf("action") })
        coVerify(exactly = 1) { repository.getMangaByIds(any()) }
        coVerify(exactly = 0) { repository.getMangaById(any()) }
    }

    @Test
    fun `cache restoration applies all active visibility filters in batches`() = runTest {
        val favoriteManga = mockk<Manga> {
            every { id } returns 1L
            every { favorite } returns true
        }
        val manga = listOf(favoriteManga) + (2L..8L).map { candidate(7L, it, "/manga/$it").manga }
        val repository = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        coEvery { repository.getMangaByIds(any()) } returns manga.reversed()
        val known = mockk<GetKnownRecommendationMangaIds>()
        coEvery { known.await(any()) } returns setOf(4L)
        val counts = mockk<GetChapterCountsByMangaIds>()
        coEvery { counts.await(any()) } returns mapOf(5L to 4L, 7L to 20L, 8L to 0L)
        val cache = mockk<GetRecommendationCache>()
        coEvery { cache.await(any()) } returns RecommendationCacheEntry(
            cacheKey = "test", sourceId = 7L, profileFingerprint = "profile", queryKey = "query",
            resultMangaIds = "1,2,3,4,5,6,7,8,7", resultScores = "1,2,3,4,5,6,7,8,9",
            resultReasons = "action", createdAt = 0L, expiresAt = 2_000L,
        )
        val rated = manga[1]
        val tastes = mapOf(
            MangaTasteKey(rated.source, rated.url) to MangaTaste(
                rated.id,
                rated.source,
                rated.url,
                rated.title,
                MangaRating.DISLIKE.value,
                0L,
                0L,
            ),
        )
        val model = buildHarness(
            getManga = GetManga(repository),
            getCache = cache,
            getKnownManga = known,
            getChapterCounts = counts,
        ).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCache" }.apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val result = function.callSuspend(
            model, 7L, "query", "profile", tastes, RatedMangaVisibility.HIDE_DISLIKED_ONLY,
            true, setOf(SeenMangaKey(7L, "/manga/3")), 20,
        ) as List<PersonalRecommendation>

        assertEquals(listOf(6L, 7L, 7L), result.map { it.manga.id })
        assertEquals(listOf(6.0, 7.0, 9.0), result.map { it.score })
        coVerify(exactly = 1) { repository.getMangaByIds(any()) }
        coVerify(exactly = 0) { repository.getMangaById(any()) }
        coVerify(exactly = 1) { known.await(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 7L)) }
        coVerify(exactly = 1) { counts.await(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 7L)) }
    }

    @Test
    fun `inactive cache filters skip their database lookups and invalid caches skip manga resolution`() = runTest {
        val manga = candidate(7L, 1L, "/first").manga
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(any<Collection<Long>>()) } returns listOf(manga)
        val known = mockk<GetKnownRecommendationMangaIds>()
        val counts = mockk<GetChapterCountsByMangaIds>()
        val cache = mockk<GetRecommendationCache>()
        val entry = RecommendationCacheEntry(
            cacheKey = "test", sourceId = 7L, profileFingerprint = "profile", queryKey = "query",
            resultMangaIds = "1", resultScores = "5", resultReasons = null,
            createdAt = 0L, expiresAt = 2_000L,
        )
        coEvery { cache.await(any()) } returnsMany listOf(entry, entry.copy(expiresAt = 999L), entry)
        val model = buildHarness(
            getManga = getManga,
            getCache = cache,
            getKnownManga = known,
            getChapterCounts = counts,
        ).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCache" }.apply { isAccessible = true }
        suspend fun load(fingerprint: String) = function.callSuspend(
            model, 7L, "query", fingerprint, emptyMap<MangaTasteKey, MangaTaste>(),
            RatedMangaVisibility.SHOW_ALL_RATED, false, emptySet<SeenMangaKey>(), 0,
        )

        assertEquals(listOf(PersonalRecommendation(manga, 5.0, emptyList())), load("profile"))
        assertEquals(null, load("profile"))
        assertEquals(null, load("changed-profile"))
        coVerify(exactly = 1) { getManga.await(any<Collection<Long>>()) }
        coVerify(exactly = 0) { known.await(any()) }
        coVerify(exactly = 0) { counts.await(any()) }
    }

    @Test
    fun `cache filter lookup failures keep candidates but cancellation propagates`() = runTest {
        val manga = candidate(7L, 1L, "/first").manga
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(any<Collection<Long>>()) } returns listOf(manga)
        val known = mockk<GetKnownRecommendationMangaIds>()
        val counts = mockk<GetChapterCountsByMangaIds>()
        val cache = mockk<GetRecommendationCache>()
        coEvery { cache.await(any()) } returns RecommendationCacheEntry(
            cacheKey = "test", sourceId = 7L, profileFingerprint = "profile", queryKey = "query",
            resultMangaIds = "1", resultScores = "5", resultReasons = null,
            createdAt = 0L, expiresAt = 2_000L,
        )
        val model = buildHarness(
            getManga = getManga,
            getCache = cache,
            getKnownManga = known,
            getChapterCounts = counts,
        ).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCache" }.apply { isAccessible = true }
        suspend fun load() = try {
            function.callSuspend(
                model, 7L, "query", "profile", emptyMap<MangaTasteKey, MangaTaste>(),
                RatedMangaVisibility.SHOW_ALL_RATED, true, emptySet<SeenMangaKey>(), 20,
            )
        } catch (error: InvocationTargetException) {
            throw (error.cause ?: error)
        }

        coEvery { known.await(any()) } throws IllegalStateException("Known lookup unavailable")
        coEvery { counts.await(any()) } throws IllegalStateException("Chapter lookup unavailable")
        assertEquals(listOf(PersonalRecommendation(manga, 5.0, emptyList())), load())

        coEvery { known.await(any()) } throws CancellationException("Known lookup cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { load() } }
        coVerify(exactly = 1) { counts.await(any()) }

        coEvery { known.await(any()) } returns emptySet()
        coEvery { counts.await(any()) } throws CancellationException("Chapter lookup cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { load() } }
        coVerify(exactly = 2) { counts.await(any()) }

        coEvery { known.await(any()) } throws IllegalStateException("Known lookup unavailable")
        coEvery { counts.await(any()) } returns mapOf(1L to 3L)
        assertEquals(emptyList<PersonalRecommendation>(), load())

        coEvery { known.await(any()) } returns setOf(1L)
        coEvery { counts.await(any()) } throws IllegalStateException("Chapter lookup unavailable")
        assertEquals(emptyList<PersonalRecommendation>(), load())
        coVerify(exactly = 4) { counts.await(any()) }
    }

    @Test
    fun `cache restoration respects every rated visibility choice`() = runTest {
        val manga = (1L..4L).map { candidate(7L, it, "/rated/$it").manga }
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(any<Collection<Long>>()) } returns manga
        val cache = mockk<GetRecommendationCache>()
        coEvery { cache.await(any()) } returns RecommendationCacheEntry(
            cacheKey = "test", sourceId = 7L, profileFingerprint = "profile", queryKey = "query",
            resultMangaIds = "1,2,3,4", resultScores = "1,2,3,4", resultReasons = null,
            createdAt = 0L, expiresAt = 2_000L,
        )
        val tastes = manga.take(3).zip(listOf(MangaRating.LIKE, MangaRating.LOVE, MangaRating.DISLIKE))
            .associate { (item, rating) ->
                MangaTasteKey(item.source, item.url) to MangaTaste(
                    item.id,
                    item.source,
                    item.url,
                    item.title,
                    rating.value,
                    0L,
                    0L,
                )
            }
        val model = buildHarness(getManga = getManga, getCache = cache).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCache" }.apply { isAccessible = true }
        val expected = mapOf(
            RatedMangaVisibility.SHOW_ALL_RATED to listOf(1L, 2L, 3L, 4L),
            RatedMangaVisibility.HIDE_DISLIKED_ONLY to listOf(1L, 2L, 4L),
            RatedMangaVisibility.HIDE_ALL_RATED to listOf(4L),
        )
        for ((visibility, ids) in expected) {
            @Suppress("UNCHECKED_CAST")
            val result = function.callSuspend(
                model, 7L, "query", "profile", tastes, visibility, false, emptySet<SeenMangaKey>(), 0,
            ) as List<PersonalRecommendation>
            assertEquals(ids, result.map { it.manga.id }, visibility.name)
            assertEquals(ids.map { it.toDouble() }, result.map { it.score }, visibility.name)
        }
    }

    @Test
    fun `new refresh cancels suspended profile loading before replacement completes`() = runTest {
        val profile = mockk<GetTasteProfile>()
        var attempts = 0
        var cancelled = 0
        val events = mutableListOf<String>()
        coEvery { profile.await() } coAnswers {
            attempts++
            if (attempts == 1) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                    events += "old profile cancelled"
                }
            }
            events += "replacement profile returned"
            TasteProfile.EMPTY
        }
        val model = buildHarness(getTasteProfile = profile).model
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals(0, cancelled)
        assertTrue(model.state.value.isLoading)
        val firstGeneration = model.state.value.resultGeneration

        model.refresh()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(1, cancelled)
        assertEquals(listOf("old profile cancelled", "replacement profile returned"), events)
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.profileIsEmpty)
        assertTrue(model.state.value.resultGeneration > firstGeneration)
        coVerify(exactly = 2) { profile.await() }
    }

    @Test
    @OptIn(InternalVoyagerApi::class)
    fun `disposing the registered screen cancels suspended recommendation loading`() = runTest {
        val profile = mockk<GetTasteProfile>()
        var started = false
        var cancelled = false
        coEvery { profile.await() } coAnswers {
            started = true
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val holder = "recommendation-disposal-test"
        val model = ScreenModelStore.getOrPut(holder, null) {
            buildHarness(getTasteProfile = profile).model
        }
        val job = model.screenModelScope.coroutineContext[Job]!!
        try {
            model.refresh()
            advanceUntilIdle()
            assertTrue(started)
            assertFalse(cancelled)
            assertTrue(job.isActive)

            ScreenModelStore.onDisposeNavigator(holder)
            advanceUntilIdle()
            assertTrue(cancelled)
            assertFalse(job.isActive)
            coVerify(exactly = 1) { profile.await() }
        } finally {
            ScreenModelStore.onDisposeNavigator(holder)
        }
    }

    @Test
    @OptIn(InternalVoyagerApi::class)
    @Suppress("DEPRECATION")
    fun `registered model disposal closes its additional page HTTP request without retry writes`() = runBlocking {
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                }
            })
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val holder = "recommendation-http-disposal-test"
        val progress = mockk<UpsertRecommendationDiscoveryProgress>(relaxed = true)
        val model = ScreenModelStore.getOrPut(holder, null) {
            buildHarness(searchDispatcher = Dispatchers.IO, upsertDiscovery = progress).model
        }
        try {
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 3_000
                val source = object : HttpSource() {
                    override val id = 992L
                    override val name = "Local HTTP fixture"
                    override val lang = "en"
                    override val supportsLatest = false
                    override val baseUrl = "http://localhost:${server.localPort}"
                    override val client = client
                    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
                        Request.Builder().url("$baseUrl/search?page=$page").build()
                    override fun searchMangaParse(response: Response): MangasPage =
                        response.use { MangasPage(emptyList(), false) }
                }
                val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
                    .single { it.name == "discoverAdditionalPage" }.apply { isAccessible = true }
                val pageOne = mockk<tachiyomi.domain.taste.model.RecommendationDiscoveryProgress> {
                    every { page } returns 1
                    every { status } returns tachiyomi.domain.taste.model.RecommendationDiscoveryProgress.STATUS_SUCCESS
                }
                val job = model.screenModelScope.launch(Dispatchers.IO) {
                    try {
                        function.callSuspend(
                            model, source, listOf(pageOne), "query", listOf("action"), "profile", null,
                            GenreFilterMapper.SearchParams(FilterList(), "action"), emptyMap<MangaTasteKey, MangaTaste>(),
                            RatedMangaVisibility.SHOW_ALL_RATED, emptySet<SeenMangaKey>(), TasteProfile.EMPTY,
                            emptyMap<String, String>(), 0, false, 20,
                        )
                    } catch (error: InvocationTargetException) {
                        throw (error.cause ?: error)
                    }
                }
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        val input = peer.getInputStream().bufferedReader()
                        assertTrue(input.readLine().startsWith("GET /search?page=2 "))
                        while (input.readLine()?.isNotEmpty() == true) { }
                        ScreenModelStore.onDisposeNavigator(holder)
                        val closed = try {
                            input.read() == -1
                        } catch (_: SocketException) {
                            true
                        }
                        assertTrue(closed, "Disposal left the source connection open")
                        assertTrue(activeCall.get().isCanceled())
                    }
                    withTimeout(3_000) { job.join() }
                    assertTrue(job.isCancelled)
                    coVerify(exactly = 0) { progress.await(any()) }
                } finally {
                    withTimeout(3_000) { job.cancelAndJoin() }
                }
            }
        } finally {
            ScreenModelStore.onDisposeNavigator(holder)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    @OptIn(InternalVoyagerApi::class)
    fun `a cancelled profile result cannot overwrite a newer offline refresh`() = runTest {
        for (lateProfile in listOf(TasteProfile.EMPTY, TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0)))) {
            val profile = mockk<GetTasteProfile>()
            val aliases = mockk<GetTagAliases>(relaxed = true)
            var delayed: Continuation<TasteProfile>? = null
            var oldResultReturned = false
            var online = true
            coEvery { profile.await() } coAnswers {
                val result = suspendCoroutine<TasteProfile> { delayed = it }
                oldResultReturned = true
                result
            }
            val holder = "recommendation-late-profile-test"
            val model = ScreenModelStore.getOrPut(holder, null) {
                buildHarness(getTasteProfile = profile, getTagAliases = aliases, isOnline = { online }).model
            }
            try {
                model.refresh()
                advanceUntilIdle()
                assertTrue(delayed != null)
                assertTrue(model.state.value.isLoading)
                val oldGeneration = model.state.value.resultGeneration

                online = false
                model.refresh()
                advanceUntilIdle()
                val newerState = model.state.value
                assertTrue(newerState.isOffline)
                assertFalse(newerState.isLoading)
                assertFalse(newerState.profileIsEmpty)
                assertTrue(newerState.resultGeneration > oldGeneration)
                assertFalse(oldResultReturned)

                val continuation = delayed!!
                delayed = null
                continuation.resume(lateProfile)
                advanceUntilIdle()
                assertTrue(oldResultReturned, "Old request must actually return after cancellation")
                assertEquals(newerState, model.state.value)
                coVerify(exactly = 1) { profile.await() }
                coVerify(exactly = 0) { aliases.awaitAll() }
            } finally {
                delayed?.resume(TasteProfile.EMPTY)
                ScreenModelStore.onDisposeNavigator(holder)
            }
        }
    }

    private enum class SetupRead { ALIASES, DISABLED_SOURCES, RATINGS }

    @Test
    @OptIn(InternalVoyagerApi::class)
    fun `late setup results stop without consulting sources or clearing newer picks`() = runTest {
        val unwantedWork = mutableListOf<String>()
        for (stage in SetupRead.entries) {
            val profile = mockk<GetTasteProfile>()
            val aliases = mockk<GetTagAliases>()
            val disabled = mockk<GetDisabledRecommendationSources>()
            val tastes = mockk<GetMangaTaste>()
            val sources = mockk<SourceManager>()
            var delayed: Continuation<Unit>? = null
            var oldResultReturned = false
            var online = true
            var sourceReads = 0
            suspend fun pause() {
                suspendCoroutine<Unit> { delayed = it }
                oldResultReturned = true
            }
            coEvery { profile.await() } returns TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0))
            coEvery { aliases.awaitAll() } coAnswers {
                if (stage == SetupRead.ALIASES) pause()
                emptyList()
            }
            coEvery { disabled.await() } coAnswers {
                if (stage == SetupRead.DISABLED_SOURCES) pause()
                emptyList()
            }
            coEvery { tastes.awaitAll() } coAnswers {
                if (stage == SetupRead.RATINGS) pause()
                emptyList()
            }
            every { sources.getVisibleSources() } answers {
                sourceReads++
                emptyList()
            }
            val holder = "recommendation-late-setup-test"
            val model = ScreenModelStore.getOrPut(holder, null) {
                buildHarness(
                    getTasteProfile = profile,
                    getTagAliases = aliases,
                    getDisabledSources = disabled,
                    getMangaTaste = tastes,
                    sourceManager = sources,
                    isOnline = { online },
                ).model
            }
            try {
                model.refresh()
                advanceUntilIdle()
                assertTrue(delayed != null, "$stage must reach the delayed read")
                assertTrue(model.state.value.isLoading)
                val oldGeneration = model.state.value.resultGeneration
                online = false
                model.refresh()
                advanceUntilIdle()
                val newerState = model.state.value
                assertTrue(newerState.isOffline)
                assertFalse(newerState.isLoading)
                assertTrue(newerState.resultGeneration > oldGeneration)
                val readsBeforeReturn = sourceReads
                val accumulator = BrowsePersonalRecommendationsScreenModel::class.java
                    .getDeclaredField("combinedAccumulator").apply { isAccessible = true }
                    .get(model) as CombinedPicksAccumulator
                accumulator.add(listOf(candidate(7L, 901L, "/checkpoint")), 7L)
                val newerPicks = accumulator.rank(emptySet(), 50)
                val continuation = delayed!!
                delayed = null
                continuation.resume(Unit)
                advanceUntilIdle()
                assertTrue(oldResultReturned)
                assertEquals(newerState, model.state.value)
                if (sourceReads != readsBeforeReturn) unwantedWork += "$stage consulted sources after cancellation"
                if (accumulator.rank(emptySet(), 50) != newerPicks) unwantedWork += "$stage cleared newer picks"
            } finally {
                delayed?.resume(Unit)
                ScreenModelStore.onDisposeNavigator(holder)
            }
        }
        assertEquals(emptyList<String>(), unwantedWork)
    }

    @Test
    fun `real additional page source cancellation propagates without writing retry progress`() = runTest {
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 991L
        coEvery { source.getSearchManga(any(), any(), any()) } throws CancellationException("Source cancelled")
        val progress = mockk<UpsertRecommendationDiscoveryProgress>(relaxed = true)
        val model = buildHarness(upsertDiscovery = progress).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "discoverAdditionalPage" }.apply { isAccessible = true }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                try {
                    function.callSuspend(
                        model, source,
                        listOf(
                            mockk<tachiyomi.domain.taste.model.RecommendationDiscoveryProgress> {
                                every { page } returns 1
                                every { status } returns tachiyomi.domain.taste.model.RecommendationDiscoveryProgress.STATUS_SUCCESS
                            },
                        ),
                        "query", listOf("action"), "profile", null,
                        GenreFilterMapper.SearchParams(FilterList(), "action"), emptyMap<MangaTasteKey, MangaTaste>(),
                        RatedMangaVisibility.SHOW_ALL_RATED, emptySet<SeenMangaKey>(), TasteProfile.EMPTY,
                        emptyMap<String, String>(), 0, false, 20,
                    )
                } catch (error: InvocationTargetException) {
                    throw (error.cause ?: error)
                }
            }
        }
        coVerify(exactly = 1) { source.getSearchManga(any(), "action", any()) }
        coVerify(exactly = 0) { progress.await(any()) }
    }

    @Test
    fun `cancellation after additional page filtering cannot commit stale progress`() = runTest {
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 993L
        val networkManga = SManga.create().apply {
            url = "/cancel-before-progress"
            title = "Cancellation boundary"
            genre = "Action"
            initialized = true
        }
        coEvery { source.getSearchManga(2, any(), any()) } returns MangasPage(listOf(networkManga), false)

        val knownManga = mockk<GetKnownRecommendationMangaIds>()
        coEvery { knownManga.await(any()) } coAnswers {
            currentCoroutineContext()[Job]!!.cancel(CancellationException("Refresh superseded"))
            emptySet()
        }
        val progress = mockk<UpsertRecommendationDiscoveryProgress>(relaxed = true)
        val localize = mockk<NetworkToLocalManga>()
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers { firstArg<List<Manga>>() }
        val model = buildHarness(
            getKnownManga = knownManga,
            networkToLocalManga = localize,
            upsertDiscovery = progress,
        ).model
        val function = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "discoverAdditionalPage" }.apply { isAccessible = true }

        try {
            withContext(Job()) {
                function.callSuspend(
                    model,
                    source,
                    listOf(
                        mockk<tachiyomi.domain.taste.model.RecommendationDiscoveryProgress> {
                            every { page } returns 1
                            every { status } returns tachiyomi.domain.taste.model.RecommendationDiscoveryProgress.STATUS_SUCCESS
                        },
                    ),
                    "query",
                    listOf("action"),
                    "profile",
                    null,
                    GenreFilterMapper.SearchParams(FilterList(), "action"),
                    emptyMap<MangaTasteKey, MangaTaste>(),
                    RatedMangaVisibility.SHOW_ALL_RATED,
                    emptySet<SeenMangaKey>(),
                    TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0)),
                    emptyMap<String, String>(),
                    0,
                    true,
                    20,
                )
            }
        } catch (error: InvocationTargetException) {
            if (error.cause !is CancellationException) throw error
        } catch (_: CancellationException) {
            // Expected: the simulated refresh is cancelled immediately before the progress commit.
        }

        coVerify(exactly = 1) { knownManga.await(any()) }
        coVerify(exactly = 0) { progress.await(any()) }
    }

    @Test
    fun `real ScreenModel construction does not eagerly load when autoLoad is disabled`() = runTest {
        val harness = buildHarness()

        assertTrue(harness.model.state.value.isLoading)
        coVerify(exactly = 0) { harness.recordExposure.await(any(), any(), any()) }
    }

    @Test
    fun `public refresh reaches offline terminal state without Android connectivity`() = runTest {
        val harness = buildHarness(isOnline = { false })

        harness.model.refresh()
        advanceUntilIdle()

        assertFalse(harness.model.state.value.isLoading)
        assertTrue(harness.model.state.value.isOffline)
    }

    @Test
    fun `applying focus starts a fresh recommendation refresh`() = runTest {
        val harness = buildHarness(isOnline = { false })

        harness.model.setActiveFocus(
            RecommendationFocusPolicy.FocusCriteria(include = setOf("comedy")),
        )
        advanceUntilIdle()

        assertFalse(harness.model.state.value.isLoading)
        assertTrue(harness.model.state.value.isOffline)
        assertEquals(setOf("comedy"), harness.model.activeFocusCriteria.value.include)
    }

    @Test
    fun `focused refresh fills matching rows within the forty source limit and preserves broader results`() = runTest {
        val searched = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val sources = (1L..41L).map { index ->
            mockk<Source>(relaxed = true).also { source ->
                every { source.id } returns index
                every { source.name } returns "Focus Source $index"
                every { source.lang } returns "en"
                every { source.supportsLatest } returns false
                every { source.getFilterList() } returns FilterList()
                val manga = SManga.create().apply {
                    url = "/focus/$index"
                    title = "Focus candidate $index"
                    genre = if (index <= 20L) "Action" else "Action, Romance"
                    status = SManga.ONGOING
                    initialized = true
                }
                coEvery { source.getSearchManga(1, any(), any()) } coAnswers {
                    searched += index
                    MangasPage(listOf(manga), false)
                }
            }
        }
        val profile = mockk<GetTasteProfile>()
        coEvery { profile.await() } returns TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0))
        val aliases = mockk<GetTagAliases>(relaxed = true)
        coEvery { aliases.awaitAll() } returns emptyList()
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        coEvery { tastes.awaitAll() } returns emptyList()
        val manager = mockk<SourceManager>(relaxed = true)
        every { manager.getVisibleSources() } returns sources
        val localize = mockk<NetworkToLocalManga>()
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers { firstArg<List<Manga>>() }
        val harness = buildHarness(
            getTasteProfile = profile,
            getTagAliases = aliases,
            getMangaTaste = tastes,
            sourceManager = manager,
            networkToLocalManga = localize,
        )
        val focus = RecommendationFocusPolicy.FocusCriteria(include = setOf("romance"))
        harness.model.setActiveFocus(focus)
        advanceUntilIdle()
        assertEquals((1L..40L).toSet(), searched.toSet())
        assertEquals(40, searched.distinct().size)
        assertFalse(harness.model.state.value.items.keys.any { it.id == 41L })
        val raw = harness.model.state.value.items[sources.first()] as PersonalRecommendationResult.Success
        assertEquals(1, raw.result.size)
        val presentation = RecommendationFocusPresentationPolicy.apply(raw, focus, harness.model.state.value.focusAliasMap)
        assertTrue((presentation.filtered as PersonalRecommendationResult.Success).isEmpty)
        assertFalse((presentation.broader as PersonalRecommendationResult.Success).isEmpty)
    }

    // KMK --> v0.8.24 B4: a candidate remembered from an earlier refresh must be re-checked against the
    // CURRENT known/rated set on a live Refresh, not only against the ids of this refresh's fresh page one.
    private fun hideKnownRememberedSource(sourceId: Long): Source =
        mockk<Source>(relaxed = true).also { source ->
            every { source.id } returns sourceId
            every { source.name } returns "Remembered Source"
            every { source.lang } returns "en"
            every { source.supportsLatest } returns false
            every { source.getFilterList() } returns FilterList()
            val fresh = SManga.create().apply {
                url = "/fresh/a"
                title = "Fresh A"
                genre = "Action"
                status = SManga.ONGOING
                initialized = true
            }
            coEvery { source.getSearchManga(1, any(), any()) } returns MangasPage(listOf(fresh), false)
        }

    private suspend fun kotlinx.coroutines.test.TestScope.refreshWithRememberedLikedCandidate(
        hideKnown: Boolean,
        knownIds: Set<Long>,
    ): List<Manga> {
        val sourceId = 501L
        val source = hideKnownRememberedSource(sourceId)
        val profile = mockk<GetTasteProfile>()
        coEvery { profile.await() } returns TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0))
        val aliases = mockk<GetTagAliases>(relaxed = true)
        coEvery { aliases.awaitAll() } returns emptyList()
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        coEvery { tastes.awaitAll() } returns emptyList()
        val manager = mockk<SourceManager>(relaxed = true)
        every { manager.getVisibleSources() } returns listOf(source)
        val localize = mockk<NetworkToLocalManga>()
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers {
            firstArg<List<Manga>>().mapIndexed { index, manga -> manga.copy(id = 101L + index) }
        }
        val remembered = Manga.create().copy(
            id = 202L,
            source = sourceId,
            url = "/remembered/b",
            ogTitle = "Remembered B",
            ogGenre = listOf("Action"),
        )
        val getManga = mockk<GetManga>(relaxed = true)
        coEvery { getManga.await(202L) } returns remembered
        coEvery { getManga.await("/remembered/b", sourceId) } returns remembered
        val memory = mockk<GetRecommendationCandidateMemory>(relaxed = true)
        coEvery { memory.awaitBySourceQuery(sourceId, any()) } returns listOf(
            tachiyomi.domain.taste.model.RecommendationCandidateMemory(
                sourceId = sourceId,
                url = "/remembered/b",
                mangaId = 202L,
                title = "Remembered B",
                thumbnailUrl = null,
                normalizedTitle = "remembered b",
                lastScore = 5.0,
                matchedGroupsJson = "action",
                resultReasonsJson = null,
                querySignature = "any",
                queryTagsJson = "action",
                queryStrategy = null,
                page = 1,
                discoveredAt = 1L,
                lastScoredAt = 1L,
                lastSeenAt = 1L,
                profileFingerprint = null,
                filteredReason = null,
            ),
        )
        // Mirrors the real query: only ids that are actually known come back, whatever ids were asked about.
        val known = mockk<GetKnownRecommendationMangaIds>()
        coEvery { known.await(any()) } answers { firstArg<Collection<Long>>().filter { it in knownIds }.toSet() }
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.recommendationHideKnownManga().set(hideKnown)
        preferences.recommendationRatedMangaVisibility().set(RatedMangaVisibility.SHOW_ALL_RATED)
        val harness = buildHarness(
            getTasteProfile = profile,
            getTagAliases = aliases,
            getMangaTaste = tastes,
            sourceManager = manager,
            networkToLocalManga = localize,
            getManga = getManga,
            getKnownManga = known,
            getMemory = memory,
            preferences = preferences,
        )

        harness.model.refresh()
        advanceUntilIdle()

        val result = harness.model.state.value.items[source] as PersonalRecommendationResult.Success
        return result.result.map { it.manga }
    }

    @Test
    fun `live refresh hides a remembered candidate the user has since rated when hide known is on`() = runTest {
        val shown = refreshWithRememberedLikedCandidate(hideKnown = true, knownIds = setOf(202L))

        assertTrue(shown.any { it.id == 101L }, "the fresh unknown candidate must still be shown")
        assertFalse(shown.any { it.id == 202L }, "a remembered candidate that is now known must be hidden")
    }

    // KMK --> v0.8.24 B4: controlled chapter-floor comparison. The SAME fixed title (id 101, 42 locally known
    // chapters, like the Voodoo Slam/Hijala observation) goes through the real refresh() once per floor, so
    // nothing but the floor changes between runs. No live source pool is involved.
    private suspend fun kotlinx.coroutines.test.TestScope.refreshWithChapterFloor(
        floor: Int,
        chapterCounts: Map<Long, Long>,
    ): List<Manga> {
        val sourceId = 502L
        val source = hideKnownRememberedSource(sourceId)
        val profile = mockk<GetTasteProfile>()
        coEvery { profile.await() } returns TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0))
        val aliases = mockk<GetTagAliases>(relaxed = true)
        coEvery { aliases.awaitAll() } returns emptyList()
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        coEvery { tastes.awaitAll() } returns emptyList()
        val manager = mockk<SourceManager>(relaxed = true)
        every { manager.getVisibleSources() } returns listOf(source)
        val localize = mockk<NetworkToLocalManga>()
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers {
            firstArg<List<Manga>>().mapIndexed { index, manga -> manga.copy(id = 101L + index) }
        }
        // Mirrors the real query: ids without chapter rows are omitted from the result.
        val counts = mockk<GetChapterCountsByMangaIds>()
        coEvery { counts.await(any()) } answers {
            firstArg<Collection<Long>>().filter { it in chapterCounts }.associateWith { chapterCounts.getValue(it) }
        }
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.recommendationMinChapterCount().set(floor)
        val harness = buildHarness(
            getTasteProfile = profile,
            getTagAliases = aliases,
            getMangaTaste = tastes,
            sourceManager = manager,
            networkToLocalManga = localize,
            getChapterCounts = counts,
            preferences = preferences,
        )

        harness.model.refresh()
        advanceUntilIdle()

        val result = harness.model.state.value.items[source] as PersonalRecommendationResult.Success
        return result.result.map { it.manga }
    }

    @Test
    fun `chapter floor shows the same title below and at its verified count and hides it above`() = runTest {
        val verifiedCounts = mapOf(101L to 42L)

        assertTrue(refreshWithChapterFloor(40, verifiedCounts).any { it.id == 101L }, "floor 40 is below 42 chapters")
        assertTrue(refreshWithChapterFloor(42, verifiedCounts).any { it.id == 101L }, "a count at the floor stays visible")
        assertFalse(refreshWithChapterFloor(43, verifiedCounts).any { it.id == 101L }, "floor 43 is above 42 chapters")
        assertTrue(refreshWithChapterFloor(0, verifiedCounts).any { it.id == 101L }, "floor Off never filters")
    }

    @Test
    fun `chapter floor never hides a title whose chapter count is not known locally`() = runTest {
        assertTrue(refreshWithChapterFloor(43, emptyMap()).any { it.id == 101L }, "unknown count fails open")
    }
    // KMK <--

    // KMK --> v0.8.24 recent-reading profile through the real refresh() path. Two fresh candidates: one matches the
    // long-term profile (Action), one matches only what was read recently (Romance).
    private suspend fun kotlinx.coroutines.test.TestScope.refreshWithRecentReading(
        baseProfile: TasteProfile,
        recentWeights: Map<String, Double>?,
    ): Pair<BrowsePersonalRecommendationsScreenModel.State, List<Manga>> {
        val sourceId = 503L
        val source = mockk<Source>(relaxed = true).also {
            every { it.id } returns sourceId
            every { it.name } returns "Recent Source"
            every { it.lang } returns "en"
            every { it.supportsLatest } returns false
            every { it.getFilterList() } returns FilterList()
            fun fresh(path: String, genreText: String) = SManga.create().apply {
                url = path
                title = path
                genre = genreText
                status = SManga.ONGOING
                initialized = true
            }
            coEvery { it.getSearchManga(1, any(), any()) } returns
                MangasPage(listOf(fresh("/action", "Action"), fresh("/romance", "Romance")), false)
        }
        val profile = mockk<GetTasteProfile>()
        coEvery { profile.await() } returns baseProfile
        val aliases = mockk<GetTagAliases>(relaxed = true)
        coEvery { aliases.awaitAll() } returns emptyList()
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        coEvery { tastes.awaitAll() } returns emptyList()
        val manager = mockk<SourceManager>(relaxed = true)
        every { manager.getVisibleSources() } returns listOf(source)
        val localize = mockk<NetworkToLocalManga>()
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers {
            firstArg<List<Manga>>().mapIndexed { index, manga -> manga.copy(id = 101L + index) }
        }
        val recent = recentWeights?.let { weights ->
            mockk<GetRecentReadingTagWeights>().also { interactor ->
                coEvery { interactor.await(any(), any()) } returns weights
            }
        }
        val harness = buildHarness(
            getTasteProfile = profile,
            getTagAliases = aliases,
            getMangaTaste = tastes,
            sourceManager = manager,
            networkToLocalManga = localize,
            getRecentReadingTagWeights = recent,
        )

        harness.model.refresh()
        advanceUntilIdle()

        val state = harness.model.state.value
        val shown = (state.items[source] as? PersonalRecommendationResult.Success)?.result.orEmpty().map { it.manga }
        return state to shown
    }

    private fun longTermActionProfile() = TasteProfile.EMPTY.copy(learnedTagWeights = mapOf("action" to 1.0))

    @Test
    fun `recent reading changes what refresh shows and ranks it above the long-term match`() = runTest {
        val (_, withRecent) = refreshWithRecentReading(longTermActionProfile(), mapOf("romance" to 3.0))
        assertEquals(listOf("/romance", "/action"), withRecent.map { it.url })

        val (_, withoutRecent) = refreshWithRecentReading(longTermActionProfile(), null)
        assertEquals(listOf("/action"), withoutRecent.map { it.url }, "control: no interactor keeps the long-term profile")
    }

    @Test
    fun `recent reading alone is enough taste evidence when there are no ratings or tag preferences`() = runTest {
        val (withRecentState, shown) = refreshWithRecentReading(TasteProfile.EMPTY, mapOf("action" to 2.0))
        assertFalse(withRecentState.profileIsEmpty)
        assertEquals(listOf("/action"), shown.map { it.url })

        val (controlState, _) = refreshWithRecentReading(TasteProfile.EMPTY, null)
        assertTrue(controlState.profileIsEmpty, "control: with no recent reading the empty-profile state still shows")
    }
    // KMK <--

    // KMK --> v0.8.24 For You scheduling and data saving, through the real refresh() path.
    private class QueueFixture(
        val sources: List<Source>,
        val started: java.util.concurrent.ConcurrentHashMap<Long, Long>,
        val pagesRequested: java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Int>>,
    )

    private fun kotlinx.coroutines.test.TestScope.queueFixture(
        count: Int,
        slowSourceId: Long? = null,
        slowMillis: Long = 0L,
        slowSourceIds: Set<Long> = setOfNotNull(slowSourceId),
        genreOf: (Long) -> String = { "Action" },
        searchFindsNothing: Set<Long> = emptySet(),
    ): QueueFixture {
        val started = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val pages = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Int>>()
        val sources = (1L..count.toLong()).map { index ->
            mockk<Source>(relaxed = true).also { source ->
                every { source.id } returns index
                every { source.name } returns "Queue Source $index"
                every { source.lang } returns "en"
                every { source.supportsLatest } returns false
                every { source.getFilterList() } returns FilterList()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    val page = firstArg<Int>()
                    pages += index to page
                    if (index in searchFindsNothing) return@coAnswers MangasPage(emptyList(), false)
                    if (page == 1) {
                        started[index] = testScheduler.currentTime
                        // Every search takes some (virtual) time, as a network call does, so completions interleave with
                        // starts instead of the whole queue finishing before the first result is processed.
                        kotlinx.coroutines.delay(if (index in slowSourceIds) slowMillis else 100L)
                    }
                    MangasPage(
                        listOf(
                            SManga.create().apply {
                                url = "/q$index/p$page"
                                title = "Queue $index page $page"
                                genre = genreOf(index)
                                status = SManga.ONGOING
                                initialized = true
                            },
                        ),
                        false,
                    )
                }
            }
        }
        sources.filter { it.id in searchFindsNothing }.forEach { source ->
            coEvery { source.getPopularManga(any()) } returns MangasPage(
                listOf(
                    SManga.create().apply {
                        url = "/popular${source.id}"
                        title = "Popular ${source.id}"
                        genre = genreOf(source.id)
                        status = SManga.ONGOING
                        initialized = true
                    },
                ),
                false,
            )
        }
        return QueueFixture(sources, started, pages)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.refreshQueue(
        fixture: QueueFixture,
        dataSaving: String? = null,
        constrained: Boolean = false,
        fits: List<tachiyomi.domain.taste.model.SourceRecommendationFit>? = null,
        autoCheck: Boolean = false,
        applyResults: Boolean = true,
        looserMatches: Boolean? = null,
    ): BrowsePersonalRecommendationsScreenModel.State {
        val profile = mockk<GetTasteProfile>()
        coEvery { profile.await() } returns longTermActionProfile()
        val aliases = mockk<GetTagAliases>(relaxed = true)
        coEvery { aliases.awaitAll() } returns emptyList()
        val tastes = mockk<GetMangaTaste>(relaxed = true)
        coEvery { tastes.awaitAll() } returns emptyList()
        val manager = mockk<SourceManager>(relaxed = true)
        every { manager.getVisibleSources() } returns fixture.sources
        val localize = mockk<NetworkToLocalManga>()
        var nextId = 1_000L
        coEvery { localize.invoke(any<List<Manga>>(), any()) } answers {
            firstArg<List<Manga>>().map { manga -> manga.copy(id = nextId++) }
        }
        val preferences = SourcePreferences(FakePreferenceStore())
        dataSaving?.let { preferences.recommendationDataSaving().set(it) }
        looserMatches?.let { preferences.recommendationLooserMatches().set(it) }
        preferences.recommendationAutoRecheckEnabled().set(autoCheck)
        preferences.recommendationAutoRecheckApplyToForYou().set(applyResults)
        // For You's own history: every fixture source tried several times recently with nothing shown, so only the check
        // result decides whether a source is skipped in these fixtures.
        if (fits != null) {
            preferences.recommendationSourceFitStats().set(
                SourceFitStatsStore.serialize(
                    fixture.sources.map {
                        SourceFitStats(
                            sourceId = it.id, runCount = 5, shownCount = 0, noMatchCount = 5, filteredOutCount = 0, errorCount = 0,
                            hiddenByDuplicateCount = 0, totalVisibleCandidates = 0, lastSuccessAt = 0L, lastErrorAt = 0L,
                            updatedAt = 0L, recentRunCount = 5, recentShownCount = 0,
                        )
                    },
                ),
            )
        }
        val fitReader = fits?.let { list ->
            mockk<tachiyomi.domain.taste.interactor.GetSourceRecommendationFit>().also { coEvery { it.awaitAll() } returns list }
        }
        val harness = buildHarness(
            getTasteProfile = profile,
            getTagAliases = aliases,
            getMangaTaste = tastes,
            sourceManager = manager,
            networkToLocalManga = localize,
            preferences = preferences,
            isNetworkConstrained = { constrained },
            getSourceRecommendationFit = fitReader,
        )
        harness.model.refresh()
        advanceUntilIdle()
        return harness.model.state.value
    }

    // KMK --> v0.8.24: automatic check results acting on For You
    private fun noMatchesFit(sourceId: Long) = tachiyomi.domain.taste.model.SourceRecommendationFit(
        fitKey = "fit$sourceId", evaluationKey = "eval$sourceId", sourceId = sourceId, extensionPkgName = "pkg", signatureHash = "sig",
        extensionName = "ext", sourceName = "Queue Source $sourceId", lang = "en", evaluatedAt = 0, queryCount = 3,
        querySuccessCount = 3, rawResultCount = 0, visibleCandidateCount = 0, filteredOutCount = 0, blockedTagCandidateCount = 0,
        matchedGroupCount = 0, topPicksContribution = 0, noMatchesCount = 3, errorCount = 0, avgCandidateScore = 0.0,
        recommendationQualityScore = 0.0, verdict = tachiyomi.domain.taste.model.RecommendationQualityVerdict.NO_MATCHES,
        reasonsJson = "[]", errorMessage = null,
    )

    @Test
    fun `with the automatic check on, For You skips a source its check found unusable`() = runTest {
        val fixture = queueFixture(count = 5)
        refreshQueue(fixture, fits = listOf(noMatchesFit(3L)), autoCheck = true)
        assertEquals(setOf(1L, 2L, 4L, 5L), fixture.started.keys, "source 3 is skipped, every other source is searched")
    }

    @Test
    fun `check results do nothing while the automatic check or Use results is off`() = runTest {
        val checkOff = queueFixture(count = 5)
        refreshQueue(checkOff, fits = listOf(noMatchesFit(3L)), autoCheck = false)
        assertEquals((1L..5L).toSet(), checkOff.started.keys, "automatic check off: nothing skipped")

        val applyOff = queueFixture(count = 5)
        refreshQueue(applyOff, fits = listOf(noMatchesFit(3L)), autoCheck = true, applyResults = false)
        assertEquals((1L..5L).toSet(), applyOff.started.keys, "Use results on For You off: nothing skipped")
    }

    @Test
    fun `skipping never empties For You`() = runTest {
        val fixture = queueFixture(count = 2)
        refreshQueue(fixture, fits = listOf(noMatchesFit(1L), noMatchesFit(2L)), autoCheck = true)
        assertEquals(setOf(1L, 2L), fixture.started.keys, "when every source would be skipped, none is")
    }
    // KMK <--

    @Test
    fun `a slow source does not delay the sources queued behind it`() = runTest {
        val slowMillis = 10_000L
        val fixture = queueFixture(count = 10, slowSourceId = 1L, slowMillis = slowMillis)

        refreshQueue(fixture)

        assertEquals((1L..10L).toSet(), fixture.started.keys, "every source is searched")
        (6L..10L).forEach { id ->
            assertTrue(
                fixture.started.getValue(id) < slowMillis,
                "source $id must start while the slow source is still running, but started at ${fixture.started[id]}ms",
            )
        }
    }

    @Test
    fun `the refresh stops starting sources once enough rows are useful`() = runTest {
        val fixture = queueFixture(count = 40)

        refreshQueue(fixture)

        val searched = fixture.started.keys.size
        assertTrue(searched >= 20, "at least the row limit is searched, was $searched")
        assertTrue(searched < 40, "the rest is not searched once the rows are full, was $searched")
    }

    // KMK v0.8.24: a source whose manga all miss the taste match shows a few looser matches in its own row
    @Test
    fun `a source whose results all miss the taste match shows looser matches outside Top Picks`() = runTest {
        // source 2 only has Romance (the profile likes Action), so its results all miss the taste match
        val fixture = queueFixture(count = 2, genreOf = { if (it == 2L) "Romance" else "Action" })

        val state = refreshQueue(fixture)

        val looserRow = state.items.entries.single { it.key.id == 2L }.value as PersonalRecommendationResult.Success
        assertTrue(looserRow.isLooser, "source 2 shows looser matches")
        assertTrue(looserRow.result.isNotEmpty())
        val strictRow = state.items.entries.single { it.key.id == 1L }.value as PersonalRecommendationResult.Success
        assertFalse(strictRow.isLooser)
        assertEquals(RecommendationSourceStatus.FilteredOut, state.sourceStatuses[2L]?.status, "still counted as filtered out")
        val topPicks = (state.combinedResult as? PersonalRecommendationResult.Success)?.result.orEmpty()
        assertTrue(topPicks.isNotEmpty() && topPicks.none { it.manga.source == 2L || it.looser }, "Top Picks stays strict")
    }

    @Test
    fun `with looser matches off a source whose results miss the taste match stays empty`() = runTest {
        val fixture = queueFixture(count = 2, genreOf = { if (it == 2L) "Romance" else "Action" })

        val state = refreshQueue(fixture, looserMatches = false)

        val row = state.items.entries.single { it.key.id == 2L }.value as PersonalRecommendationResult.Success
        assertTrue(row.isEmpty, "off restores the previous behavior")
    }

    // KMK v0.8.24: one stuck source no longer holds the whole refresh open
    @Test
    fun `a source that does not finish within the deadline ends as an error and the refresh completes`() = runTest {
        val fixture = queueFixture(count = 3, slowSourceIds = setOf(2L), slowMillis = 600_000L)

        val state = refreshQueue(fixture)

        val stuck = state.items.entries.single { it.key.id == 2L }.value
        assertTrue(stuck is PersonalRecommendationResult.Error && stuck.throwable is ForYouSourceTimeoutException, "was $stuck")
        assertEquals(RecommendationSourceStatus.Error, state.sourceStatuses[2L]?.status)
        assertTrue(state.items.entries.filter { it.key.id != 2L }.all { it.value is PersonalRecommendationResult.Success })
        assertFalse(state.isLoading, "the refresh finished")
        assertEquals(state.total, state.progress)
    }

    // KMK v0.8.24: "Check source" on a failing row tells a source that only fails in For You from a broken one
    @Test
    fun `check source reports works when its Popular page loads and broken when it fails`() = runTest {
        val working = mockk<Source>(relaxed = true).also {
            every { it.id } returns -9_101L
            coEvery { it.getPopularManga(1) } returns MangasPage(emptyList(), false)
        }
        val broken = mockk<Source>(relaxed = true).also {
            // a failure is remembered process-wide (SourceRuntimeFailureRegistry): ids no other test uses
            every { it.id } returns -9_102L
            coEvery { it.getPopularManga(1) } throws java.io.IOException("site gone")
        }
        val model = buildHarness().model

        model.checkSource(working)
        model.checkSource(broken)
        advanceUntilIdle()

        assertEquals(ForYouSourceFailurePolicy.Check.WORKS, model.state.value.sourceChecks[-9_101L])
        assertEquals(ForYouSourceFailurePolicy.Check.BROKEN, model.state.value.sourceChecks[-9_102L])
    }

    // KMK v0.8.24: a source whose search finds nothing still gets its Popular page (the catalogue fallback)
    @Test
    fun `a source whose searches find nothing falls back to its Popular page`() = runTest {
        val fixture = queueFixture(count = 2, searchFindsNothing = setOf(2L))

        val state = refreshQueue(fixture)

        val row = state.items.entries.single { it.key.id == 2L }.value as PersonalRecommendationResult.Success
        assertEquals(listOf("/popular2"), row.result.map { it.manga.url }, "status ${state.sourceStatuses[2L]?.status}")
    }

    // KMK v0.8.24: sources still searching when the rows fill up are stopped and leave no spinner behind
    @Test
    fun `stopping early leaves no unfinished rows and the refresh counts as finished`() = runTest {
        // Sources 1-3 are slow and still in flight while the fast ones fill every row.
        val fixture = queueFixture(count = 40, slowSourceIds = setOf(1L, 2L, 3L), slowMillis = 600_000L)

        val state = refreshQueue(fixture)

        assertTrue(setOf(1L, 2L, 3L).all { fixture.started.containsKey(it) }, "the slow sources were started")
        assertTrue(fixture.started.size < 40, "the refresh still stopped early, searched ${fixture.started.size}")
        assertTrue(
            state.items.values.none { it is PersonalRecommendationResult.Loading },
            "no Loading row is left: ${state.items.filterValues { it is PersonalRecommendationResult.Loading }.keys.map { it.id }}",
        )
        assertTrue(state.items.keys.none { it.id in setOf(1L, 2L, 3L) }, "a stopped source writes no result")
        assertTrue(state.total > 0 && state.progress == state.total, "progress ${state.progress}/${state.total} is complete")
        assertFalse(state.isLoading)
    }

    @Test
    fun `data saving skips the extra discovery page and the controls still request it`() = runTest {
        fun extraPages(fixture: QueueFixture) = fixture.pagesRequested.count { it.second > 1 }

        val always = queueFixture(count = 5)
        refreshQueue(always, dataSaving = "on")
        assertEquals(0, extraPages(always), "Always: no extra page")
        assertEquals(
            5,
            always.pagesRequested.filter { it.second == 1 }.map { it.first }.toSet().size,
            "page one is still searched for every source",
        )
        val pageOnePerSource = { f: QueueFixture ->
            f.pagesRequested.filter { it.second == 1 }.groupingBy { it.first }.eachCount().values
        }
        assertTrue(
            pageOnePerSource(always).all { it <= ForYouDataSavingPolicy.SAVING_MAX_PLANS_PER_SOURCE },
            "Always: at most ${ForYouDataSavingPolicy.SAVING_MAX_PLANS_PER_SOURCE} query strategies per source, was ${pageOnePerSource(always)}",
        )

        val never = queueFixture(count = 5)
        refreshQueue(never, dataSaving = "off", constrained = true)
        assertTrue(extraPages(never) > 0, "Never keeps the discovery pages even on a constrained network")
        assertTrue(
            pageOnePerSource(never).any { it > ForYouDataSavingPolicy.SAVING_MAX_PLANS_PER_SOURCE },
            "control: Never keeps every query strategy, was ${pageOnePerSource(never)}",
        )

        val autoConstrained = queueFixture(count = 5)
        refreshQueue(autoConstrained, dataSaving = "auto", constrained = true)
        assertEquals(0, extraPages(autoConstrained), "Automatic on a constrained network saves data")

        val autoOpen = queueFixture(count = 5)
        refreshQueue(autoOpen, dataSaving = "auto", constrained = false)
        assertTrue(extraPages(autoOpen) > 0, "Automatic on an open network keeps the discovery pages")
    }

    @Test
    fun `data saving stops at its smaller row limit`() = runTest {
        val saving = queueFixture(count = 40)
        refreshQueue(saving, dataSaving = "on")
        val normal = queueFixture(count = 40)
        refreshQueue(normal, dataSaving = "off")

        assertTrue(saving.started.size < normal.started.size, "saving ${saving.started.size} vs normal ${normal.started.size}")
        assertTrue(saving.started.size >= ForYouDataSavingPolicy.SAVING_MAX_VISIBLE_SOURCE_ROWS)
    }
    // KMK <--

    @Test
    fun `live refresh keeps a remembered candidate when hide known is off`() = runTest {
        val shown = refreshWithRememberedLikedCandidate(hideKnown = false, knownIds = setOf(202L))

        assertTrue(shown.any { it.id == 101L })
        assertTrue(shown.any { it.id == 202L }, "with Hide known off a known remembered candidate stays visible")
    }
    // KMK <--

    @Test
    fun `public refresh reaches profile-empty terminal state`() = runTest {
        val getTasteProfile = mockk<GetTasteProfile>()
        coEvery { getTasteProfile.await() } returns TasteProfile.EMPTY
        val harness = buildHarness(getTasteProfile = getTasteProfile)

        harness.model.refresh()
        advanceUntilIdle()

        assertFalse(harness.model.state.value.isLoading)
        assertTrue(harness.model.state.value.profileIsEmpty)
    }

    @Test
    fun `real ScreenModel tracker assembly distinguishes known tracked from known empty`() = runTest {
        val repository = mockk<TrackRepository>()
        val harness = buildHarness(repository)
        val tracked = candidate(sourceId = 7L, mangaId = 42L, url = "/tracked")
        coEvery { repository.getTracksByMangaIds(listOf(42L)) } returns listOf(track(42L))

        val known = resolveTrackedExposureKeys(harness.model, 7L, listOf(tracked))

        assertEquals(
            RecommendationDisplayReranker.TrackedState.Known(
                setOf(RecommendationDisplayReranker.ExposureKey(7L, "/tracked")),
            ),
            known,
        )

        coEvery { repository.getTracksByMangaIds(listOf(42L)) } returns emptyList()
        val knownEmpty = resolveTrackedExposureKeys(harness.model, 7L, listOf(tracked))

        assertEquals(RecommendationDisplayReranker.TrackedState.Known(emptySet()), knownEmpty)
    }

    @Test
    fun `real ScreenModel tracker assembly turns repository failure into Unknown`() = runTest {
        val repository = mockk<TrackRepository>()
        val harness = buildHarness(repository)
        val candidate = candidate(sourceId = 7L, mangaId = 42L, url = "/failure")
        coEvery { repository.getTracksByMangaIds(listOf(42L)) } throws IllegalStateException("fixture failure")

        val result = resolveTrackedExposureKeys(harness.model, 7L, listOf(candidate))

        assertEquals(RecommendationDisplayReranker.TrackedState.Unknown, result)
    }

    @Test
    fun `real ScreenModel tracker assembly propagates cancellation`() {
        val repository = mockk<TrackRepository>()
        val harness = buildHarness(repository)
        val candidate = candidate(sourceId = 7L, mangaId = 42L, url = "/cancel")
        coEvery { repository.getTracksByMangaIds(listOf(42L)) } coAnswers {
            throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                resolveTrackedExposureKeys(harness.model, 7L, listOf(candidate))
            }
        }
    }

    @Test
    fun `visible exposure records one settled generation and preserves source identity`() = runTest {
        val sourceA = mockk<Source>(relaxed = true)
        val sourceB = mockk<Source>(relaxed = true)
        every { sourceA.id } returns 11L
        every { sourceB.id } returns 22L
        val keys = slot<List<Pair<Long, String>>>()
        val recordExposure = mockk<RecordRecommendationExposure>()
        coEvery { recordExposure.await(capture(keys), any(), any()) } just runs
        val harness = buildHarness(recordExposure = recordExposure)
        forceState(
            harness.model,
            BrowsePersonalRecommendationsScreenModel.State(
                items = persistentMapOf(
                    sourceA to PersonalRecommendationResult.Success(listOf(candidate(11L, 1L, "/same", "Source A title"))),
                    sourceB to PersonalRecommendationResult.Success(listOf(candidate(22L, 2L, "/same", "Source B title"))),
                ),
                isLoading = false,
                resultGeneration = 9L,
            ),
        )

        harness.model.recordVisibleExposure()
        harness.model.recordVisibleExposure()
        advanceUntilIdle()

        forceState(
            harness.model,
            harness.model.state.value.copy(resultGeneration = 10L),
        )
        harness.model.recordVisibleExposure()
        advanceUntilIdle()

        coVerify(exactly = 2) { recordExposure.await(any(), any(), any()) }
        assertEquals(setOf(11L to "/same", 22L to "/same"), keys.captured.toSet())
        coVerify(exactly = 2) { recordExposure.await(any(), any(), 1_000L) }
    }

    @Test
    fun `visible exposure ignores loading empty and partial states`() = runTest {
        val recordExposure = mockk<RecordRecommendationExposure>(relaxed = true)
        val harness = buildHarness(recordExposure = recordExposure)
        val sourceA = mockk<Source>(relaxed = true)
        val sourceB = mockk<Source>(relaxed = true)
        every { sourceA.id } returns 11L
        every { sourceB.id } returns 22L
        val success = PersonalRecommendationResult.Success(listOf(candidate(11L, 1L, "/visible")))

        forceState(harness.model, BrowsePersonalRecommendationsScreenModel.State())
        harness.model.recordVisibleExposure()
        forceState(
            harness.model,
            BrowsePersonalRecommendationsScreenModel.State(isLoading = false, resultGeneration = 1L),
        )
        harness.model.recordVisibleExposure()
        forceState(
            harness.model,
            BrowsePersonalRecommendationsScreenModel.State(
                items = persistentMapOf(sourceA to success, sourceB to PersonalRecommendationResult.Loading),
                isLoading = false,
                resultGeneration = 2L,
            ),
        )
        harness.model.recordVisibleExposure()
        advanceUntilIdle()

        coVerify(exactly = 0) { recordExposure.await(any(), any(), any()) }
        assertFalse(harness.model.state.value.isLoading)
    }
    private fun naturalObserver(model: BrowsePersonalRecommendationsScreenModel): ForYouNaturalRequestObservation =
        BrowsePersonalRecommendationsScreenModel::class.java.getDeclaredField("naturalObservation")
            .apply { isAccessible = true }.get(model) as ForYouNaturalRequestObservation

    private fun setActiveGeneration(model: BrowsePersonalRecommendationsScreenModel, generation: Long) {
        val active = BrowsePersonalRecommendationsScreenModel::class.java.getDeclaredField("activeRefreshGeneration")
            .apply { isAccessible = true }.get(model) as java.util.concurrent.atomic.AtomicLong
        active.set(generation)
    }

    @Test
    fun `real model natural observation respects explicit developer and evaluation gates`() {
        for ((developer, evaluation) in listOf(false to false, false to true, true to true, true to false)) {
            val preferences = SourcePreferences(FakePreferenceStore())
            preferences.developerOptionsEnabled().set(developer)
            preferences.evaluationMode().set(evaluation)
            val events = mutableListOf<String>()
            val model = buildHarness(preferences = preferences, naturalObservationSink = { events.add(it) }).model
            naturalObserver(model).generationStart(1)
            assertEquals(developer && !evaluation, events.isNotEmpty())
            model.onDispose()
        }
    }

    @Test
    fun `real provider wrapper records inside invocation and preserves cancellation identity`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        val events = mutableListOf<String>()
        val model = buildHarness(preferences = preferences, naturalObservationSink = { events.add(it) }).model
        val observer = naturalObserver(model)
        observer.generationStart(1)
        val request = observer.requestStart(1, 3991, "private actual query", "profile", 1)!!
        val cancellation = CancellationException("private provider cancellation")
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 3991L
        coEvery { source.getSearchManga(1, any(), any()) } coAnswers {
            assertTrue(events.last().contains("provider_start"))
            throw cancellation
        }
        val call = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "runObservedPersonalizedSearch" }.apply { isAccessible = true }
        var caught: Throwable? = null
        try {
            call.callSuspend(model, source, GenreFilterMapper.SearchParams(FilterList(), "private actual query"), 1, request, false)
        } catch (error: InvocationTargetException) {
            caught = error.cause
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(caught === cancellation)
        assertTrue(events.last().contains("\"outcome\":\"cancelled\""))
        assertFalse(events.any { it.contains("provider_return") || it.contains("source_applied") || it.contains("private") })
        coVerify(exactly = 1) { source.getSearchManga(1, any(), any()) }
        model.onDispose()
    }

    @Test
    fun `real commit retries do not publish abandoned state or accept a reserved newer generation`() {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        val events = mutableListOf<String>()
        val model = buildHarness(preferences = preferences, naturalObservationSink = { events.add(it) }).model
        val observer = naturalObserver(model)
        observer.generationStart(1)
        val request = observer.requestStart(1, 3992, "query", "profile", 2)!!
        observer.providerStart(request)
        observer.providerReturn(request, 2)
        observer.terminal(request, ForYouNaturalRequestObservation.Terminal.RETURNED)
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 3992L
        val recommendation = candidate(3992, 1, "/kept")
        val contribution = ForYouNaturalRequestObservation.Contribution(request, setOf(3992L to "/kept"))
        val initial = BrowsePersonalRecommendationsScreenModel.State(resultGeneration = 1)
        val delegate = kotlinx.coroutines.flow.MutableStateFlow(initial)
        var casAttempts = 0
        val failingCommit = object : kotlinx.coroutines.flow.MutableStateFlow<BrowsePersonalRecommendationsScreenModel.State> by delegate {
            override fun compareAndSet(
                expect: BrowsePersonalRecommendationsScreenModel.State,
                update: BrowsePersonalRecommendationsScreenModel.State,
            ): Boolean {
                casAttempts++
                delegate.value = expect.copy(resultGeneration = 2)
                return false
            }
        }
        StateScreenModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.set(model, failingCommit)
        setActiveGeneration(model, 1)
        val update = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "updateItem" }.apply { isAccessible = true }
        update.call(
            model,
            1L,
            source,
            PersonalRecommendationResult.Success(listOf(recommendation)),
            null,
            mutableMapOf<Long, Pair<Source, List<PersonalRecommendation>>>(),
            mapOf(3992L to 0),
            listOf(contribution),
        )
        assertEquals(1, casAttempts)
        assertTrue(delegate.value.items.isEmpty())
        assertTrue(events.last().contains("source_dropped"))
        assertFalse(events.any { it.contains("source_applied") || it.contains("refill_applied") })
        model.onDispose()
    }

    @Test
    fun `real model fixture input cannot export natural provider observations`() {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        preferences.forYouFixtureMode().set(ForYouDebugFixtureMode.entries.first { it != ForYouDebugFixtureMode.OFF }.prefValue)
        val events = mutableListOf<String>()
        val model = buildHarness(preferences = preferences, naturalObservationSink = { events.add(it) }, debugBuild = true).model
        naturalObserver(model).generationStart(1)
        assertTrue(events.isEmpty())
        model.onDispose()
    }

    @Test
    fun `real state commit publishes exact retained refill after committing and only once`() {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        val events = mutableListOf<String>()
        val model = buildHarness(preferences = preferences, naturalObservationSink = { event ->
            events.add(event)
        }).model
        val observer = naturalObserver(model)
        observer.generationStart(1)
        val request = observer.requestStart(1, 3993, "query", "profile", 2)!!
        observer.providerStart(request)
        observer.providerReturn(request, 8)
        observer.terminal(request, ForYouNaturalRequestObservation.Terminal.RETURNED)
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 3993L
        val kept = candidate(3993, 1, "/kept")
        val contribution = ForYouNaturalRequestObservation.Contribution(request, setOf(3993L to "/kept", 3993L to "/filtered"))
        forceState(model, BrowsePersonalRecommendationsScreenModel.State(resultGeneration = 1))
        setActiveGeneration(model, 1)
        val update = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "updateItem" }.apply { isAccessible = true }
        val completed = mutableMapOf<Long, Pair<Source, List<PersonalRecommendation>>>()
        repeat(2) {
            update.call(
                model,
                1L,
                source,
                PersonalRecommendationResult.Success(listOf(kept)),
                null,
                completed,
                mapOf(3993L to 0),
                listOf(contribution),
            )
        }
        assertEquals(listOf(kept), (model.state.value.items[source] as PersonalRecommendationResult.Success).result)
        assertEquals(1, events.count { it.contains("source_applied") })
        assertTrue(events.single { it.contains("refill_applied") }.contains("\"candidates\":1"))
        assertTrue(events.last().contains("source_applied"))
        model.onDispose()
    }

    @Test
    fun `actual runtime wrapper distinguishes recoverable suppression and preserves fatal error`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        val events = mutableListOf<String>()
        val model = buildHarness(preferences = preferences, naturalObservationSink = { events.add(it) }).model
        val observer = naturalObserver(model)
        observer.generationStart(1)
        val call = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "runObservedPersonalizedSearch" }.apply { isAccessible = true }
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 3994L
        val linkage = NoClassDefFoundError("private missing class")
        coEvery { source.getSearchManga(1, any(), any()) } throws linkage
        try {
            val first = observer.requestStart(1, 3994, "query", "profile", 1)!!
            var failure: Throwable? = null
            try {
                call.callSuspend(model, source, GenreFilterMapper.SearchParams(FilterList(), "query"), 1, first, false)
            } catch (error: Throwable) {
                failure = if (error is InvocationTargetException) error.cause else error
            }
            assertTrue(failure is eu.kanade.tachiyomi.source.RecoverableSourceRuntimeException)
            assertTrue(events.last().contains("\"outcome\":\"error\""))
            val second = observer.requestStart(1, 3994, "query", "profile", 1)!!
            try {
                call.callSuspend(model, source, GenreFilterMapper.SearchParams(FilterList(), "query"), 1, second, false)
            } catch (error: Throwable) {
                val cause = if (error is InvocationTargetException) error.cause else error
                assertTrue(cause is Exception)
                // The real SourceRuntime suppression result is deliberately propagated.
            }
            assertTrue(events.last().contains("\"outcome\":\"suppressed\""))
            assertEquals(1, events.count { it.contains("provider_start") })
            coVerify(exactly = 1) { source.getSearchManga(1, any(), any()) }
            eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry.clear(3994)
            val fatal = OutOfMemoryError("synthetic fatal boundary")
            coEvery { source.getSearchManga(1, any(), any()) } throws fatal
            val third = observer.requestStart(1, 3994, "query", "profile", 1)!!
            var fatalResult: Throwable? = null
            try {
                call.callSuspend(model, source, GenreFilterMapper.SearchParams(FilterList(), "query"), 1, third, false)
            } catch (error: Throwable) {
                fatalResult = if (error is InvocationTargetException) error.cause else error
            }
            assertTrue(fatalResult === fatal)
            assertTrue(events.last().contains("\"outcome\":\"error\""))
            assertFalse(events.any { it.contains("source_applied") || it.contains("private") })
        } finally {
            eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry.clear(3994)
            model.onDispose()
        }
    }

    @Test
    fun `observed exact cache entry is recognized by a fresh model cache read`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.developerOptionsEnabled().set(true)
        val first = buildHarness(preferences = preferences, naturalObservationSink = {}).model
        val observer = naturalObserver(first)
        observer.generationStart(1)
        val request = observer.requestStart(1, 3998, "actual query and filter identity", "profile", 1)!!
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 3998L
        val networkManga = SManga.create().apply {
            url = "/cached"
            title = "Observed cache"
            initialized = true
        }
        coEvery { source.getSearchManga(1, any(), any()) } returns MangasPage(listOf(networkManga), false)
        val invoke = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "runObservedPersonalizedSearch" }.apply { isAccessible = true }
        invoke.callSuspend(first, source, GenreFilterMapper.SearchParams(FilterList(), "query"), 1, request, false)
        val manga = candidate(3998, 1, "/cached").manga
        val save = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "saveToCache" }.apply { isAccessible = true }
        val entry = save.callSuspend(first, 3998L, "query", "profile", listOf(manga), listOf(1.0), null) as RecommendationCacheEntry
        observer.rememberCache(entry.cacheKey, "profile", request, entry, entry.expiresAt)
        first.onDispose()
        val repository = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        coEvery { repository.getMangaByIds(listOf(1L)) } returns listOf(manga)
        val cache = mockk<GetRecommendationCache>()
        coEvery { cache.await(any()) } returns entry
        val events = mutableListOf<String>()
        val current = buildHarness(
            preferences = preferences,
            getCache = cache,
            getManga = GetManga(repository),
            naturalObservationSink = { events.add(it) },
        ).model
        naturalObserver(current).generationStart(1)
        val read = BrowsePersonalRecommendationsScreenModel::class.declaredFunctions
            .single { it.name == "loadFromCacheObserved" }.apply { isAccessible = true }
        read.callSuspend(
            current, 3998L, "query", "profile", emptyMap<MangaTasteKey, MangaTaste>(),
            RatedMangaVisibility.SHOW_ALL_RATED, false, emptySet<SeenMangaKey>(), 0, 1L,
        )
        assertTrue(events.single { it.contains("cache_hit") }.contains("\"candidates\":1"))
        assertFalse(events.joinToString().contains("actual query"))
        coEvery { cache.await(any()) } returns entry.copy(createdAt = entry.createdAt + 1)
        read.callSuspend(
            current, 3998L, "query", "profile", emptyMap<MangaTasteKey, MangaTaste>(),
            RatedMangaVisibility.SHOW_ALL_RATED, false, emptySet<SeenMangaKey>(), 0, 1L,
        )
        assertEquals(1, events.count { it.contains("cache_hit") })
        current.onDispose()
    }
}
