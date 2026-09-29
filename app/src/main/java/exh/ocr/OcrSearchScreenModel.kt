package exh.ocr

import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.UUID

// KMK --> OCR v0.1.1 (updated from v0.1.0)

class OcrSearchScreenModel(
    private val context: Context,
    private val repository: OcrIndexRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val persistedWorkInfoProvider: suspend (Context) -> androidx.work.WorkInfo? = {
        OcrIndexWorker.getPersistedWorkInfo(it)
    },
) : StateScreenModel<OcrSearchScreenModel.State>(State()) {

    enum class ErrorAction {
        DISMISS,
        RETRY_SEARCH,
    }

    private val engineVersion = MlKitLatinOcrTextRecognizer.ENGINE_VERSION

    data class State(
        val query: String = "",
        val results: List<OcrSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val indexStats: OcrIndexStats = OcrIndexStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
        val indexProgress: OcrIndexProgress? = null,
        val isIndexRunning: Boolean = false,
        val showClearConfirm: Boolean = false,
        val showIndexAllConfirm: Boolean = false,
        val showForceReindexConfirm: Boolean = false,
        val maxPages: Int = 0,
        // KMK v0.7.46: typed key instead of raw exception text — OcrSearchScreen maps this to a
        // KMR string at render time.
        val errorKey: OcrErrorKey? = null,
        val errorAction: ErrorAction = ErrorAction.DISMISS,
    )

    private var searchJob: Job? = null
    private var statsRefreshJob: Job? = null
    private val backgroundJobs = mutableListOf<Job>()
    private val operationJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private var lastTerminalWorkId: UUID? = null

    private fun launchOperation(block: suspend () -> Unit): Job {
        val job = screenModelScope.launchIO { block() }
        operationJobs += job
        job.invokeOnCompletion { operationJobs -= job }
        return job
    }

    init {
        backgroundJobs += screenModelScope.launchIO {
            OcrJobState.isRunning.collectLatest { running ->
                mutableState.update { it.copy(isIndexRunning = running) }
            }
        }
        backgroundJobs += screenModelScope.launchIO {
            OcrJobState.activeProgress.collectLatest { progress ->
                mutableState.update { it.copy(indexProgress = progress) }
            }
        }
        // WorkManager survives process death; reattach the screen to its persisted unique work
        // instead of trusting the process-local OcrJobState after recreation.
        backgroundJobs += screenModelScope.launchIO {
            while (currentCoroutineContext().isActive) {
                try {
                    val info = persistedWorkInfoProvider(context)
                    currentCoroutineContext().ensureActive()
                    if (info == null) {
                        // A successful query with no unique work is authoritative. Clear any
                        // process-local state left behind by a previous screen instance.
                        lastTerminalWorkId = null
                        mutableState.update { it.copy(indexProgress = null, isIndexRunning = false) }
                    } else {
                        val progress = OcrIndexWorker.progressFromWorkInfo(info)
                        mutableState.update {
                            it.copy(
                                indexProgress = progress,
                                isIndexRunning = progress.isRunning,
                            )
                        }
                        val terminal = !progress.isRunning &&
                            (progress.isComplete || progress.isFailed || progress.isCancelled)
                        if (terminal && lastTerminalWorkId != info.id) {
                            lastTerminalWorkId = info.id
                            refreshStats()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the current state when WorkManager is temporarily unavailable.
                }
                delay(750)
            }
        }
        refreshStats()
    }

    fun refreshStats() {
        statsRefreshJob?.cancel()
        statsRefreshJob = screenModelScope.launchIO {
            try {
                val stats = repository.getStats(engineVersion)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(indexStats = stats) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // non-fatal
            }
        }
    }

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query, errorKey = null, errorAction = ErrorAction.DISMISS) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = screenModelScope.launchIO {
            mutableState.update { it.copy(isSearching = true) }
            try {
                val rawResults = repository.searchSmart(query, engineVersion)
                currentCoroutineContext().ensureActive()
                // Resolve source names
                val results = rawResults.map { result ->
                    val name = sourceManager.get(result.sourceId)?.name
                    result.copy(sourceName = name)
                }
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(results = results, isSearching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(
                        isSearching = false,
                        errorKey = OcrErrorClassifier.classify(e),
                        errorAction = ErrorAction.RETRY_SEARCH,
                    )
                }
            }
        }
    }

    fun retrySearch() {
        val query = state.value.query
        if (query.isNotBlank()) onQueryChange(query)
    }

    // Index all (retry empty/failed by default in v0.1.1)
    fun requestIndexAll() {
        mutableState.update { it.copy(showIndexAllConfirm = true) }
    }

    fun dismissIndexAllConfirm() {
        mutableState.update { it.copy(showIndexAllConfirm = false) }
    }

    fun startIndexAll() {
        mutableState.update { it.copy(showIndexAllConfirm = false) }
        if (!OcrIndexWorker.isRunning(context)) {
            OcrIndexWorker.start(
                context,
                OcrScope.AllDownloaded,
                retryMode = OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED,
                maxPages = state.value.maxPages,
            )
        }
    }

    // Force re-index all
    fun requestForceReindex() {
        mutableState.update { it.copy(showForceReindexConfirm = true) }
    }

    fun dismissForceReindexConfirm() {
        mutableState.update { it.copy(showForceReindexConfirm = false) }
    }

    fun startForceReindex() {
        mutableState.update { it.copy(showForceReindexConfirm = false) }
        if (!OcrIndexWorker.isRunning(context)) {
            OcrIndexWorker.start(
                context,
                OcrScope.AllDownloaded,
                retryMode = OcrRetryMode.FORCE_ALL,
                maxPages = state.value.maxPages,
            )
        }
    }

    fun cancelIndexing() {
        OcrIndexWorker.cancel(context)
    }

    // Page limit
    fun setMaxPages(limit: Int) {
        mutableState.update { it.copy(maxPages = limit) }
    }

    // Clear index
    fun requestClearIndex() {
        mutableState.update { it.copy(showClearConfirm = true) }
    }

    fun dismissClearConfirm() {
        mutableState.update { it.copy(showClearConfirm = false) }
    }

    fun clearIndex() {
        mutableState.update { it.copy(showClearConfirm = false) }
        launchOperation {
            try {
                repository.deleteAll()
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(
                        results = emptyList(),
                        indexStats = OcrIndexStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(errorKey = OcrErrorClassifier.classify(e), errorAction = ErrorAction.DISMISS) }
            }
        }
    }

    fun clearOldEngineRows() {
        launchOperation {
            try {
                repository.deleteOldEngineRows(engineVersion)
                currentCoroutineContext().ensureActive()
                refreshStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(errorKey = OcrErrorClassifier.classify(e), errorAction = ErrorAction.DISMISS) }
            }
        }
    }

    // KMK --> v0.7.46 Phase 3: per-manga/per-chapter OCR deletion, reachable from the result row menu
    fun deleteOcrForManga(mangaId: Long) {
        launchOperation {
            try {
                repository.deleteByManga(mangaId)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(results = it.results.filterNot { r -> r.mangaId == mangaId }) }
                refreshStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(errorKey = OcrErrorClassifier.classify(e), errorAction = ErrorAction.DISMISS) }
            }
        }
    }

    fun deleteOcrForChapter(chapterId: Long) {
        launchOperation {
            try {
                repository.deleteByChapter(chapterId)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(results = it.results.filterNot { r -> r.chapterId == chapterId }) }
                refreshStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(errorKey = OcrErrorClassifier.classify(e), errorAction = ErrorAction.DISMISS) }
            }
        }
    }

    fun clearEmptyAndFailed() {
        launchOperation {
            try {
                repository.deleteEmptyAndFailed()
                currentCoroutineContext().ensureActive()
                refreshStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(errorKey = OcrErrorClassifier.classify(e), errorAction = ErrorAction.DISMISS) }
            }
        }
    }
    // KMK <--

    fun clearError() {
        mutableState.update { it.copy(errorKey = null, errorAction = ErrorAction.DISMISS) }
    }

    override fun onDispose() {
        super.onDispose()
        searchJob?.cancel()
        searchJob = null
        statsRefreshJob?.cancel()
        statsRefreshJob = null
        backgroundJobs.forEach { it.cancel() }
        backgroundJobs.clear()
        operationJobs.toList().forEach { it.cancel() }
        operationJobs.clear()
    }
}

// KMK <--
