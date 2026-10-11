package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import exh.recs.matching.CrossExtensionMatchQueryPlanner
import exh.recs.matching.SameMangaCandidateResult
import exh.recs.matching.SameMangaCandidateSearcher
import exh.recs.matching.SameMangaMatchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga

/**
 * Owns one live candidate search per reader gateway. Candidate visibility follows the searcher's
 * per-source terminal callbacks; the producer slot remains occupied until its aggregate job exits.
 */
internal class AlternateSourceReaderLiveSearch(
    private val scope: CoroutineScope,
    private val searcher: SameMangaCandidateSearcher,
    private val settings: SameMangaMatchSettings,
    private val sourceProvider: () -> List<Source>,
    private val maxSources: Int,
) {
    private data class OriginKey(val source: Long, val url: String)

    private class Run(
        val origin: OriginKey,
        val sourceIds: Set<Long>,
        val explicitRetry: Boolean,
    ) {
        val completedSourceIds = mutableSetOf<Long>()
        val results = mutableListOf<AlternateSourceReaderLiveCandidateRead>()
        val terminal = CompletableDeferred<List<AlternateSourceReaderLiveCandidateRead>>()
        var cancelled = false
        var job: Job? = null
    }

    private sealed interface Plan {
        data class Join(val run: Run) : Plan
        data class AwaitCleanup(val run: Run) : Plan
        data class Immediate(val results: List<AlternateSourceReaderLiveCandidateRead>) : Plan
    }

    private val lock = Any()
    private var activeRun: Run? = null

    suspend fun search(
        origin: Manga,
        explicitRetry: Boolean = false,
    ): List<AlternateSourceReaderLiveCandidateRead> {
        val originKey = OriginKey(origin.source, origin.url)
        val plan = synchronized(lock) {
            if (activeRun?.job?.isCompleted == true) activeRun = null
            val existing = activeRun
            when {
                existing != null && existing.origin == originKey && explicitRetry && !existing.explicitRetry -> {
                    Plan.AwaitCleanup(existing)
                }
                existing != null && existing.origin == originKey && !existing.cancelled -> Plan.Join(existing)
                existing != null -> Plan.Immediate(listOf(AlternateSourceReaderLiveCandidateRead.SourceFailure))
                else -> {
                    val sources = sourceProvider().take(maxSources)
                    if (sources.isEmpty()) {
                        Plan.Immediate(emptyList())
                    } else {
                        val run = Run(originKey, sources.mapTo(mutableSetOf(), Source::id), explicitRetry)
                        activeRun = run
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            produce(run, origin, sources)
                        }
                        run.job = job
                        job.invokeOnCompletion { cause -> onProducerCompleted(run, cause) }
                        job.start()
                        Plan.Join(run)
                    }
                }
            }
        }
        return when (plan) {
            is Plan.Immediate -> plan.results
            is Plan.Join -> plan.run.terminal.await()
            is Plan.AwaitCleanup -> {
                plan.run.job?.join()
                currentCoroutineContext().ensureActive()
                search(origin, explicitRetry = true)
            }
        }
    }

    fun cancel() {
        val job = synchronized(lock) {
            activeRun?.also { it.cancelled = true }?.job
        }
        job?.cancel()
    }

    private suspend fun produce(run: Run, origin: Manga, sources: List<Source>) {
        try {
            if (run.explicitRetry) {
                currentCoroutineContext().ensureActive()
                val admitted = synchronized(lock) {
                    if (activeRun !== run || run.cancelled || run.terminal.isCompleted) {
                        false
                    } else {
                        run.sourceIds.forEach(SourceRuntimeFailureRegistry::clear)
                        true
                    }
                }
                if (!admitted) return
            }
            searcher.search(
                queries = CrossExtensionMatchQueryPlanner.buildQueries(origin),
                settings = settings,
                originManga = origin,
                sources = sources,
            ) { sourceResult ->
                record(run, sourceResult)
            }
        } catch (error: CancellationException) {
            run.terminal.completeExceptionally(error)
            throw error
        } catch (error: Exception) {
            completeMissingSources(run)
        } catch (error: Error) {
            run.terminal.completeExceptionally(error)
            throw error
        } finally {
            completeMissingSources(run)
        }
    }

    private fun record(run: Run, sourceResult: exh.recs.matching.SameMangaSourceResult) {
        synchronized(lock) {
            val sourceId = sourceResult.source.id
            if (
                activeRun !== run ||
                run.cancelled ||
                run.terminal.isCompleted ||
                sourceId !in run.sourceIds ||
                sourceId in run.completedSourceIds
            ) {
                return
            }
            when (val result = sourceResult.result) {
                SameMangaCandidateResult.Loading -> return
                is SameMangaCandidateResult.Error -> run.results += AlternateSourceReaderLiveCandidateRead.SourceFailure
                is SameMangaCandidateResult.Success -> {
                    run.results += result.results.map(AlternateSourceReaderLiveCandidateRead::Candidate)
                }
            }
            run.completedSourceIds += sourceId
            if (run.completedSourceIds.size == run.sourceIds.size) {
                run.terminal.complete(run.results.toList())
            }
        }
    }

    private fun completeMissingSources(run: Run) {
        synchronized(lock) {
            if (run.cancelled || run.terminal.isCompleted) return
            val missing = run.sourceIds - run.completedSourceIds
            repeat(missing.size) {
                run.results += AlternateSourceReaderLiveCandidateRead.SourceFailure
            }
            run.completedSourceIds += missing
            run.terminal.complete(run.results.toList())
        }
    }

    private fun onProducerCompleted(run: Run, cause: Throwable?) {
        if (cause is CancellationException) {
            run.terminal.completeExceptionally(cause)
        } else if (cause != null) {
            completeMissingSources(run)
        }
        synchronized(lock) {
            if (activeRun === run) activeRun = null
        }
    }
}
