package eu.kanade.tachiyomi.ui.reader.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

// KMK v0.8.25 -->
/**
 * Asks the confirmed linked versions of the manga being read whether they have anything after the chapter just finished.
 *
 * Only versions the user has confirmed are asked (never a guess from a search), at most six, two at a time, each through the
 * source's own request limits and a timeout. A version that cannot answer is simply left out, so a failure never invents a
 * chapter and the screen stays what it was. The answer for one finished chapter is reused for half an hour.
 */
class AlternateSourceReaderAheadChecker internal constructor(
    private val discover: suspend (AlternateSourceReaderCandidateRequest) -> AlternateSourceReaderCandidateDiscovery,
    private val chapters: suspend (AlternateSourceReaderMangaCandidate) -> AlternateSourceReaderChapterDiscovery,
    private val now: () -> Long = System::currentTimeMillis,
    private val perVersionTimeoutMillis: Long = 20_000L,
) {

    data class Report(
        val offer: AlternateSourceReaderAheadPolicy.Offer,
        /** The newest chapter of every version that answered, ahead or not, by [keyOf]. */
        val latestByKey: Map<String, Float>,
        val aheadByKey: Map<String, AlternateSourceReaderAheadPolicy.Ahead>,
        val candidatesByKey: Map<String, AlternateSourceReaderMangaCandidate>,
    ) {
        companion object {
            val Empty = Report(AlternateSourceReaderAheadPolicy.Offer.None, emptyMap(), emptyMap(), emptyMap())
        }
    }

    private data class CacheKey(val mangaId: Long, val finishedChapterId: Long, val finishedChapterNumber: Float)

    private val cache = HashMap<CacheKey, Pair<Long, Report>>()

    suspend fun check(request: AlternateSourceReaderCandidateRequest, finishedChapterNumber: Float): Report {
        val cacheKey = CacheKey(
            request.primaryRoute.mangaId,
            request.precedingPrimaryChapterId,
            finishedChapterNumber,
        )
        synchronized(cache) {
            cache[cacheKey]?.takeIf { now() - it.first < AlternateSourceReaderAheadPolicy.CACHE_MILLIS }?.let { return it.second }
        }

        val candidates = try {
            discover(request).candidates
                .filter { candidate ->
                    !candidate.requiresPairConfirmation &&
                        (
                            candidate.origin == AlternateSourceReaderCandidateOrigin.CONFIRMED_PAIR ||
                                candidate.origin == AlternateSourceReaderCandidateOrigin.CURRENT_BRIDGE
                            )
                }
                .distinctBy { keyOf(it) }
                .take(AlternateSourceReaderAheadPolicy.MAX_VERSIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Report.Empty
        }
        if (candidates.isEmpty()) return Report.Empty.also { remember(cacheKey, it) }

        val gate = Semaphore(AlternateSourceReaderAheadPolicy.MAX_CONCURRENT)
        val answered = coroutineScope {
            candidates.map { candidate ->
                async {
                    gate.withPermit {
                        val result = try {
                            withTimeoutOrNull(perVersionTimeoutMillis) { chapters(candidate) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        (result as? AlternateSourceReaderChapterDiscovery.Available)?.let { available ->
                            candidate to AlternateSourceReaderAheadPolicy.VersionChapters(
                                key = keyOf(candidate),
                                chapterNumbers = available.chapters.map { it.chapterNumber },
                            )
                        }
                    }
                }
            }.mapNotNull { it.await() }
        }

        val versions = answered.map { it.second }
        val ahead = AlternateSourceReaderAheadPolicy.ahead(finishedChapterNumber, versions)
        val report = Report(
            offer = AlternateSourceReaderAheadPolicy.offer(ahead),
            latestByKey = AlternateSourceReaderAheadPolicy.latestByVersion(versions),
            aheadByKey = ahead.associateBy { it.key },
            candidatesByKey = answered.associate { (candidate, version) -> version.key to candidate },
        )
        remember(cacheKey, report)
        return report
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun remember(key: CacheKey, report: Report) = synchronized(cache) { cache[key] = now() to report }

    companion object {
        fun keyOf(candidate: AlternateSourceReaderMangaCandidate): String = "${candidate.manga.source}:${candidate.manga.url}"
    }
}

/** The answer for one finished chapter, so a screen never shows an answer meant for a different chapter. */
data class AlternateSourceReaderAheadState(
    val finishedChapterId: Long,
    val report: AlternateSourceReaderAheadChecker.Report,
)
// KMK <--
