package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SameMangaCandidateSearcherLifecycleTest {

    @Test
    fun `cancellation propagates without a late result callback`() = runTest {
        val enteredSearch = CompletableDeferred<Unit>()
        val source = FakeSource(9_910_007) {
            enteredSearch.complete(Unit)
            awaitCancellation()
        }
        val results = mutableListOf<SameMangaSourceResult>()
        val searcher = searcher(UnconfinedTestDispatcher(testScheduler))

        val job = launch {
            searcher.search(
                queries = listOf("query"),
                settings = settings(),
                originManga = origin(),
                sources = listOf(source),
                onResult = results::add,
            )
        }
        enteredSearch.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `stalled source times out without discarding another source success`() = runTest {
        val first = FakeSource(9_910_013) { page(manga("/success", "Shared")) }
        val stalled = FakeSource(9_910_014) { awaitCancellation() }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), identityNetworkToLocalManga()).search(
            queries = listOf("query"),
            settings = settings(),
            originManga = origin(),
            sources = listOf(first, stalled),
            onResult = results::add,
        )

        assertEquals(1, results.count { it.result is SameMangaCandidateResult.Success })
        val failure = results.single { it.result is SameMangaCandidateResult.Error }
            .result as SameMangaCandidateResult.Error
        assertTrue(failure.throwable is java.util.concurrent.TimeoutException)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun `blocking sources publish timeout results while source calls remain blocked`() = runTest {
        val workerCount = 5
        val entered = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        val sourceDispatcher = Executors.newFixedThreadPool(workerCount).asCoroutineDispatcher()
        val enteredSourceIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val blockedSources = (0 until workerCount * 2).map { index ->
            val sourceId = 9_910_030L + index
            FakeSource(sourceId) {
                enteredSourceIds.add(sourceId)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                page(manga("/blocked-$index", "Blocked"))
            }
        }
        val queuedHealthySource = FakeSource(9_910_040) { page(manga("/healthy", "Healthy")) }
        val allSources = blockedSources + queuedHealthySource
        val results = mutableListOf<SameMangaSourceResult>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            searcher(
                sourceDispatcher,
                identityNetworkToLocalManga(),
                timeoutDispatcher = UnconfinedTestDispatcher(testScheduler),
            ).search(
                queries = listOf("query"),
                settings = settings(),
                originManga = origin(),
                sources = allSources,
                onResult = results::add,
            )
        }

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Blocking sources did not occupy all workers")
            advanceTimeBy(20_000L)
            runCurrent()

            assertEquals(20_000L, testScheduler.currentTime)
            assertTrue(!job.isCompleted, "Search should remain suspended until non-cooperative calls return")
            assertEquals(allSources.size, results.size, "Timeouts should be published independently of blocked calls")
            assertTrue(results.all { it.result is SameMangaCandidateResult.Error })
            assertTrue(
                results.all {
                    (it.result as SameMangaCandidateResult.Error).throwable is java.util.concurrent.TimeoutException
                },
            )
            assertEquals(workerCount, enteredSourceIds.size)

            release.countDown()
            job.join()
            assertTrue(job.isCompleted, "Releasing blocked sources should allow search cleanup")
            assertEquals(allSources.size, results.size, "Late source returns must not publish duplicate results")

            val recoveryCalls = java.util.concurrent.atomic.AtomicInteger()
            val recoverySource = FakeSource(9_910_041) {
                recoveryCalls.incrementAndGet()
                page(manga("/recovered", "Recovered"))
            }
            val recoveryResults = mutableListOf<SameMangaSourceResult>()
            searcher(
                sourceDispatcher,
                identityNetworkToLocalManga(),
                timeoutDispatcher = sourceDispatcher,
            ).search(
                queries = listOf("query"),
                settings = settings(),
                originManga = origin(),
                sources = listOf(recoverySource),
                onResult = recoveryResults::add,
            )

            assertEquals(1, recoveryCalls.get(), "The released worker pool should accept a later search")
            assertEquals(1, recoveryResults.size)
            assertTrue(recoveryResults.single().result is SameMangaCandidateResult.Success)
        } finally {
            release.countDown()
            job.cancelAndJoin()
            sourceDispatcher.close()
        }
    }

    @Test
    fun `ordinary failure is isolated per source`() = runTest {
        val networkToLocalManga = identityNetworkToLocalManga()
        val first = FakeSource(9_910_008) { page(manga("/success", "Shared")) }
        val second = FakeSource(9_910_009) { throw IllegalArgumentException("second") }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), networkToLocalManga).search(
            queries = listOf("query"),
            settings = settings(),
            originManga = origin(),
            sources = listOf(first, second),
            onResult = results::add,
        )

        assertEquals(setOf(9_910_008L, 9_910_009L), results.map { it.source.id }.toSet())
        assertEquals(1, results.count { it.result is SameMangaCandidateResult.Success })
        assertEquals(1, results.count { it.result is SameMangaCandidateResult.Error })
    }

    @Test
    fun `recoverable linkage failure is isolated from a successful source`() = runTest {
        val networkToLocalManga = identityNetworkToLocalManga()
        val first = FakeSource(9_910_010) { page(manga("/success", "Shared")) }
        val second = FakeSource(9_910_011) { throw NoClassDefFoundError("fixture dependency") }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), networkToLocalManga).search(
            queries = listOf("query"),
            settings = settings(),
            originManga = origin(),
            sources = listOf(first, second),
            onResult = results::add,
        )

        assertEquals(1, results.count { it.result is SameMangaCandidateResult.Success })
        val failure = results.single { it.result is SameMangaCandidateResult.Error }
            .result as SameMangaCandidateResult.Error
        assertTrue(failure.throwable is NoClassDefFoundError)
    }

    @Test
    fun `fatal error propagates instead of becoming a source result`() = runTest {
        val source = FakeSource(9_910_012) { throw OutOfMemoryError("fatal") }
        var propagated = false

        try {
            searcher(UnconfinedTestDispatcher(testScheduler)).search(
                queries = listOf("query"),
                settings = settings(),
                originManga = origin(),
                sources = listOf(source),
                onResult = {},
            )
        } catch (_: OutOfMemoryError) {
            propagated = true
        }

        assertTrue(propagated)
    }

    @Test
    fun `origin exclusion happens before the result cap`() = runTest {
        val source = FakeSource(99) {
            page(
                manga("/origin", "Origin"),
                manga("/candidate", "Origin"),
            )
        }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), identityNetworkToLocalManga()).search(
            queries = listOf("query"),
            settings = settings(resultCap = 1),
            originManga = origin(),
            sources = listOf(source),
            onResult = results::add,
        )

        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(listOf("/candidate"), success.results.map(Manga::url))
    }

    @Test
    fun `query and URL duplicates are deduplicated in first-seen order`() = runTest {
        val source = FakeSource(9_910_002) { query ->
            when (query) {
                "first" -> page(manga("/a", "Origin"), manga("/b", "Origin"))
                else -> page(manga("/a", "Origin"), manga("/c", "Origin"))
            }
        }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), identityNetworkToLocalManga()).search(
            queries = listOf("first", "second"),
            settings = settings(resultCap = 5),
            originManga = origin(),
            sources = listOf(source),
            onResult = results::add,
        )

        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(listOf("/a", "/b", "/c"), success.results.map(Manga::url))
    }

    @Test
    fun `identical relative URLs from different sources remain distinct candidates`() = runTest {
        val first = FakeSource(9_910_003) { page(manga("/shared", "Origin")) }
        val second = FakeSource(9_910_004) { page(manga("/shared", "Origin")) }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), identityNetworkToLocalManga()).search(
            queries = listOf("query"),
            settings = settings(resultCap = 5),
            originManga = origin(),
            sources = listOf(first, second),
            onResult = results::add,
        )

        val candidates = results.flatMap { result ->
            (result.result as SameMangaCandidateResult.Success).results
        }
        assertEquals(setOf(9_910_003L, 9_910_004L), candidates.map(Manga::source).toSet())
        assertEquals(listOf("/shared", "/shared"), candidates.map(Manga::url))
    }

    @Test
    fun `result cap preserves source result order when evidence ties`() = runTest {
        val source = FakeSource(9_910_005) {
            page(
                manga("/c", "Same"),
                manga("/a", "Same"),
                manga("/b", "Same"),
            )
        }
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(UnconfinedTestDispatcher(testScheduler), identityNetworkToLocalManga()).search(
            queries = listOf("query"),
            settings = settings(resultCap = 2),
            originManga = origin(title = "Same"),
            sources = listOf(source),
            onResult = results::add,
        )

        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(listOf("/c", "/a"), success.results.map(Manga::url))
    }

    @Test
    fun `global scope uses normal global sources and does not apply matching result cap`() = runTest {
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        every { sourcePreferences.enabledLanguages().get() } returns setOf("en", "ja")
        every { sourcePreferences.disabledSources().get() } returns setOf("4")
        every { sourcePreferences.pinnedSources().get() } returns setOf("9910020")
        val sourceManager = mockk<SourceManager>()
        val pinned = FakeSource(9_910_020, lang = "en", name = "Pinned") { page(manga("/pinned-a", "Different Alias"), manga("/pinned-b", "Another Title")) }
        val regular = FakeSource(9_910_021, lang = "ja", name = "Japanese") { page(manga("/ja", "別名")) }
        val disabled = FakeSource(4, lang = "en", name = "Disabled") { page(manga("/disabled", "Hidden")) }
        every { sourceManager.getVisibleSources() } returns listOf(regular, disabled, pinned)

        val results = mutableListOf<SameMangaSourceResult>()
        SameMangaCandidateSearcher(
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            networkToLocalManga = identityNetworkToLocalManga(),
            coroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
            getIdentityDecisions = mockk<tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions>().also {
                coEvery { it.await(any()) } returns null
            },
        ).search(
            queries = listOf("Origin"),
            settings = settings(resultCap = 1),
            originManga = origin(),
            scope = SameMangaSearchScope.GLOBAL,
            onResult = results::add,
        )

        assertEquals(setOf(9_910_020L, 9_910_021L), results.map { it.source.id }.toSet())
        val pinnedResult = results.single { it.source.id == pinned.id }.result as SameMangaCandidateResult.Success
        assertEquals(setOf("/pinned-a", "/pinned-b"), pinnedResult.results.map(Manga::url).toSet())
    }

    @Test
    fun `bounded global scope uses global sources and honors configured per-source result cap`() = runTest {
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        every { sourcePreferences.enabledLanguages().get() } returns setOf("en")
        every { sourcePreferences.disabledSources().get() } returns setOf("9911023")
        every { sourcePreferences.pinnedSources().get() } returns emptySet()
        val queries = mutableListOf<String>()
        val enabled = FakeSource(9_911_022) { query ->
            queries += query
            page(manga("/first", "Origin"), manga("/second", "Origin"))
        }
        val disabled = FakeSource(9_911_023) { page(manga("/disabled", "Origin")) }
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.getVisibleSources() } returns listOf(enabled, disabled)
        val results = mutableListOf<SameMangaSourceResult>()

        SameMangaCandidateSearcher(
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            networkToLocalManga = identityNetworkToLocalManga(),
            coroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
            getIdentityDecisions = mockk<tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions>().also {
                coEvery { it.await(any()) } returns null
            },
        ).search(
            queries = listOf("Origin"),
            settings = settings(resultCap = 1),
            originManga = origin(),
            scope = SameMangaSearchScope.GLOBAL_BOUNDED,
            onResult = results::add,
        )

        assertEquals(listOf(enabled.id), results.map { it.source.id })
        assertEquals(listOf("Origin"), queries)
        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(listOf("/first"), success.results.map(Manga::url))
    }

    @Test
    fun `bounded global scope retains three candidates per eligible source regardless of identity conflicts`() = runTest {
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        every { sourcePreferences.enabledLanguages().get() } returns setOf("en")
        every { sourcePreferences.disabledSources().get() } returns setOf("9911033")
        every { sourcePreferences.pinnedSources().get() } returns emptySet()
        val first = FakeSource(9_911_031) {
            page(*(1..4).map { manga("/first-$it", "Unrelated first $it") }.toTypedArray())
        }
        val second = FakeSource(9_911_032) {
            page(*(1..4).map { manga("/second-$it", "Unrelated second $it") }.toTypedArray())
        }
        val disabled = FakeSource(9_911_033) {
            page(manga("/disabled", "Unrelated disabled"))
        }
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.getVisibleSources() } returns listOf(first, second, disabled)
        val confirmed = MangaIdentityKey(first.id, "/first-2")
        val pair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
            tachiyomi.domain.taste.model.CrossSourceRecordKey(99, "/origin"),
            tachiyomi.domain.taste.model.CrossSourceRecordKey(confirmed.source, confirmed.url),
        )
        val confirmation = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.userDecision(
            pair = pair,
            value = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue.USER_CONFIRMED,
            previous = null,
            timestamp = 10_000,
        )
        val identityDecisions = mockk<tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions>()
        coEvery { identityDecisions.await(any()) } answers {
            confirmation.takeIf { it.pair == firstArg() }
        }
        val results = mutableListOf<SameMangaSourceResult>()

        SameMangaCandidateSearcher(
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            networkToLocalManga = identityNetworkToLocalManga(),
            coroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
            getIdentityDecisions = identityDecisions,
        ).search(
            queries = listOf("Origin"),
            settings = settings(resultCap = 3),
            originManga = origin("Origin"),
            scope = SameMangaSearchScope.GLOBAL_BOUNDED,
            onResult = results::add,
        )

        assertEquals(setOf(first.id, second.id), results.map { it.source.id }.toSet())
        val bySource = results.associate { it.source.id to (it.result as SameMangaCandidateResult.Success) }
        assertEquals(listOf("/first-1", "/first-2", "/first-3"), bySource.getValue(first.id).results.map(Manga::url))
        assertEquals(listOf("/second-1", "/second-2", "/second-3"), bySource.getValue(second.id).results.map(Manga::url))
        assertEquals(setOf(confirmed), bySource.getValue(first.id).confirmedKeys)
        assertTrue(bySource.getValue(second.id).confirmedKeys.isEmpty())
    }

    @Test
    fun `global search carries authoritative confirmation keys to the caller`() = runTest {
        val source = FakeSource(9_910_022, name = "Grouped") {
            page(manga("/confirmed", "An alternate title"))
        }
        val pair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
            tachiyomi.domain.taste.model.CrossSourceRecordKey(99, "/origin"),
            tachiyomi.domain.taste.model.CrossSourceRecordKey(source.id, "/confirmed"),
        )
        val confirmation = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.userDecision(
            pair = pair,
            value = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue.USER_CONFIRMED,
            previous = null,
            timestamp = 10_000,
        )
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            networkToLocalManga = identityNetworkToLocalManga(),
            identityDecision = confirmation,
        ).search(
            queries = listOf("Origin"),
            settings = settings(),
            originManga = origin(),
            sources = listOf(source),
            scope = SameMangaSearchScope.GLOBAL,
            onResult = results::add,
        )

        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(setOf(MangaIdentityKey(source.id, "/confirmed")), success.confirmedKeys)
    }

    @Test
    fun `current user rejection is suppressed before candidate delivery`() = runTest {
        val source = FakeSource(6) { page(manga("/rejected", "Same"), manga("/unknown", "Same")) }
        val rejected = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.userDecision(
            pair = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy.canonicalPair(
                tachiyomi.domain.taste.model.CrossSourceRecordKey(99, "/origin"),
                tachiyomi.domain.taste.model.CrossSourceRecordKey(6, "/rejected"),
            ),
            value = tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue.USER_REJECTED,
            previous = null,
            timestamp = 10_000,
        )
        val results = mutableListOf<SameMangaSourceResult>()

        searcher(
            UnconfinedTestDispatcher(testScheduler),
            identityNetworkToLocalManga(),
            rejected,
        ).search(
            queries = listOf("query"),
            settings = settings(resultCap = 2),
            originManga = origin(title = "Same"),
            sources = listOf(source),
            onResult = results::add,
        )

        val success = results.single().result as SameMangaCandidateResult.Success
        assertEquals(listOf("/unknown"), success.results.map(Manga::url))
    }

    private fun searcher(
        dispatcher: CoroutineDispatcher,
        networkToLocalManga: NetworkToLocalManga = mockk(relaxed = true),
        identityDecision: tachiyomi.domain.taste.model.CrossSourceIdentityDecision? = null,
        timeoutDispatcher: CoroutineDispatcher = dispatcher,
    ) = SameMangaCandidateSearcher(
        sourcePreferences = mockk<SourcePreferences>(relaxed = true),
        sourceManager = mockk<SourceManager>(relaxed = true),
        networkToLocalManga = networkToLocalManga,
        coroutineDispatcher = dispatcher,
        timeoutDispatcher = timeoutDispatcher,
        getIdentityDecisions = mockk<tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions>().also {
            coEvery { it.await(any()) } answers {
                identityDecision?.takeIf { decision -> decision.pair == firstArg() }
            }
        },
    )

    private fun settings(resultCap: Int = 2) = SameMangaMatchSettings(
        resultsPerSource = resultCap,
        preselectResults = false,
        previewSampleSize = 5,
        avoidFirstPages = true,
    )

    private fun origin(title: String = "Origin") = Manga.create().copy(
        source = 99,
        url = "/origin",
        ogTitle = title,
    )

    private fun identityNetworkToLocalManga(): NetworkToLocalManga {
        return NetworkToLocalManga(
            mockk<MangaRepository>().also { repository ->
                coEvery { repository.insertNetworkManga(any(), true) } coAnswers { arg<List<Manga>>(0) }
            },
        )
    }

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }

    private fun page(vararg mangas: SManga) = MangasPage(mangas.toList(), false)

    private inner class FakeSource(
        override val id: Long,
        override val lang: String = "en",
        override val name: String = "Fixture $id",
        private val search: suspend (String) -> MangasPage,
    ) : Source {
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val value = search(query)
            return value
        }
        override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()
        override fun getFilterList() = FilterList()
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException()
    }
}
