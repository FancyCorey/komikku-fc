package exh.recs.matching

// KMK --> v0.7.8
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntime
import eu.kanade.tachiyomi.source.SourceRuntimeOperation
import eu.kanade.tachiyomi.source.isRecoverableSourceRuntimeFailure
import eu.kanade.tachiyomi.source.unwrapSourceRuntimeCause
import exh.recs.RecommendationErrorClassifier
import exh.recs.RecommendationSourceFilter
import exh.recs.RecommendationSourceOrdering
import exh.recs.isRetryableForDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/** Search scope used by the shared candidate searcher.
 *
 * [MATCHING] is intentionally bounded and follows the recommendation source controls. [GLOBAL]
 * mirrors the app's normal global-search source set and first-page behavior. [GLOBAL_BOUNDED] uses
 * that global source set while retaining the configured per-source cap and candidate assessment.
 */
enum class SameMangaSearchScope {
    MATCHING,
    GLOBAL,
    GLOBAL_BOUNDED,
}

/**
 * Shared bounded same-manga candidate search used by
 * [CrossExtensionMatchScreenModel] and [BestVersionCompareScreenModel].
 *
 * MATCHING applies recommendation-language filtering and source priority ordering. GLOBAL uses
 * normal global-search source eligibility and retains full first-page results; GLOBAL_BOUNDED uses
 * that same source set and configured result cap for Best Version, retaining first-page candidates
 * for the explicit global-search surface to inspect manually. Every scope excludes the origin and
 * deduplicates across queries.
 */
