package exh.ocr

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

class OcrSearchScreenModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search resolves source names and keeps result metadata`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val source = mockk<Source> {
            every { name } returns "Fixture source"
        }
        val result = result(chapterId = 20L, sourceId = 7L)
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.searchSmart("dragon", any()) } returns listOf(result)
        every { sourceManager.get(7L) } returns source
        val model = OcrSearchScreenModel(mockk<Context>(relaxed = true), repository, sourceManager)

        try {
            model.onQueryChange("dragon")
            val state = awaitState(model) { !it.isSearching && it.results.isNotEmpty() }

            assertEquals("Fixture source", state.results.single().sourceName)
            assertEquals(20L, state.results.single().chapterId)
            assertEquals("dragon", state.query)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `deleting a chapter removes only that chapter from visible results`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.deleteByChapter(20L) } returns Unit
        val model = OcrSearchScreenModel(mockk<Context>(relaxed = true), repository, mockk(relaxed = true))

        try {
            model.forceResults(listOf(result(20L), result(21L)))
            model.deleteOcrForChapter(20L)
            val state = awaitState(model) { it.results.size == 1 }

            assertEquals(21L, state.results.single().chapterId)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `deletion failure exposes a typed error without raw exception text`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.deleteByChapter(20L) } throws IllegalStateException("/private/path/page.jpg")
        val model = OcrSearchScreenModel(mockk<Context>(relaxed = true), repository, mockk(relaxed = true))

        try {
            model.deleteOcrForChapter(20L)
            val state = awaitState(model) { it.errorKey != null }

            assertEquals(OcrErrorKey.Internal, state.errorKey)
            assertTrue(state.errorKey!!.storageKey.startsWith("ocr_error_"))
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `disposing the model cancels an in-flight search`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.searchSmart("dragon", any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
        )

        model.onQueryChange("dragon")
        withTimeout(5_000) { entered.await() }
        model.onDispose()

        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun `disposing the model suppresses a late search result after cancellation is ignored`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.searchSmart("dragon", any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit)
            listOf(result(chapterId = 20L))
        }
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
        )

        try {
            model.onQueryChange("dragon")
            withTimeout(5_000) { entered.await() }
            model.onDispose()
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }

            assertTrue(model.state.value.results.isEmpty())
        } finally {
            model.onDispose()
            release.complete(Unit)
        }
    }

    @Test
    fun `disposing the model cancels an in-flight stats refresh`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getStats(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
        )

        withTimeout(5_000) { entered.await() }
        model.onDispose()

        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun `disposing the model stops observer state updates`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        coEvery { repository.getStats(any()) } returns emptyStats()
        OcrJobState.isRunning.value = true
        OcrJobState.activeProgress.value = OcrIndexProgress(isRunning = true)
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
        )

        try {
            withTimeout(5_000) {
                model.state.first { it.isIndexRunning && it.indexProgress?.isRunning == true }
            }
            model.onDispose()
            OcrJobState.isRunning.value = false
            OcrJobState.activeProgress.value = null
            delay(100)

            assertTrue(model.state.value.isIndexRunning)
            assertTrue(model.state.value.indexProgress?.isRunning == true)
        } finally {
            model.onDispose()
            OcrJobState.isRunning.value = false
            OcrJobState.activeProgress.value = null
        }
    }

    @Test
    fun `disposing the model cancels persisted work polling`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getStats(any()) } returns emptyStats()
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
            persistedWorkInfoProvider = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )

        try {
            withTimeout(5_000) { entered.await() }
            model.onDispose()
            withTimeout(5_000) { cancelled.await() }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `a successful empty persisted-work query clears stale process-local progress`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        coEvery { repository.getStats(any()) } returns emptyStats()
        OcrJobState.isRunning.value = true
        OcrJobState.activeProgress.value = OcrIndexProgress(isRunning = true)
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
            persistedWorkInfoProvider = { null },
        )

        try {
            val state = awaitState(model) { !it.isIndexRunning && it.indexProgress == null }
            assertTrue(!state.isIndexRunning)
            assertEquals(null, state.indexProgress)
        } finally {
            model.onDispose()
            OcrJobState.isRunning.value = false
            OcrJobState.activeProgress.value = null
        }
    }

    @Test
    fun `a persisted-work lookup failure preserves current progress`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        coEvery { repository.getStats(any()) } returns emptyStats()
        OcrJobState.isRunning.value = true
        OcrJobState.activeProgress.value = OcrIndexProgress(isRunning = true, totalPages = 3)
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
            persistedWorkInfoProvider = { throw IllegalStateException("temporary lookup failure") },
        )

        try {
            val state = awaitState(model) { it.isIndexRunning && it.indexProgress?.totalPages == 3 }
            assertTrue(state.isIndexRunning)
            assertEquals(3, state.indexProgress?.totalPages)
        } finally {
            model.onDispose()
            OcrJobState.isRunning.value = false
            OcrJobState.activeProgress.value = null
        }
    }

    @Test
    fun `disposing the model suppresses a late mutation after repository cancellation is ignored`() = runBlocking {
        val repository = mockk<OcrIndexRepository>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        coEvery { repository.getStats(any()) } returns emptyStats()
        coEvery { repository.deleteByChapter(20L) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit)
        }
        val model = OcrSearchScreenModel(
            mockk<Context>(relaxed = true),
            repository,
            mockk(relaxed = true),
        )

        try {
            model.forceResults(listOf(result(20L)))
            model.deleteOcrForChapter(20L)
            withTimeout(5_000) { entered.await() }
            model.onDispose()
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }

            assertEquals(20L, model.state.value.results.single().chapterId)
        } finally {
            model.onDispose()
            release.complete(Unit)
        }
    }

    private suspend fun awaitState(
        model: OcrSearchScreenModel,
        predicate: (OcrSearchScreenModel.State) -> Boolean,
    ): OcrSearchScreenModel.State = withTimeout(2.seconds) {
        model.state.filter(predicate).first()
    }

    private fun OcrSearchScreenModel.forceResults(results: List<OcrSearchResult>) {
        val field = cafe.adriel.voyager.core.model.StateScreenModel::class.java
            .getDeclaredField("mutableState")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val state = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<OcrSearchScreenModel.State>
        state.value = state.value.copy(results = results)
    }

    private fun result(chapterId: Long, sourceId: Long = 7L) = OcrSearchResult(
        mangaId = 1L,
        mangaTitle = "Dragon",
        sourceId = sourceId,
        sourceName = null,
        chapterId = chapterId,
        chapterName = "Chapter $chapterId",
        pageIndex = 0,
        snippet = "dragon",
        matchType = OcrMatchType.EXACT,
        matchedWords = listOf("dragon"),
        missingWords = emptyList(),
        matchScore = 1.0,
    )

    private fun emptyStats() = OcrIndexStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
}
