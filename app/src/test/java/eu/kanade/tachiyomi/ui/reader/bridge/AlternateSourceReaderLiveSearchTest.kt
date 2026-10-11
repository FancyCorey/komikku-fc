package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntimeFailure
import eu.kanade.tachiyomi.source.SourceRuntimeFailureKind
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.SourceRuntimeOperation
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import exh.recs.matching.SameMangaCandidateSearcher
import exh.recs.matching.SameMangaMatchSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AlternateSourceReaderLiveSearchTest {

    @Test
    fun `explicit retry clears only selected capped sources`() = runTest {
        val sourceDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
        val lifecycleJob = SupervisorJob()
        val lifecycleScope = CoroutineScope(lifecycleJob + UnconfinedTestDispatcher(testScheduler))
        val maxSources = 12
        val selectedSources = (0 until maxSources).map { index -> FakeSource(8_814_000L + index) { page() } }
        val overCapSource = FakeSource(8_814_100L) { page() }
        val unrelatedSource = 8_814_200L
        val origin = Manga.create().copy(source = 8_814_300L, url = "/origin", ogTitle = "Origin")
        val allIds = (selectedSources.map(Source::id) + overCapSource.id + unrelatedSource).toSet()
        allIds.forEach(SourceRuntimeFailureRegistry::clear)
        allIds.forEach(::recordFailure)
        val liveSearch = newLiveSearch(
            scope = lifecycleScope,
            sourceDispatcher = sourceDispatcher,
            timeoutDispatcher = Dispatchers.Default,
            sourceProvider = { selectedSources + overCapSource },
            maxSources = maxSources,
        )

        try {
            assertTrue(liveSearch.search(origin, explicitRetry = true).isEmpty())
            selectedSources.forEach { assertEquals(null, SourceRuntimeFailureRegistry.get(it.id)) }
            assertTrue(SourceRuntimeFailureRegistry.get(overCapSource.id) != null)
            assertTrue(SourceRuntimeFailureRegistry.get(unrelatedSource) != null)
        } finally {
            lifecycleJob.cancelAndJoin()
            sourceDispatcher.close()
            allIds.forEach(SourceRuntimeFailureRegistry::clear)
        }
    }

    @Test
    fun `explicit retry join and busy rejection do not clear or start another producer`() = runTest {
        val sourceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val lifecycleJob = SupervisorJob()
        val lifecycleScope = CoroutineScope(lifecycleJob + UnconfinedTestDispatcher(testScheduler))
        val sourceId = 8_815_100L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val providerCalls = AtomicInteger()
        val source = FakeSource(sourceId) {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            page()
        }
        val origin = Manga.create().copy(source = 8_815_000L, url = "/origin", ogTitle = "Origin")
        val otherOrigin = Manga.create().copy(source = 8_815_001L, url = "/other", ogTitle = "Origin")
        SourceRuntimeFailureRegistry.clear(sourceId)
        val liveSearch = newLiveSearch(
            scope = lifecycleScope,
            sourceDispatcher = sourceDispatcher,
            timeoutDispatcher = Dispatchers.Default,
            sourceProvider = {
                providerCalls.incrementAndGet()
                listOf(source)
            },
            maxSources = 1,
        )
        val first = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(origin) }
        var joined: kotlinx.coroutines.Deferred<List<AlternateSourceReaderLiveCandidateRead>>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            recordFailure(sourceId)
            val seeded = requireNotNull(SourceRuntimeFailureRegistry.get(sourceId))
            joined = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(origin, explicitRetry = true) }
            runCurrent()
            assertEquals(seeded, SourceRuntimeFailureRegistry.get(sourceId))
            assertEquals(
                listOf(AlternateSourceReaderLiveCandidateRead.SourceFailure),
                liveSearch.search(otherOrigin, explicitRetry = true),
            )
            assertEquals(seeded, SourceRuntimeFailureRegistry.get(sourceId))
            assertEquals(1, providerCalls.get())
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(first.await().isEmpty())
            assertTrue(requireNotNull(joined).await().isEmpty())
        } finally {
            release.countDown()
            first.cancelAndJoin()
            joined?.cancelAndJoin()
            lifecycleJob.cancelAndJoin()
            sourceDispatcher.close()
            SourceRuntimeFailureRegistry.clear(sourceId)
        }
    }

    @Test
    fun `terminal callbacks return before blocked cleanup and same gateway recovers after release`() = runTest {
        val workerCount = 5
        val blockedSourceCount = workerCount * 2
        val entered = CountDownLatch(workerCount)
        val releasedCallsReturned = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        val cancelledRelease = CountDownLatch(1)
        val activeWorkers = AtomicInteger()
        val peakWorkers = AtomicInteger()
        val enteredSourceIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val sourceDispatcher = Executors.newFixedThreadPool(workerCount).asCoroutineDispatcher()
        val cancellationExecutor = Executors.newSingleThreadExecutor()
        val lifecycleJob = SupervisorJob()
        val lifecycleScope = CoroutineScope(lifecycleJob + UnconfinedTestDispatcher(testScheduler))
        val blockedSources = (0 until blockedSourceCount).map { index ->
            val sourceId = 8_811_000L + index
            FakeSource(sourceId) {
                enteredSourceIds.add(sourceId)
                val active = activeWorkers.incrementAndGet()
                peakWorkers.updateAndGet { previous -> maxOf(previous, active) }
                try {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    page(manga("/blocked-$index", "Blocked"))
                } finally {
                    activeWorkers.decrementAndGet()
                    releasedCallsReturned.countDown()
                }
            }
        }
        val healthyCalls = AtomicInteger()
        val healthySource = FakeSource(8_811_100L) {
            healthyCalls.incrementAndGet()
            page(manga("/healthy", "Origin"))
        }
        val currentSources = AtomicReference<List<Source>>(emptyList())
        val sourceProviderCalls = AtomicInteger()
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any(), true) } coAnswers { arg<List<Manga>>(0) }
        val identityDecisions = mockk<GetCrossSourceIdentityDecisions>()
        coEvery { identityDecisions.await(any()) } returns null
        val searcher = SameMangaCandidateSearcher(
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            networkToLocalManga = NetworkToLocalManga(repository),
            coroutineDispatcher = sourceDispatcher,
            getIdentityDecisions = identityDecisions,
            timeoutDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val liveSearch = AlternateSourceReaderLiveSearch(
            scope = lifecycleScope,
            searcher = searcher,
            settings = SameMangaMatchSettings(
                resultsPerSource = SameMangaMatchSettings.DEFAULT_RESULT_CAP,
                preselectResults = false,
                previewSampleSize = SameMangaMatchSettings.DEFAULT_SAMPLE_SIZE,
                avoidFirstPages = true,
            ),
            sourceProvider = {
                sourceProviderCalls.incrementAndGet()
                currentSources.get()
            },
            maxSources = 12,
        )
        val origin = Manga.create().copy(source = 8_810_000L, url = "/origin", ogTitle = "Origin")
        val otherOrigin = Manga.create().copy(source = 8_810_001L, url = "/other", ogTitle = "Origin")
        assertTrue(liveSearch.search(origin).isEmpty(), "An empty source list should return an empty terminal result")
        assertEquals(1, sourceProviderCalls.get(), "An empty source list must not start producer work")
        currentSources.set(blockedSources)
        val terminalSearch = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(origin) }
        var healthySearch: kotlinx.coroutines.Deferred<List<AlternateSourceReaderLiveCandidateRead>>? = null
        var cancelledSearch: kotlinx.coroutines.Deferred<List<AlternateSourceReaderLiveCandidateRead>>? = null
        val cancelledSearchFinished = CountDownLatch(1)
        val cancelledSearchCause = AtomicReference<Throwable?>()

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The production searcher did not occupy all five workers")
            assertEquals(workerCount, activeWorkers.get())
            val producer = lifecycleJob.children.single()
            val producerFinished = CountDownLatch(1)
            producer.invokeOnCompletion { producerFinished.countDown() }

            advanceTimeBy(20_000L)
            runCurrent()

            val terminal = terminalSearch.await()
            assertEquals(blockedSourceCount, terminal.size, "Every selected source should publish one timeout")
            assertTrue(terminal.all { it == AlternateSourceReaderLiveCandidateRead.SourceFailure })
            assertFalse(producer.isCompleted, "The caller must return while non-cooperative source calls remain blocked")
            assertEquals(workerCount, enteredSourceIds.size, "Canceled queued source calls must not enter")
            assertEquals(workerCount, peakWorkers.get(), "The producer exceeded its real worker-pool bound")
            assertEquals(workerCount, activeWorkers.get())

            assertEquals(terminal, liveSearch.search(origin), "Same-origin retry should reuse the terminal snapshot")
            assertEquals(
                listOf(AlternateSourceReaderLiveCandidateRead.SourceFailure),
                liveSearch.search(otherOrigin),
                "A different origin must fail recoverably while the old producer still owns the slot",
            )
            assertEquals(2, sourceProviderCalls.get(), "Coalescing and busy rejection must not spawn another producer")
            assertEquals(workerCount, activeWorkers.get(), "The timeout result must not release blocked workers")

            currentSources.set(listOf(healthySource))
            val healthyStartClock = testScheduler.currentTime
            val healthyStartProviderCalls = sourceProviderCalls.get()
            val healthyStartLifecycleActive = lifecycleJob.isActive
            val healthyStartLifecycleCancelled = lifecycleJob.isCancelled
            val healthyStartChildStates = lifecycleJob.children.mapIndexed { index, job ->
                "$index(active=${job.isActive},completed=${job.isCompleted},cancelled=${job.isCancelled})"
            }.toList()
            val healthyStartActiveWorkers = activeWorkers.get()
            val initialHealthySearch = async(UnconfinedTestDispatcher(testScheduler)) {
                liveSearch.search(origin, explicitRetry = true)
            }
            healthySearch = initialHealthySearch
            val healthyPublished = CountDownLatch(1)
            initialHealthySearch.invokeOnCompletion { healthyPublished.countDown() }
            assertFalse(
                initialHealthySearch.isCompleted,
                "Explicit retry must wait while timed-out source calls still own the producer slot",
            )
            assertEquals(2, sourceProviderCalls.get(), "Explicit retry started before old-producer cleanup")

            release.countDown()
            assertTrue(releasedCallsReturned.await(5, TimeUnit.SECONDS), "Blocked source calls did not return after release")
            assertTrue(producerFinished.await(5, TimeUnit.SECONDS), "The producer slot did not clear after actual cleanup")
            assertEquals(0, activeWorkers.get())
            assertEquals(workerCount, enteredSourceIds.size, "Timed-out queued calls must remain canceled after release")
            assertEquals(blockedSourceCount, terminal.size, "Late source returns must not mutate the published snapshot")

            assertTrue(
                healthyPublished.await(5, TimeUnit.SECONDS),
                "The explicit retry did not publish a healthy terminal result after cleanup",
            )
            runCurrent()
            val healthy = initialHealthySearch.await()
            val healthyProducerFinished = observeProducerCompletion(lifecycleScope)
            assertTrue(
                healthyProducerFinished.await(5, TimeUnit.SECONDS),
                "The healthy producer did not finish after publishing its terminal callback",
            )
            val healthyEndClock = testScheduler.currentTime
            val healthyEndProviderCalls = sourceProviderCalls.get()
            val healthyEndLifecycleActive = lifecycleJob.isActive
            val healthyEndLifecycleCancelled = lifecycleJob.isCancelled
            val healthyEndChildStates = lifecycleJob.children.mapIndexed { index, job ->
                "$index(active=${job.isActive},completed=${job.isCompleted},cancelled=${job.isCancelled})"
            }.toList()
            val healthyEndActiveWorkers = activeWorkers.get()
            val healthyResultKinds = healthy.map { result ->
                when (result) {
                    is AlternateSourceReaderLiveCandidateRead.Candidate -> "Candidate"
                    AlternateSourceReaderLiveCandidateRead.SourceFailure -> "SourceFailure"
                }
            }
            assertEquals(
                1,
                healthyCalls.get(),
                "Healthy search did not call the source; before=(clock=$healthyStartClock, " +
                    "providerCalls=$healthyStartProviderCalls, lifecycleActive=$healthyStartLifecycleActive, " +
                    "lifecycleCancelled=$healthyStartLifecycleCancelled, children=$healthyStartChildStates, " +
                    "activeWorkers=$healthyStartActiveWorkers), after=(clock=$healthyEndClock, " +
                    "providerCalls=$healthyEndProviderCalls, lifecycleActive=$healthyEndLifecycleActive, " +
                    "lifecycleCancelled=$healthyEndLifecycleCancelled, children=$healthyEndChildStates, " +
                    "activeWorkers=$healthyEndActiveWorkers), resultKinds=$healthyResultKinds",
            )
            assertEquals(1, healthy.size)
            assertTrue(healthy.single() is AlternateSourceReaderLiveCandidateRead.Candidate)

            val cancelledEntered = CountDownLatch(1)
            val cancelledReturned = CountDownLatch(1)
            val cancelledSource = FakeSource(8_811_101L) {
                activeWorkers.incrementAndGet()
                try {
                    cancelledEntered.countDown()
                    while (cancelledRelease.count > 0) {
                        try {
                            cancelledRelease.await()
                        } catch (_: InterruptedException) {
                            // Source calls are non-cooperative: interruption must not release the slot.
                        }
                    }
                    page(manga("/cancelled", "Cancelled"))
                } finally {
                    activeWorkers.decrementAndGet()
                    cancelledReturned.countDown()
                }
            }
            currentSources.set(listOf(cancelledSource))
            cancelledSearch = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(otherOrigin) }
            assertTrue(cancelledEntered.await(5, TimeUnit.SECONDS), "The cancellation source did not block")
            val cancelledProducer = lifecycleJob.children.single()
            val cancelledProducerFinished = CountDownLatch(1)
            cancelledProducer.invokeOnCompletion { cancelledProducerFinished.countDown() }
            requireNotNull(cancelledSearch).invokeOnCompletion { cause ->
                cancelledSearchCause.set(cause)
                cancelledSearchFinished.countDown()
            }

            val cancellationCall = cancellationExecutor.submit { liveSearch.cancel() }
            cancellationCall.get(5, TimeUnit.SECONDS)
            assertFalse(cancelledProducer.isCompleted, "Cancellation must retain the slot while source cleanup is blocked")
            assertEquals(1, activeWorkers.get())
            assertEquals(
                listOf(AlternateSourceReaderLiveCandidateRead.SourceFailure),
                liveSearch.search(otherOrigin),
                "A retry must remain fenced until the canceled producer actually exits",
            )
            assertEquals(4, sourceProviderCalls.get(), "A fenced retry must not start another source wave")

            cancelledRelease.countDown()
            assertTrue(cancelledReturned.await(5, TimeUnit.SECONDS), "The canceled source did not return after release")
            assertTrue(
                cancelledProducerFinished.await(5, TimeUnit.SECONDS),
                "The canceled producer slot did not clear after actual cleanup",
            )
            assertTrue(
                cancelledSearchFinished.await(5, TimeUnit.SECONDS),
                "The canceled adapter caller did not finish after source cleanup",
            )
            assertTrue(cancelledSearchCause.get() is kotlinx.coroutines.CancellationException)
            assertEquals(0, activeWorkers.get())

            currentSources.set(listOf(healthySource))
            val recoverySearch = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(otherOrigin) }
            healthySearch = recoverySearch
            val recoveryPublished = CountDownLatch(1)
            recoverySearch.invokeOnCompletion { recoveryPublished.countDown() }
            assertTrue(recoveryPublished.await(5, TimeUnit.SECONDS), "Fresh work did not publish after cancellation cleanup")
            runCurrent()
            val recovered = recoverySearch.await()
            val recoveryProducerFinished = observeProducerCompletion(lifecycleScope)
            assertTrue(recoveryProducerFinished.await(5, TimeUnit.SECONDS))
            assertEquals(2, healthyCalls.get())
            assertEquals(1, recovered.size)
            assertTrue(recovered.single() is AlternateSourceReaderLiveCandidateRead.Candidate)
        } finally {
            release.countDown()
            cancelledRelease.countDown()
            runCatching { liveSearch.cancel() }
            terminalSearch.cancelAndJoin()
            healthySearch?.cancelAndJoin()
            cancelledSearch?.cancelAndJoin()
            lifecycleJob.cancelAndJoin()
            cancellationExecutor.shutdownNow()
            sourceDispatcher.close()
        }
    }

    @Test
    fun `mixed healthy and timed out callbacks preserve one candidate during blocked cleanup`() = runTest {
        val sourceDispatcher = UnconfinedTestDispatcher(testScheduler)
        val timeoutDispatcher = UnconfinedTestDispatcher(testScheduler)
        val blockedWorkerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val lifecycleJob = SupervisorJob()
        val lifecycleScope = CoroutineScope(lifecycleJob + UnconfinedTestDispatcher(testScheduler))
        val blockedEntered = CountDownLatch(1)
        val blockedReturned = CountDownLatch(1)
        val releaseBlockedSource = CountDownLatch(1)
        val healthySourceReturned = CountDownLatch(1)
        val activeWorkers = AtomicInteger()
        val sourceProviderCalls = AtomicInteger()
        val origin = Manga.create().copy(source = 8_812_000L, url = "/origin", ogTitle = "Origin")
        val healthySource = FakeSource(8_812_100L) {
            try {
                page(manga("/healthy-candidate", "Origin"))
            } finally {
                healthySourceReturned.countDown()
            }
        }
        val blockedSource = FakeSource(8_812_101L) {
            kotlinx.coroutines.withContext(blockedWorkerDispatcher) {
                activeWorkers.incrementAndGet()
                try {
                    blockedEntered.countDown()
                    awaitReleaseUninterruptibly(releaseBlockedSource)
                    page(manga("/blocked-candidate", "Origin"))
                } finally {
                    activeWorkers.decrementAndGet()
                    blockedReturned.countDown()
                }
            }
        }
        val liveSearch = newLiveSearch(
            scope = lifecycleScope,
            sourceDispatcher = sourceDispatcher,
            timeoutDispatcher = timeoutDispatcher,
            sourceProvider = {
                sourceProviderCalls.incrementAndGet()
                listOf(healthySource, blockedSource)
            },
            maxSources = 2,
        )
        val caller = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(origin) }

        try {
            assertTrue(blockedEntered.await(5, TimeUnit.SECONDS), "The timeout source did not enter the real worker")
            assertTrue(
                healthySourceReturned.await(5, TimeUnit.SECONDS),
                "The immediate healthy source did not return before the virtual timeout advance",
            )
            runCurrent()
            val producer = lifecycleJob.children.single()
            val producerFinished = CountDownLatch(1)
            producer.invokeOnCompletion { producerFinished.countDown() }

            advanceTimeBy(20_000L)
            runCurrent()

            val terminal = caller.await()
            assertEquals(2, terminal.size)
            val candidate = terminal.filterIsInstance<AlternateSourceReaderLiveCandidateRead.Candidate>().single()
            assertEquals(8_812_100L, candidate.manga.source)
            assertEquals("/healthy-candidate", candidate.manga.url)
            assertEquals("Origin", candidate.manga.ogTitle)
            assertEquals(1, terminal.count { it == AlternateSourceReaderLiveCandidateRead.SourceFailure })
            val terminalSnapshot = terminal.toList()
            assertFalse(producer.isCompleted, "The terminal snapshot must publish before blocked cleanup")
            assertEquals(1, activeWorkers.get())
            assertEquals(terminal, liveSearch.search(origin), "Same-origin calls must reuse the immutable snapshot")
            assertEquals(1, sourceProviderCalls.get(), "Same-origin reuse must not start a second producer")

            releaseBlockedSource.countDown()
            assertTrue(blockedReturned.await(5, TimeUnit.SECONDS), "The timed-out source did not return after release")
            assertTrue(producerFinished.await(5, TimeUnit.SECONDS), "The producer did not finish after source cleanup")
            assertEquals(0, activeWorkers.get())
            assertEquals(terminalSnapshot, terminal, "Late source return must not change the published result list")
            assertEquals(1, sourceProviderCalls.get(), "Cleanup must not relaunch the source provider")
        } finally {
            releaseBlockedSource.countDown()
            runCatching { liveSearch.cancel() }
            caller.cancelAndJoin()
            lifecycleJob.cancelAndJoin()
            blockedWorkerDispatcher.close()
        }
    }

    @Test
    fun `parent cancellation retains blocked producer slot until source cleanup`() = runTest {
        val sourceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val timeoutDispatcher = UnconfinedTestDispatcher(testScheduler)
        val lifecycleJob = SupervisorJob()
        val lifecycleScope = CoroutineScope(lifecycleJob + UnconfinedTestDispatcher(testScheduler))
        val sourceEntered = CountDownLatch(1)
        val sourceReturned = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val activeWorkers = AtomicInteger()
        val sourceProviderCalls = AtomicInteger()
        val origin = Manga.create().copy(source = 8_813_000L, url = "/origin", ogTitle = "Origin")
        val otherOrigin = Manga.create().copy(source = 8_813_001L, url = "/other", ogTitle = "Origin")
        val blockedSource = FakeSource(8_813_100L) {
            activeWorkers.incrementAndGet()
            try {
                sourceEntered.countDown()
                awaitReleaseUninterruptibly(releaseSource)
                page(manga("/candidate", "Origin"))
            } finally {
                activeWorkers.decrementAndGet()
                sourceReturned.countDown()
            }
        }
        val liveSearch = newLiveSearch(
            scope = lifecycleScope,
            sourceDispatcher = sourceDispatcher,
            timeoutDispatcher = timeoutDispatcher,
            sourceProvider = {
                sourceProviderCalls.incrementAndGet()
                listOf(blockedSource)
            },
            maxSources = 1,
        )
        val caller = async(UnconfinedTestDispatcher(testScheduler)) { liveSearch.search(origin) }
        val callerFinished = CountDownLatch(1)
        val callerCause = AtomicReference<Throwable?>()
        caller.invokeOnCompletion { cause ->
            callerCause.set(cause)
            callerFinished.countDown()
        }

        try {
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS), "The parent-cancel source did not block on the real worker")
            val producer = lifecycleJob.children.single()
            val producerFinished = CountDownLatch(1)
            producer.invokeOnCompletion { producerFinished.countDown() }
            val parentFinished = CountDownLatch(1)
            lifecycleJob.invokeOnCompletion { parentFinished.countDown() }

            lifecycleJob.cancel()

            assertTrue(lifecycleJob.isCancelled)
            assertFalse(lifecycleJob.isCompleted, "The lifecycle parent must wait for its blocked producer")
            assertTrue(producer.isCancelled)
            assertFalse(producer.isCompleted, "The producer must remain alive until blocked source cleanup")
            assertEquals(1, activeWorkers.get())
            assertEquals(
                listOf(AlternateSourceReaderLiveCandidateRead.SourceFailure),
                liveSearch.search(otherOrigin),
                "A disposed lifecycle must retain the active single-flight slot until producer completion",
            )
            assertEquals(1, sourceProviderCalls.get(), "Parent cancellation must not start another source wave")

            releaseSource.countDown()
            assertTrue(sourceReturned.await(5, TimeUnit.SECONDS), "The canceled source did not return after release")
            assertTrue(producerFinished.await(5, TimeUnit.SECONDS), "The canceled producer did not finish after cleanup")
            assertTrue(parentFinished.await(5, TimeUnit.SECONDS), "The lifecycle parent did not finish after producer cleanup")
            assertTrue(callerFinished.await(5, TimeUnit.SECONDS), "Cancellation did not propagate to the gateway caller")
            assertTrue(callerCause.get() is kotlinx.coroutines.CancellationException)
            assertEquals(0, activeWorkers.get())
        } finally {
            releaseSource.countDown()
            caller.cancelAndJoin()
            lifecycleJob.cancelAndJoin()
            sourceDispatcher.close()
        }
    }

    private fun newLiveSearch(
        scope: CoroutineScope,
        sourceDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        timeoutDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        sourceProvider: () -> List<Source>,
        maxSources: Int,
    ): AlternateSourceReaderLiveSearch {
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any(), true) } coAnswers { arg<List<Manga>>(0) }
        val identityDecisions = mockk<GetCrossSourceIdentityDecisions>()
        coEvery { identityDecisions.await(any()) } returns null
        val searcher = SameMangaCandidateSearcher(
            sourcePreferences = sourcePreferences,
            sourceManager = sourceManager,
            networkToLocalManga = NetworkToLocalManga(repository),
            coroutineDispatcher = sourceDispatcher,
            getIdentityDecisions = identityDecisions,
            timeoutDispatcher = timeoutDispatcher,
        )
        return AlternateSourceReaderLiveSearch(
            scope = scope,
            searcher = searcher,
            settings = SameMangaMatchSettings(
                resultsPerSource = SameMangaMatchSettings.DEFAULT_RESULT_CAP,
                preselectResults = false,
                previewSampleSize = SameMangaMatchSettings.DEFAULT_SAMPLE_SIZE,
                avoidFirstPages = true,
            ),
            sourceProvider = sourceProvider,
            maxSources = maxSources,
        )
    }

    private fun recordFailure(sourceId: Long) {
        SourceRuntimeFailureRegistry.record(
            SourceRuntimeFailure(
                sourceId = sourceId,
                sourceName = "Fixture $sourceId",
                sourceLang = "en",
                operation = SourceRuntimeOperation.Search,
                kind = SourceRuntimeFailureKind.Internal,
                throwable = IllegalStateException("controlled prior failure"),
            ),
        )
    }

    private fun awaitReleaseUninterruptibly(release: CountDownLatch) {
        while (release.count > 0) {
            try {
                release.await()
            } catch (_: InterruptedException) {
                // Keep the fake extension blocked until its explicit release latch opens.
            }
        }
    }

    private class FakeSource(
        override val id: Long,
        private val search: suspend () -> MangasPage,
    ) : Source {
        override val lang: String = "en"
        override val name: String = "Fixture $id"
        override val supportsLatest: Boolean = false

        override suspend fun getPopularManga(page: Int) = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = search()
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

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }

    private fun page(vararg mangas: SManga) = MangasPage(mangas.toList(), false)

    private fun observeProducerCompletion(scope: CoroutineScope): CountDownLatch {
        val activeProducers = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        check(activeProducers.size <= 1) { "The adapter started more than one producer" }
        return CountDownLatch(activeProducers.size).also { finished ->
            activeProducers.singleOrNull()?.invokeOnCompletion { finished.countDown() }
        }
    }
}