class SameMangaCandidateSearcher(
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val coroutineDispatcher: CoroutineDispatcher,
    private val getIdentityDecisions: GetCrossSourceIdentityDecisions = Injekt.get(),
    private val timeoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun getMatchingSources(): List<Source> {
        val recLanguages = RecommendationSourceFilter.normalizeLanguages(
            sourcePreferences.recommendationSourceLanguages().get(),
        )
        val storedOrder = RecommendationSourceOrdering.parse(
            sourcePreferences.recommendationSourceOrder().get(),
        )
        val disabledSourceIds = sourcePreferences.disabledSources().get()
            .mapNotNull { it.toLongOrNull() }.toSet()
        val all = sourceManager.getVisibleSources()
        val filtered = RecommendationSourceFilter.filterForRecommendations(all, recLanguages)
        return RecommendationSourceOrdering.apply(filtered, storedOrder, disabledSourceIds)
    }

    /**
     * Returns the same source scope used by the app's normal global search: enabled languages,
     * disabled-source exclusions, and pinned-source ordering. Recommendation-language filters and
     * the recommendation priority list are deliberately not applied here.
     */
    fun getGlobalSearchSources(): List<Source> {
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledSources().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()
        return sourceManager.getVisibleSources()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase(java.util.Locale.ROOT)} (${it.lang})" },
                ),
            )
    }

    /**
     * Searches [sources] (or the source scope selected by [scope]) with [queries]. MATCHING and
     * GLOBAL_BOUNDED searches use the configured per-source cap; GLOBAL searches return the
     * complete first page from each source. The [originManga] is excluded from results. Returns one
     * [SameMangaSourceResult] per source.
     */
    suspend fun search(
        queries: List<String>,
        settings: SameMangaMatchSettings,
        originManga: Manga,
        sources: List<Source>? = null,
        scope: SameMangaSearchScope = SameMangaSearchScope.MATCHING,
        onResult: suspend (SameMangaSourceResult) -> Unit,
    ) = coroutineScope {
        val cap = when (scope) {
            SameMangaSearchScope.MATCHING,
            SameMangaSearchScope.GLOBAL_BOUNDED,
            -> SameMangaMatchSettings.clampResultCap(settings.resultsPerSource)
            // Normal global search renders the complete first page from each enabled source.
            SameMangaSearchScope.GLOBAL -> null
        }
        val searchSources = sources ?: when (scope) {
            SameMangaSearchScope.MATCHING -> getMatchingSources()
            SameMangaSearchScope.GLOBAL,
            SameMangaSearchScope.GLOBAL_BOUNDED,
            -> getGlobalSearchSources()
        }

        searchSources.map { source ->
            async {
                currentCoroutineContext().ensureActive()
                val timedOut = AtomicBoolean(false)
                val resultPublished = AtomicBoolean(false)
                val sourceSearch = async(start = CoroutineStart.LAZY) {
                    searchOneSource(
                        source = source,
                        queries = queries,
                        cap = cap,
                        originManga = originManga,
                        retainAllCandidates = scope != SameMangaSearchScope.MATCHING,
                    )
                }
                val timeoutJob = launch(timeoutDispatcher) {
                    delay(SOURCE_SEARCH_TIMEOUT_MS)
                    if (resultPublished.compareAndSet(false, true)) {
                        timedOut.set(true)
                        sourceSearch.cancel()
                        onResult(
                            SameMangaSourceResult(
                                source,
                                SameMangaCandidateResult.Error(
                                    java.util.concurrent.TimeoutException(
                                        "Candidate search timed out for ${source.id}",
                                    ),
                                ),
                            ),
                        )
                    }
                }
                sourceSearch.start()
                try {
                    val result = sourceSearch.await()
                    currentCoroutineContext().ensureActive()
                    if (resultPublished.compareAndSet(false, true)) {
                        onResult(SameMangaSourceResult(source, result))
                    }
                } catch (e: CancellationException) {
                    if (!timedOut.get()) throw e
                    currentCoroutineContext().ensureActive()
                } finally {
                    timeoutJob.cancel()
                }
            }
        }.awaitAll()
    }

    private suspend fun searchOneSource(
        source: Source,
        queries: List<String>,
        cap: Int?,
        originManga: Manga,
        retainAllCandidates: Boolean,
    ): SameMangaCandidateResult {
        // KMK v0.8.10-fix4: routed through SourceRuntime instead of a local
        // catch(Exception)/catch(Error) pair -- one shared boundary classifies both,
        // records a recoverable extension LinkageError in SourceRuntimeFailureRegistry,
        // and still always rethrows CancellationException and any genuinely fatal Error.
        // KMK v0.8.21-fix4: extracted to a local fun (rather than inlining the SourceRuntime.run
        // call at both the initial-attempt and retry sites below) so
        // SameMangaSearchOwnershipSourceTest's "exactly one Search-operation call site in this
        // file" source-guard stays satisfied by construction, not by convention.
        suspend fun attempt(query: String, bypassSuppression: Boolean = false) = SourceRuntime.run(
            source,
            SourceRuntimeOperation.Search,
            coroutineDispatcher,
            bypassSuppression = bypassSuppression,
        ) {
            getSearchManga(1, query.sanitize(), getFilterList())
        }
        return try {
            val seen = LinkedHashMap<CrossSourceRecordKey, Manga>()
            val confirmedKeys = mutableSetOf<MangaIdentityKey>()
            // KMK v0.8.10-fix3: widened from Exception? to Throwable? -- see the identical pattern
            // in CrossExtensionMatchScreenModel for the full reasoning.
            var lastError: Throwable? = null
            for (query in queries) {
                currentCoroutineContext().ensureActive()
                if (cap != null && seen.size >= cap) break
                var searchResult = attempt(query)
                // KMK v0.8.21-fix4: R2/AUG-05 correction -- the reproduced live failure was a
                // transient HTTP 502 during this exact discovery search with no retry of any kind,
                // so a source having one bad moment permanently failed for the whole comparison. A
                // single bounded retry of the *same* query against the *same* source (never another
                // source's request or another source's pages -- see SameMangaCandidateSearcherRetryTest
                // and Plan B's REOPENED fix-boundary constraint) is attempted only for the transient
                // error kinds (Network/Timeout/RateLimit/ServerError) that a second attempt could
                // plausibly succeed at; a terminal failure like Authentication is never retried since
                // repeating the identical request cannot change the outcome. ensureActive() first so
                // a cancelled/superseded search never issues a pointless extra network call.
                searchResult.exceptionOrNull()?.let { throwable ->
                    if (RecommendationErrorClassifier.classify(throwable).isRetryableForDiscovery()) {
                        currentCoroutineContext().ensureActive()
                        // KMK v0.8.21-fix5: R2 correction -- SourceRuntime.run() unconditionally
                        // suppresses a second call to the same source within its 60s failure window
                        // (SourceRuntimeFailureRegistry.SUPPRESSION_WINDOW_MS, enforced
                        // unconditionally since v0.8.10-fix8) -- without lifting it here, this retry
                        // would never actually reach the source at all. Previously this called
                        // SourceRuntimeFailureRegistry.clear(source.id), which is a process-global
                        // side effect: it silently lifted suppression for every other unrelated
                        // concurrent caller of the same source too, and made a second consecutive
                        // failure record as a misleadingly fresh single-failure entry instead of
                        // correctly extending the existing one. bypassSuppression = true authorizes
                        // exactly this one retry attempt without touching the registry at all -- a
                        // second failure now correctly increments the existing entry's count and
                        // preserves its original firstFailureAt, and no other caller's suppression
                        // window is ever affected by this retry decision.
                        searchResult = attempt(query, bypassSuppression = true)
                    }
                }
                searchResult.fold(
                    onSuccess = { page ->
                        val resolved = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }
                            .filterNot { it.source == originManga.source && it.url == originManga.url }
                        for (manga in resolved) {
                            if (cap != null && seen.size >= cap) break
                            val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(
                                CrossSourceRecordKey(originManga.source, originManga.url),
                                CrossSourceRecordKey(manga.source, manga.url),
                            )
                            val identityDecision = getIdentityDecisions.await(pair)
                            if (CrossSourceIdentityDecisionPolicy.isCurrentRejection(identityDecision)) {
                                continue
                            }
                            if (CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(identityDecision)) {
                                confirmedKeys += MangaIdentityKey(manga.source, manga.url)
                            }
                            seen.putIfAbsent(CrossSourceRecordKey(manga.source, manga.url), manga)
                        }
                    },
                    onFailure = { throwable -> lastError = throwable },
                )
            }
            when {
                seen.isNotEmpty() -> SameMangaCandidateEvidenceAttacher.attach(
                    origin = originManga,
                    candidates = seen.values.toList(),
                    confirmedKeys = confirmedKeys,
                    retainAllCandidates = retainAllCandidates,
                )
                lastError != null -> SameMangaCandidateResult.Error(lastError)
                else -> SameMangaCandidateResult.Success(emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SameMangaCandidateResult.Error(e)
        } catch (e: Error) {
            val unwrapped = e.unwrapSourceRuntimeCause()
            if (!unwrapped.isRecoverableSourceRuntimeFailure()) throw e
            SameMangaCandidateResult.Error(unwrapped)
        }
    }

    private companion object {
        private const val SOURCE_SEARCH_TIMEOUT_MS = 20_000L
    }
}
// KMK <--
