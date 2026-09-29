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
        upsertDiscovery: UpsertRecommendationDiscoveryProgress = mockk(relaxed = true),
    ): Harness {
        val model = BrowsePersonalRecommendationsScreenModel(
            context = mockk<Context>(relaxed = true),
            isOnline = isOnline,
            clock = clock,
            searchDispatcher = searchDispatcher,
            isLowRamDevice = false,
            autoLoad = false,
            getTasteProfile = getTasteProfile,
            getTagAliases = getTagAliases,
            getDisabledSources = getDisabledSources,
            sourceManager = sourceManager,
            networkToLocalManga = networkToLocalManga,
            getMangaInteractor = getManga,
            getMangaTaste = getMangaTaste,
            sourcePreferences = SourcePreferences(FakePreferenceStore()),
            getRecommendationCache = getCache,
            upsertRecommendationCache = mockk<UpsertRecommendationCache>(relaxed = true),
            clearRecommendationCache = mockk<ClearRecommendationCache>(relaxed = true),
            getKnownMangaIds = getKnownManga,
            getChapterCounts = getChapterCounts,
            getMemory = mockk<GetRecommendationCandidateMemory>(relaxed = true),
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
}
