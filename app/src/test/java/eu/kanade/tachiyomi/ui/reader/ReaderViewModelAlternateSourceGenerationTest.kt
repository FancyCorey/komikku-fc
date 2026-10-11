package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.RecordLocalTrackedChapterProgress
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.source.SourceRuntimeFailure
import eu.kanade.tachiyomi.source.SourceRuntimeFailureKind
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.SourceRuntimeOperation
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderLoadState
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderPhase
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderPresentation
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderRecoveryReason
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderRouteRole
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.recs.matching.ConfirmedGroupLocalTrackingPropagator
import exh.recs.matching.ConfirmedMangaGroupTargets
import exh.util.FakePreferenceStore
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetAlternateSourceBridge
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.ReplaceAlternateSourceBridge
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.SetMangaTasteBatch
import tachiyomi.domain.taste.model.AlternateSourceBridge
import tachiyomi.domain.taste.model.AlternateSourceBridgeKey
import tachiyomi.domain.taste.model.AlternateSourceBridgeMapping
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingKey
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingRelation
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingState
import tachiyomi.domain.taste.model.AlternateSourceBridgePolicy
import tachiyomi.domain.taste.model.AlternateSourceBridgeState
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Regression coverage for actual-ReaderViewModel chooser ownership and explicit Retry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelAlternateSourceGenerationTest {

    private val store = ViewModelStore()

    @Test
    fun publicRetryReachesSourceAfterOrdinaryChooserOpenRemainsSuppressed() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        SourceRuntimeFailureRegistry.clear(CANDIDATE_SOURCE_ID)
        try {
            fixture = fixture()
            val current = requireNotNull(fixture)
            current.releaseSearch.countDown()
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val sourceCalls = AtomicInteger()
            coEvery { current.candidateSource.getSearchManga(any(), any(), any<FilterList>()) } coAnswers {
                if (sourceCalls.incrementAndGet() == 1) throw IllegalStateException("controlled source failure")
                MangasPage(emptyList(), hasNextPage = false)
            }

            var start = current.chapterReads.size
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = true)
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first {
                    (it as? AlternateSourceReaderPresentation.ChoosingSource)?.content is AlternateSourceReaderLoadState.Failed
                }
            }
            assertEquals(1, sourceCalls.get())
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(start).take(2))

            start = current.chapterReads.size
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = true)
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first {
                    (it as? AlternateSourceReaderPresentation.ChoosingSource)?.content is AlternateSourceReaderLoadState.Failed
                }
            }
            assertEquals("ordinary chooser open must not bypass SourceRuntime suppression", 1, sourceCalls.get())
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(start).take(2))

            start = current.chapterReads.size
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first {
                    (it as? AlternateSourceReaderPresentation.ChoosingSource)?.content === AlternateSourceReaderLoadState.Empty
                }
            }
            assertEquals("explicit Retry must invoke the primitive source again", 2, sourceCalls.get())
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(start).take(2))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
        } finally {
            fixture?.releaseSearch?.countDown()
            try {
                runCurrent()
                store.clear()
            } finally {
                SourceRuntimeFailureRegistry.clear(CANDIDATE_SOURCE_ID)
                ReaderViewModelTestBindings.restore()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun publicRetryRecoversRepositoryFailureWithSameChapterBounds() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        SourceRuntimeFailureRegistry.clear(CANDIDATE_SOURCE_ID)
        try {
            fixture = fixture()
            val current = requireNotNull(fixture)
            current.releaseSearch.countDown()
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val linkReads = AtomicInteger()
            coEvery { current.getLinks.awaitAll() } coAnswers {
                if (linkReads.incrementAndGet() == 1) throw IllegalStateException("controlled repository failure")
                emptyList()
            }
            val sourceCalls = AtomicInteger()
            coEvery { current.candidateSource.getSearchManga(any(), any(), any<FilterList>()) } coAnswers {
                sourceCalls.incrementAndGet()
                MangasPage(emptyList(), hasNextPage = false)
            }

            var start = current.chapterReads.size
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = true)
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first { it is AlternateSourceReaderPresentation.RecoverableFailure }
            }
            assertEquals(1, linkReads.get())
            assertEquals(0, sourceCalls.get())
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(start).take(2))

            start = current.chapterReads.size
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first {
                    (it as? AlternateSourceReaderPresentation.ChoosingSource)?.content === AlternateSourceReaderLoadState.Empty
                }
            }
            assertEquals(2, linkReads.get())
            assertEquals(1, sourceCalls.get())
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(start).take(2))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
        } finally {
            fixture?.releaseSearch?.countDown()
            try {
                runCurrent()
                store.clear()
            } finally {
                SourceRuntimeFailureRegistry.clear(CANDIDATE_SOURCE_ID)
                ReaderViewModelTestBindings.restore()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun dismissedChooserStaysHiddenWhenBlockedSameTitleSearchReturnsLate() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        try {
            fixture = fixture()
            val current = requireNotNull(fixture)
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val existingJobs = current.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = true)
            runCurrent()
            assertTrue(awaitReal(3_000) { current.searchEntered.await(3, TimeUnit.SECONDS) })
            val oldJobs = current.viewModel.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it in existingJobs }
                .toList()
            assertTrue("dismissed chooser must own newly created work", oldJobs.isNotEmpty())

            current.viewModel.dismissAlternateSourcePresentation()
            assertSame(AlternateSourceReaderPresentation.Hidden, current.viewModel.alternateSourcePresentation.value)
            current.releaseSearch.countDown()
            assertTrue(awaitReal(3_000) { current.searchReturned.await(3, TimeUnit.SECONDS) })
            assertTrue(current.searchReturnedNormally.get())
            awaitReal(3_000) { oldJobs.joinAll() }

            assertSame(AlternateSourceReaderPresentation.Hidden, current.viewModel.alternateSourcePresentation.value)
            current.viewModel.confirmAlternateSourceCandidate()
            assertSame(AlternateSourceReaderPresentation.Hidden, current.viewModel.alternateSourcePresentation.value)
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
        } finally {
            fixture?.releaseSearch?.countDown()
            try {
                runCurrent()
            } finally {
                try {
                    store.clear()
                } finally {
                    try {
                        ReaderViewModelTestBindings.restore()
                    } finally {
                        Dispatchers.resetMain()
                    }
                }
            }
        }
    }

    @Test
    fun supersededLiveChooserCannotOverwriteCurrentEmptyChooser() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        try {
            fixture = fixture()
            val current = requireNotNull(fixture)
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val existingJobs = current.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = true)
            runCurrent()
            assertTrue(awaitReal(3_000) { current.searchEntered.await(3, TimeUnit.SECONDS) })
            val oldJobs = current.viewModel.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it in existingJobs }
                .toList()
            assertTrue("blocked old chooser must own newly created work", oldJobs.isNotEmpty())

            // Keep the requests observably different: the replacement reads only known stores,
            // which are empty here, while the old live-search producer remains blocked.
            current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID, includeLiveSearch = false)
            runCurrent()
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first {
                    val chooser = it as? AlternateSourceReaderPresentation.ChoosingSource
                    chooser?.content === AlternateSourceReaderLoadState.Empty
                }
            }
            assertSame(
                AlternateSourceReaderLoadState.Empty,
                (current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.ChoosingSource).content,
            )
            assertEquals("old source producer is still blocked", 1L, current.searchReturned.count)

            current.releaseSearch.countDown()
            assertTrue(awaitReal(3_000) { current.searchReturned.await(3, TimeUnit.SECONDS) })
            assertTrue(current.searchReturnedNormally.get())
            awaitReal(3_000) { oldJobs.joinAll() }

            val chooser = current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.ChoosingSource
            assertSame(AlternateSourceReaderLoadState.Empty, chooser.content)
            assertEquals(1, current.searchCalls.get())
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
        } finally {
            fixture?.releaseSearch?.countDown()
            try {
                runCurrent()
            } finally {
                try {
                    store.clear()
                } finally {
                    try {
                        ReaderViewModelTestBindings.restore()
                    } finally {
                        Dispatchers.resetMain()
                    }
                }
            }
        }
    }

    @Test
    fun retainedMangaPublicRetryResetsOnlyTargetAndReusesTheSameChoice() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val sourceCalls = AtomicInteger()
            val sourceRequests = CopyOnWriteArrayList<Triple<String, Boolean, Boolean>>()
            val linkReads = AtomicInteger()
            coEvery { current.getLinks.awaitAll() } coAnswers {
                linkReads.incrementAndGet()
                emptyList()
            }
            coEvery { current.candidateSource.getMangaUpdate(any(), any(), false, true) } coAnswers {
                sourceRequests += Triple(firstArg<SManga>().url, arg<Boolean>(2), arg<Boolean>(3))
                if (sourceCalls.incrementAndGet() == 1) throw IllegalStateException("controlled retained chapter failure")
                assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
                SMangaUpdate(firstArg(), listOf(sourceChapter(CANDIDATE_CHAPTER_URL, "Chapter 1", 1f)))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val boundsStart = current.chapterReads.size
            val chooser = chooseRetainedManga(current)
            assertEquals(listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID), current.chapterReads.drop(boundsStart).take(2))
            recordRetainedFailure(ORIGIN_SOURCE_ID)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val originFailure = SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)

            var existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceCandidate()
            val failed = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, existingJobs)
            assertTrue(failed.content is AlternateSourceReaderLoadState.Failed)
            assertEquals(chooser.selectedToken, failed.sourceToken)
            assertEquals(1, sourceCalls.get())
            val targetFailure = requireNotNull(SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))

            existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceCandidate()
            assertTrue(awaitChapterChoice(current).content is AlternateSourceReaderLoadState.Failed)
            awaitNewWorkCompletion(current, existingJobs)
            assertEquals("ordinary selection stays suppressed", 1, sourceCalls.get())
            assertEquals(targetFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))

            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            val retried = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, existingJobs)
            assertTrue(retried.content is AlternateSourceReaderLoadState.Content)
            assertEquals("Retry keeps the retained manga token", failed.sourceToken, retried.sourceToken)
            assertEquals(2, sourceCalls.get())
            assertEquals(List(2) { Triple(CANDIDATE_MANGA_URL, false, true) }, sourceRequests.toList())
            assertEquals("retained Retry must not reopen discovery", 1, linkReads.get())
            assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(originFailure, SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun retainedMangaPublicRetryWaitsForCancelledProducerCleanupBeforeResetting() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        val chapterEntered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupExited = CountDownLatch(1)
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val sourceCalls = AtomicInteger()
            coEvery { current.candidateSource.getMangaUpdate(any(), any(), false, true) } coAnswers {
                assertEquals(CANDIDATE_MANGA_URL, firstArg<SManga>().url)
                if (sourceCalls.incrementAndGet() == 1) {
                    chapterEntered.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupEntered.countDown()
                            try {
                                check(releaseCleanup.await(5, TimeUnit.SECONDS)) { "test did not release retained producer cleanup" }
                            } finally {
                                cleanupExited.countDown()
                            }
                        }
                    }
                }
                assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
                SMangaUpdate(firstArg(), listOf(sourceChapter(CANDIDATE_CHAPTER_URL, "Chapter 1", 1f)))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            val chooser = chooseRetainedManga(current)
            val existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceCandidate()
            assertTrue(awaitReal(3_000) { chapterEntered.await(3, TimeUnit.SECONDS) })
            val ownedJobs = scopeJobs(current).filterNot { it in existingJobs }
            assertEquals("the chapter discovery owns one VM producer", 1, ownedJobs.size)
            ownedJobs.forEach { it.cancel() }
            assertTrue(awaitReal(3_000) { cleanupEntered.await(3, TimeUnit.SECONDS) })
            assertTrue(ownedJobs.all { it.isCancelled && !it.isCompleted })
            recordRetainedFailure(CANDIDATE_SOURCE_ID)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val targetFailure = SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)
            val retainedPresentation = current.viewModel.alternateSourcePresentation.value
            val retainedJobs = scopeJobs(current)

            current.viewModel.retryAlternateSourcePresentation()
            runCurrent()
            assertSame(retainedPresentation, current.viewModel.alternateSourcePresentation.value)
            assertEquals(chooser.selectedToken, (retainedPresentation as AlternateSourceReaderPresentation.ChoosingChapter).sourceToken)
            assertEquals(retainedJobs, scopeJobs(current))
            assertEquals(1, sourceCalls.get())
            assertEquals(targetFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertEquals(1L, cleanupExited.count)

            releaseCleanup.countDown()
            assertTrue(awaitReal(3_000) { cleanupExited.await(3, TimeUnit.SECONDS) })
            awaitReal(3_000) { ownedJobs.joinAll() }
            assertTrue(ownedJobs.all(Job::isCompleted))
            val beforeRetry = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            val retried = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, beforeRetry)
            assertTrue(retried.content is AlternateSourceReaderLoadState.Content)
            assertEquals(chooser.selectedToken, retried.sourceToken)
            assertEquals(2, sourceCalls.get())
            assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
        } finally {
            releaseCleanup.countDown()
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun retainedChapterPublicRetryComposesAdmittedAlternateAndPrimarySourceResets() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val alternatePageCalls = AtomicInteger()
            val primaryPageCalls = AtomicInteger()
            coEvery { current.candidateSource.getPageList(any()) } coAnswers {
                alternatePageCalls.incrementAndGet()
                assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
                listOf(Page(index = 0, url = "page://candidate"))
            }
            coEvery { current.originSource.getPageList(any()) } coAnswers {
                primaryPageCalls.incrementAndGet()
                assertEquals(null, SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID))
                listOf(Page(index = 0, url = "page://origin"))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            chooseRetainedManga(current)
            var existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceCandidate()
            val alternateChoice = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, existingJobs)
            val alternateRow = (alternateChoice.content as AlternateSourceReaderLoadState.Content).items.single()
            current.viewModel.selectAlternateSourceChapter(alternateRow.token)
            recordRetainedFailure(CANDIDATE_SOURCE_ID)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val alternateFailure = SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)
            // Exercise the retained chapter's public Retry directly from the selected choice.
            assertEquals(alternateFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(0, alternatePageCalls.get())
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)

            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourceReaderState.first { it.session?.currentRoute?.role == AlternateSourceReaderRouteRole.ALTERNATE }
            }
            awaitNewWorkCompletion(current, existingJobs)
            assertEquals(CANDIDATE_MANGA_ID, current.viewModel.manga?.id)
            assertEquals(CANDIDATE_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertEquals(1, alternatePageCalls.get())
            assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)

            existingJobs = scopeJobs(current)
            current.viewModel.requestReturnToPrimarySource()
            val primaryChoice = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, existingJobs)
            val primaryRow = (primaryChoice.content as AlternateSourceReaderLoadState.Content).items.single()
            current.viewModel.selectAlternateSourceChapter(primaryRow.token)
            recordRetainedFailure(ORIGIN_SOURCE_ID)
            recordRetainedFailure(CANDIDATE_SOURCE_ID)
            val primaryFailure = SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID)
            val alternateSentinel = SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID)
            coEvery { current.getChapter.await(current.originChapters[0].url, MANGA_ID) } returns null
            existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceChapterSelection()
            awaitNewWorkCompletion(current, existingJobs)
            val returnFailure = current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.RecoverableFailure
            assertEquals(AlternateSourceReaderRecoveryReason.ROUTE_UNAVAILABLE, returnFailure.reason)
            assertTrue(returnFailure.canRetry)
            assertFalse(returnFailure.invalidateTokens)
            assertEquals(primaryFailure, SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID))
            assertEquals(alternateSentinel, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(CANDIDATE_MANGA_ID, current.viewModel.manga?.id)

            coEvery { current.getChapter.await(current.originChapters[0].url, MANGA_ID) } returns current.originChapters[0]
            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourceReaderState.first { it.session?.currentRoute?.role == AlternateSourceReaderRouteRole.PRIMARY }
            }
            awaitNewWorkCompletion(current, existingJobs)
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertEquals(2, primaryPageCalls.get())
            assertEquals(null, SourceRuntimeFailureRegistry.get(ORIGIN_SOURCE_ID))
            assertEquals(alternateSentinel, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun retainedChapterPublicRetryPreservesSuppressionWhenUnconfirmedOrStale() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val alternatePageCalls = AtomicInteger()
            coEvery { current.candidateSource.getPageList(any()) } coAnswers {
                alternatePageCalls.incrementAndGet()
                listOf(Page(index = 0, url = "page://candidate"))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            chooseRetainedManga(current)
            var existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceCandidate()
            val choice = awaitChapterChoice(current)
            awaitNewWorkCompletion(current, existingJobs)
            current.viewModel.selectAlternateSourceChapter((choice.content as AlternateSourceReaderLoadState.Content).items.single().token)
            recordRetainedFailure(CANDIDATE_SOURCE_ID)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val targetFailure = SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)
            val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(retainedBridgeKey().primary, retainedBridgeKey().alternate)
            val confirmation = ReaderViewModelTestBindings.getIdentityDecisions.await(pair)
            coEvery { ReaderViewModelTestBindings.getIdentityDecisions.await(pair) } returns null
            existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceChapterSelection()
            awaitNewWorkCompletion(current, existingJobs)
            assertTrue(current.viewModel.alternateSourcePresentation.value is AlternateSourceReaderPresentation.ConfirmPair)
            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitNewWorkCompletion(current, existingJobs)
            assertTrue(current.viewModel.alternateSourcePresentation.value is AlternateSourceReaderPresentation.ConfirmPair)
            assertEquals(targetFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)

            coEvery { ReaderViewModelTestBindings.getIdentityDecisions.await(pair) } returns confirmation
            assertEquals(SECOND_CHAPTER_ID, current.viewModel.state.value.viewerChapters?.prevChapter?.chapter?.id)
            current.viewModel.loadPreviousChapter()
            awaitReal(5_000) { current.viewModel.state.first { it.viewerChapters?.currChapter?.chapter?.id == SECOND_CHAPTER_ID } }
            assertEquals(SECOND_CHAPTER_ID, current.viewModel.currentChapter?.id)
            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitNewWorkCompletion(current, existingJobs)
            val stale = current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.RecoverableFailure
            assertEquals(AlternateSourceReaderRecoveryReason.INVALID_SESSION, stale.reason)
            assertTrue(stale.invalidateTokens)
            assertEquals(0, alternatePageCalls.get())
            assertEquals(targetFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(SECOND_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun retainedChapterPublicRetryRecoversPostAdmissionResolutionFailureWithSameChoice() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val chapterResolutions = AtomicInteger()
            val pageCalls = AtomicInteger()
            coEvery { current.getChapter.await(CANDIDATE_CHAPTER_URL, CANDIDATE_MANGA_ID) } coAnswers {
                if (chapterResolutions.incrementAndGet() == 1) null else retainedCandidateChapter()
            }
            coEvery { current.candidateSource.getPageList(any()) } coAnswers {
                pageCalls.incrementAndGet()
                assertEquals(CANDIDATE_CHAPTER_URL, firstArg<SChapter>().url)
                assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
                listOf(Page(index = 0, url = "page://resolved-retry"))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            chooseRetainedChapter(current)
            recordRetainedFailure(CANDIDATE_SOURCE_ID)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val targetFailure = SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)
            var existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceChapterSelection()
            awaitNewWorkCompletion(current, existingJobs)
            val failed = current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.RecoverableFailure
            assertEquals(AlternateSourceReaderRecoveryReason.ROUTE_UNAVAILABLE, failed.reason)
            assertTrue(failed.canRetry)
            assertFalse(failed.invalidateTokens)
            assertEquals(AlternateSourceReaderPhase.DEGRADED, current.viewModel.alternateSourceReaderState.value.phase)
            assertEquals(null, current.viewModel.alternateSourceReaderState.value.session)
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
            assertEquals(1, chapterResolutions.get())
            assertEquals(0, pageCalls.get())
            assertEquals(targetFailure, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))

            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourceReaderState.first { it.phase == AlternateSourceReaderPhase.ALTERNATE }
            }
            awaitNewWorkCompletion(current, existingJobs)
            assertEquals(2, chapterResolutions.get())
            assertEquals(1, pageCalls.get())
            assertEquals(0, current.searchCalls.get())
            assertEquals(CANDIDATE_MANGA_ID, current.viewModel.manga?.id)
            assertEquals(CANDIDATE_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertEquals(RETAINED_TARGET_ID, current.viewModel.alternateSourceReaderState.value.session?.activeTargetId)
            assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun retainedChapterPublicRetryRecoversFailedRouteReplacementWithSameChoice() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture()
            val current = requireNotNull(fixture)
            val pageCalls = AtomicInteger()
            val requestedChapters = CopyOnWriteArrayList<String>()
            coEvery { current.candidateSource.getPageList(any()) } coAnswers {
                requestedChapters += firstArg<SChapter>().url
                if (pageCalls.incrementAndGet() == 1) throw IllegalStateException("controlled initial route replacement failure")
                assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
                listOf(Page(index = 0, url = "page://replacement-retry"))
            }
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            chooseRetainedChapter(current)
            recordRetainedFailure(SENTINEL_SOURCE_ID)
            val sentinelFailure = SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID)
            var existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceChapterSelection()
            awaitReal(5_000) {
                current.viewModel.alternateSourcePresentation.first { it is AlternateSourceReaderPresentation.RecoverableFailure }
            }
            awaitNewWorkCompletion(current, existingJobs)
            val failed = current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.RecoverableFailure
            assertEquals(AlternateSourceReaderRecoveryReason.FAILED, failed.reason)
            assertTrue(failed.canRetry)
            assertFalse(failed.invalidateTokens)
            val safeSession = requireNotNull(current.viewModel.alternateSourceReaderState.value.session)
            assertEquals(AlternateSourceReaderPhase.DEGRADED, current.viewModel.alternateSourceReaderState.value.phase)
            assertEquals(AlternateSourceReaderRouteRole.PRIMARY, safeSession.currentRoute.role)
            assertEquals(FIRST_CHAPTER_ID, safeSession.currentRoute.chapterId)
            assertEquals(1, safeSession.failedResolutionCount)
            assertEquals(null, safeSession.pendingRouteFingerprint)
            assertEquals(MANGA_ID, current.viewModel.manga?.id)
            assertEquals(FIRST_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
            assertEquals(1, pageCalls.get())
            assertTrue(SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID) != null)

            existingJobs = scopeJobs(current)
            current.viewModel.retryAlternateSourcePresentation()
            awaitReal(5_000) {
                current.viewModel.alternateSourceReaderState.first { it.phase == AlternateSourceReaderPhase.ALTERNATE }
            }
            awaitNewWorkCompletion(current, existingJobs)
            val committed = requireNotNull(current.viewModel.alternateSourceReaderState.value.session)
            assertEquals(safeSession.sessionId, committed.sessionId)
            assertEquals(RETAINED_TARGET_ID, committed.activeTargetId)
            assertEquals(0, committed.failedResolutionCount)
            assertEquals(2, pageCalls.get())
            assertEquals(List(2) { CANDIDATE_CHAPTER_URL }, requestedChapters.toList())
            assertEquals(0, current.searchCalls.get())
            assertEquals(CANDIDATE_MANGA_ID, current.viewModel.manga?.id)
            assertEquals(CANDIDATE_CHAPTER_ID, current.viewModel.currentChapter?.id)
            assertEquals(null, SourceRuntimeFailureRegistry.get(CANDIDATE_SOURCE_ID))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(SENTINEL_SOURCE_ID))
            assertTrue(current.viewModel.state.value.viewerChapters?.currChapter?.state is ReaderChapter.State.Loaded)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    @Test
    fun chapterChooserConfirmationAcceptsProvisionalAlignmentWithoutSecondPrompt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
        try {
            fixture = retainedFixture(mappingState = AlternateSourceBridgeMappingState.PROVISIONAL)
            val current = requireNotNull(fixture)
            ReaderViewModelTestBindings.allowBridgeReplacement()
            assertTrue(current.viewModel.init(MANGA_ID, FIRST_CHAPTER_ID, page = null).getOrThrow())
            chooseRetainedChapter(current)
            assertTrue(current.viewModel.alternateSourcePresentation.value is AlternateSourceReaderPresentation.ChoosingChapter)

            val existingJobs = scopeJobs(current)
            current.viewModel.confirmAlternateSourceChapterSelection()
            awaitReal(5_000) {
                current.viewModel.alternateSourceReaderState.first { it.phase == AlternateSourceReaderPhase.ALTERNATE }
            }
            awaitNewWorkCompletion(current, existingJobs)

            assertEquals(AlternateSourceReaderPresentation.Hidden, current.viewModel.alternateSourcePresentation.value)
            assertEquals(AlternateSourceReaderPhase.ALTERNATE, current.viewModel.alternateSourceReaderState.value.phase)
            assertEquals(AlternateSourceReaderRouteRole.ALTERNATE, current.viewModel.alternateSourceReaderState.value.session?.currentRoute?.role)
            assertEquals(CANDIDATE_CHAPTER_ID, current.viewModel.currentChapter?.id)
        } finally {
            closeRetainedFixture(fixture)
        }
    }

    private suspend fun chooseRetainedChapter(current: Fixture) {
        chooseRetainedManga(current)
        val existingJobs = scopeJobs(current)
        current.viewModel.confirmAlternateSourceCandidate()
        val choice = awaitChapterChoice(current)
        awaitNewWorkCompletion(current, existingJobs)
        current.viewModel.selectAlternateSourceChapter((choice.content as AlternateSourceReaderLoadState.Content).items.single().token)
    }

    private fun retainedFixture(
        mappingState: AlternateSourceBridgeMappingState = AlternateSourceBridgeMappingState.CONFIRMED,
    ): Fixture {
        val current = fixture()
        val candidateManga = current.originManga.copy(
            id = CANDIDATE_MANGA_ID,
            source = CANDIDATE_SOURCE_ID,
            url = CANDIDATE_MANGA_URL,
        )
        val candidateChapter = retainedCandidateChapter()
        every { current.sourceManager.getOrStub(CANDIDATE_SOURCE_ID) } returns current.candidateSource
        coEvery { current.getManga.await(CANDIDATE_MANGA_ID) } returns candidateManga
        coEvery { current.getManga.await(CANDIDATE_MANGA_URL, CANDIDATE_SOURCE_ID) } returns candidateManga
        coEvery { current.getManga.await(ORIGIN_MANGA_URL, ORIGIN_SOURCE_ID) } returns current.originManga
        coEvery { current.getChapter.await(CANDIDATE_CHAPTER_ID) } returns candidateChapter
        coEvery { current.getChapter.await(CANDIDATE_CHAPTER_URL, CANDIDATE_MANGA_ID) } returns candidateChapter
        current.originChapters.forEach { chapter ->
            coEvery { current.getChapter.await(chapter.url, MANGA_ID) } returns chapter
        }
        coEvery { current.getChaptersByMangaId.await(CANDIDATE_MANGA_ID, any()) } returns listOf(candidateChapter)
        coEvery { ReaderViewModelTestBindings.getMangaByUrlAndSourceId.await(CANDIDATE_MANGA_URL, CANDIDATE_SOURCE_ID) } returns candidateManga
        coEvery { ReaderViewModelTestBindings.getMangaByUrlAndSourceId.await(ORIGIN_MANGA_URL, ORIGIN_SOURCE_ID) } returns current.originManga

        val now = System.currentTimeMillis() - 1_000L
        val state = AlternateSourceBridgeState(
            bridge = AlternateSourceBridge(
                key = retainedBridgeKey(),
                version = AlternateSourceBridgePolicy.CURRENT_VERSION,
                continuationPrimaryChapterUrl = current.originChapters[1].url,
                returnAfterAlternateChapterUrl = CANDIDATE_CHAPTER_URL,
                createdAt = now,
                updatedAt = now,
            ),
            mappings = listOf(
                AlternateSourceBridgeMapping(
                    key = AlternateSourceBridgeMappingKey(retainedBridgeKey(), RETAINED_TARGET_ID),
                    alternateChapterUrl = CANDIDATE_CHAPTER_URL,
                    precedingPrimaryChapterUrl = current.originChapters[0].url,
                    followingPrimaryChapterUrl = current.originChapters[1].url,
                    relation = AlternateSourceBridgeMappingRelation.PRIMARY_MISSING,
                    state = mappingState,
                    version = AlternateSourceBridgePolicy.CURRENT_VERSION,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        coEvery { ReaderViewModelTestBindings.getAlternateSourceBridge.await(retainedBridgeKey()) } returns state
        coEvery { ReaderViewModelTestBindings.getAlternateSourceBridge.awaitAllBridges() } returns listOf(state.bridge)
        coEvery { ReaderViewModelTestBindings.getAlternateSourceBridge.awaitAllMappings() } returns state.mappings
        val confirmation = CrossSourceIdentityDecisionPolicy.userDecision(
            CrossSourceIdentityDecisionPolicy.canonicalPair(retainedBridgeKey().primary, retainedBridgeKey().alternate),
            CrossSourceIdentityDecisionValue.USER_CONFIRMED,
            previous = null,
            timestamp = now,
        )
        coEvery { ReaderViewModelTestBindings.getIdentityDecisions.await(confirmation.pair) } returns confirmation
        coEvery { ReaderViewModelTestBindings.getIdentityDecisions.awaitAll() } returns listOf(confirmation)
        coEvery { current.candidateSource.getMangaUpdate(any(), any(), false, true) } coAnswers {
            SMangaUpdate(firstArg(), listOf(sourceChapter(CANDIDATE_CHAPTER_URL, "Chapter 1", 1f)))
        }
        coEvery { current.candidateSource.getPageList(any()) } returns listOf(Page(index = 0, url = "page://candidate"))
        coEvery { current.originSource.getMangaUpdate(any(), any(), false, true) } coAnswers {
            // A distinct number keeps the public return path on the explicit chapter chooser.
            SMangaUpdate(firstArg(), listOf(sourceChapter(current.originChapters[0].url, "Return special", 3f)))
        }
        current.viewModel.viewModelScope.launch { current.viewModel.eventFlow.collect {} }
        current.releaseSearch.countDown()
        return current
    }

    private fun retainedBridgeKey() = AlternateSourceBridgeKey(
        primary = CrossSourceRecordKey(ORIGIN_SOURCE_ID, ORIGIN_MANGA_URL),
        alternate = CrossSourceRecordKey(CANDIDATE_SOURCE_ID, CANDIDATE_MANGA_URL),
    )

    private fun retainedCandidateChapter() = Chapter.create().copy(
        id = CANDIDATE_CHAPTER_ID,
        mangaId = CANDIDATE_MANGA_ID,
        sourceOrder = 1,
        url = CANDIDATE_CHAPTER_URL,
        name = "Chapter 1",
        chapterNumber = 1.0,
    )

    private fun sourceChapter(url: String, name: String, number: Float) = SChapter.create().apply {
        this.url = url
        this.name = name
        chapter_number = number
    }

    private suspend fun chooseRetainedManga(current: Fixture): AlternateSourceReaderPresentation.ChoosingSource {
        val existingJobs = scopeJobs(current)
        current.viewModel.openAlternateSourceChooser(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID)
        val chooser = awaitReal(5_000) {
            current.viewModel.alternateSourcePresentation.first {
                (it as? AlternateSourceReaderPresentation.ChoosingSource)?.content is AlternateSourceReaderLoadState.Content
            }
        } as AlternateSourceReaderPresentation.ChoosingSource
        awaitNewWorkCompletion(current, existingJobs)
        val row = (chooser.content as AlternateSourceReaderLoadState.Content).items.single()
        current.viewModel.toggleAlternateSourceCandidate(row.token)
        return current.viewModel.alternateSourcePresentation.value as AlternateSourceReaderPresentation.ChoosingSource
    }

    private suspend fun awaitChapterChoice(current: Fixture): AlternateSourceReaderPresentation.ChoosingChapter =
        awaitReal(5_000) {
            current.viewModel.alternateSourcePresentation.first {
                val choice = it as? AlternateSourceReaderPresentation.ChoosingChapter
                choice != null && choice.content !== AlternateSourceReaderLoadState.Loading
            }
        } as AlternateSourceReaderPresentation.ChoosingChapter

    private fun scopeJobs(current: Fixture): Set<Job> =
        current.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

    private suspend fun awaitNewWorkCompletion(current: Fixture, existingJobs: Set<Job>) {
        val owned = scopeJobs(current).filterNot { it in existingJobs }
        if (owned.isNotEmpty()) awaitReal(5_000) { owned.joinAll() }
    }

    private suspend fun closeRetainedFixture(current: Fixture?) {
        current?.releaseSearch?.countDown()
        val jobs = current?.let(::scopeJobs).orEmpty().toList()
        try {
            store.clear()
            if (jobs.isNotEmpty()) awaitReal(5_000) { jobs.joinAll() }
        } finally {
            try {
                listOf(ORIGIN_SOURCE_ID, CANDIDATE_SOURCE_ID, SENTINEL_SOURCE_ID).forEach(SourceRuntimeFailureRegistry::clear)
                ReaderViewModelTestBindings.restore()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private fun recordRetainedFailure(sourceId: Long) {
        SourceRuntimeFailureRegistry.record(
            SourceRuntimeFailure(
                sourceId = sourceId,
                sourceName = "Retained fixture $sourceId",
                sourceLang = "en",
                operation = SourceRuntimeOperation.MangaUpdate,
                kind = SourceRuntimeFailureKind.Internal,
                throwable = IllegalStateException("controlled retained failure"),
            ),
        )
    }

    private fun fixture(): Fixture {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferenceStore = FakePreferenceStore()
        val readerPreferences = ReaderPreferences(preferenceStore)
        val sourcePreferences = SourcePreferences(preferenceStore)
        ReaderViewModelTestBindings.install(application, readerPreferences, sourcePreferences)
        val manga = Manga.create().copy(
            id = MANGA_ID,
            source = ORIGIN_SOURCE_ID,
            url = ORIGIN_MANGA_URL,
            ogTitle = CANDIDATE_TITLE,
        )
        val chapters = listOf(
            Chapter.create().copy(
                id = FIRST_CHAPTER_ID,
                mangaId = MANGA_ID,
                sourceOrder = 1,
                url = "/chapter/1",
                name = "Chapter 1",
                chapterNumber = 1.0,
            ),
            Chapter.create().copy(
                id = SECOND_CHAPTER_ID,
                mangaId = MANGA_ID,
                sourceOrder = 2,
                url = "/chapter/2",
                name = "Chapter 2",
                chapterNumber = 2.0,
            ),
        )
        val originSource = httpSource(ORIGIN_SOURCE_ID, "Origin")
        val candidateSource = httpSource(CANDIDATE_SOURCE_ID, "Candidate")
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(ORIGIN_SOURCE_ID) } returns originSource
            every { get(ORIGIN_SOURCE_ID) } returns originSource
            every { get(CANDIDATE_SOURCE_ID) } returns candidateSource
            every { getVisibleSources() } returns listOf(candidateSource)
        }
        val searchEntered = CountDownLatch(1)
        val releaseSearch = CountDownLatch(1)
        val searchReturned = CountDownLatch(1)
        val searchReturnedNormally = AtomicBoolean(false)
        val searchCalls = AtomicInteger()
        val candidate = SManga.create().apply {
            title = CANDIDATE_TITLE
            url = CANDIDATE_MANGA_URL
        }
        coEvery { candidateSource.getSearchManga(any(), any(), any<FilterList>()) } coAnswers {
            searchCalls.incrementAndGet()
            searchEntered.countDown()
            try {
                check(releaseSearch.await(5, TimeUnit.SECONDS)) { "test did not release the controlled source search" }
                val result = MangasPage(listOf(candidate), hasNextPage = false)
                searchReturnedNormally.set(true)
                result
            } finally {
                searchReturned.countDown()
            }
        }
        coEvery { originSource.getPageList(any()) } returns listOf(Page(index = 0, url = "page://one"))
        val getManga = mockk<GetManga>(relaxed = true)
        coEvery { getManga.await(MANGA_ID) } returns manga
        val getChapter = mockk<GetChapter>(relaxed = true)
        val chapterReads = CopyOnWriteArrayList<Long>()
        coEvery { getChapter.await(FIRST_CHAPTER_ID) } coAnswers {
            chapterReads += FIRST_CHAPTER_ID
            chapters[0]
        }
        coEvery { getChapter.await(SECOND_CHAPTER_ID) } coAnswers {
            chapterReads += SECOND_CHAPTER_ID
            chapters[1]
        }
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true)
        coEvery { getChaptersByMangaId.await(MANGA_ID, any()) } returns chapters
        val chapterCache = ReaderViewModelTestBindings.chapterCache
        every { chapterCache.getPageListFromCache(any()) } throws IllegalStateException("synthetic page-list cache miss")
        val getLinks = mockk<GetCrossSourceMangaLinks>(relaxed = true)

        val viewModel = ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = sourceManager,
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            tempFileManager = mockk<UniFileTempFileManager>(relaxed = true),
            imageSaver = mockk<ImageSaver>(relaxed = true),
            readerPreferences = readerPreferences,
            basePreferences = BasePreferences(application, preferenceStore),
            downloadPreferences = DownloadPreferences(preferenceStore),
            trackPreferences = TrackPreferences(preferenceStore),
            trackChapter = mockk<TrackChapter>(relaxed = true),
            recordLocalTrackedChapterProgress = mockk<RecordLocalTrackedChapterProgress>(relaxed = true),
            getManga = getManga,
            updateManga = mockk<UpdateManga>(relaxed = true),
            getChapter = getChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getNextChapters = mockk<GetNextChapters>(relaxed = true),
            upsertHistory = mockk<UpsertHistory>(relaxed = true),
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true),
            getIncognitoState = mockk<GetIncognitoState>(relaxed = true),
            libraryPreferences = LibraryPreferences(preferenceStore),
            syncPreferences = SyncPreferences(preferenceStore),
            uiPreferences = UiPreferences(preferenceStore),
            getFlatMetadataById = mockk<GetFlatMetadataById>(relaxed = true),
            getMergedMangaById = mockk<GetMergedMangaById>(relaxed = true),
            getMergedReferencesById = mockk<GetMergedReferencesById>(relaxed = true),
            getMergedChaptersByMangaId = mockk<GetMergedChaptersByMangaId>(relaxed = true),
            setMangaTasteBatch = mockk<SetMangaTasteBatch>(relaxed = true),
            getCrossSourceMangaLinks = getLinks,
            sourcePreferencesForRatingPrompt = sourcePreferences,
            getMangaTasteForRatingPrompt = mockk<GetMangaTaste>(relaxed = true),
            confirmedMangaGroupTargets = mockk<ConfirmedMangaGroupTargets>(relaxed = true),
            localTrackerRepository = mockk<LocalTrackerRepository>(relaxed = true),
            confirmedGroupLocalTrackingPropagator = mockk<ConfirmedGroupLocalTrackingPropagator>(relaxed = true),
        )
        store.put("reader", viewModel)
        return Fixture(
            viewModel,
            searchEntered,
            releaseSearch,
            searchReturned,
            searchReturnedNormally,
            searchCalls,
            candidateSource,
            chapterReads,
            getLinks,
            manga,
            chapters,
            originSource,
            getManga,
            getChapter,
            getChaptersByMangaId,
            sourceManager,
        )
    }

    private fun httpSource(id: Long, name: String): HttpSource = mockk<HttpSource>(relaxed = true).also { source ->
        every { source.id } returns id
        every { source.name } returns name
        every { source.lang } returns "en"
        every { source.supportsLatest } returns false
    }

    private suspend fun <T> awaitReal(timeoutMillis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) { block() }
        }

    private data class Fixture(
        val viewModel: ReaderViewModel,
        val searchEntered: CountDownLatch,
        val releaseSearch: CountDownLatch,
        val searchReturned: CountDownLatch,
        val searchReturnedNormally: AtomicBoolean,
        val searchCalls: AtomicInteger,
        val candidateSource: HttpSource,
        val chapterReads: CopyOnWriteArrayList<Long>,
        val getLinks: GetCrossSourceMangaLinks,
        val originManga: Manga,
        val originChapters: List<Chapter>,
        val originSource: HttpSource,
        val getManga: GetManga,
        val getChapter: GetChapter,
        val getChaptersByMangaId: GetChaptersByMangaId,
        val sourceManager: SourceManager,
    )

    private object ReaderViewModelTestBindings {
        val chapterCache = mockk<ChapterCache>(relaxed = true)
        val getAlternateSourceBridge = mockk<GetAlternateSourceBridge>(relaxed = true)
        private val replaceAlternateSourceBridge = mockk<ReplaceAlternateSourceBridge>(relaxed = true)
        val getMangaByUrlAndSourceId = mockk<GetMangaByUrlAndSourceId>(relaxed = true)
        val getIdentityDecisions = mockk<GetCrossSourceIdentityDecisions>(relaxed = true)
        private val replaceIdentityDecisions = mockk<ReplaceCrossSourceIdentityDecisions>(relaxed = true)
        private val networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true)
        private val syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true)
        private var previousScope: InjektScope? = null

        fun install(
            application: Application,
            readerPreferences: ReaderPreferences,
            sourcePreferences: SourcePreferences,
        ) {
            check(previousScope == null) { "ReaderViewModel Injekt scope already installed" }
            clearMocks(
                getAlternateSourceBridge,
                replaceAlternateSourceBridge,
                getMangaByUrlAndSourceId,
                getIdentityDecisions,
                replaceIdentityDecisions,
                networkToLocalManga,
                syncChaptersWithSource,
                chapterCache,
            )
            previousScope = Injekt
            val isolatedScope = InjektScope(DefaultRegistrar())
            coEvery { networkToLocalManga.invoke(any<List<Manga>>(), any()) } answers { firstArg() }
            isolatedScope.importModule(
                object : InjektModule {
                    override fun InjektRegistrar.registerInjectables() {
                        addSingletonFactory<Application> { application }
                        addSingletonFactory<ChapterCache> { chapterCache }
                        addSingletonFactory<GetAlternateSourceBridge> { getAlternateSourceBridge }
                        addSingletonFactory<ReplaceAlternateSourceBridge> { replaceAlternateSourceBridge }
                        addSingletonFactory<GetMangaByUrlAndSourceId> { getMangaByUrlAndSourceId }
                        addSingletonFactory<GetCrossSourceIdentityDecisions> { getIdentityDecisions }
                        addSingletonFactory<ReplaceCrossSourceIdentityDecisions> { replaceIdentityDecisions }
                        addSingletonFactory<NetworkToLocalManga> { networkToLocalManga }
                        addSingletonFactory<SyncChaptersWithSource> { syncChaptersWithSource }
                        addSingletonFactory<ReaderPreferences> { readerPreferences }
                        addSingletonFactory<SourcePreferences> { sourcePreferences }
                    }
                },
            )
            Injekt = isolatedScope
        }

        fun restore() {
            val original = previousScope ?: return
            try {
                Injekt = original
            } finally {
                previousScope = null
            }
        }

        fun allowBridgeReplacement() {
            coEvery { replaceAlternateSourceBridge.await(any()) } returns true
        }
    }

    private companion object {
        const val MANGA_ID = 81_021L
        const val FIRST_CHAPTER_ID = 81_022L
        const val SECOND_CHAPTER_ID = 81_023L
        const val ORIGIN_SOURCE_ID = 81_024L
        const val CANDIDATE_SOURCE_ID = 81_025L
        const val CANDIDATE_TITLE = "Controlled Reader Candidate"
        const val ORIGIN_MANGA_URL = "/origin-reader"
        const val CANDIDATE_MANGA_URL = "/candidate-reader"
        const val CANDIDATE_MANGA_ID = 81_026L
        const val CANDIDATE_CHAPTER_ID = 81_027L
        const val SENTINEL_SOURCE_ID = 81_028L
        const val CANDIDATE_CHAPTER_URL = "/candidate/chapter/1"
        const val RETAINED_TARGET_ID = "223e4567-e89b-42d3-a456-426614174000"
    }
}
