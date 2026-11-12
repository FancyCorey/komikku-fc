package exh.recs

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.SourceRuntime
import eu.kanade.tachiyomi.source.SourceRuntimeOperation
import eu.kanade.tachiyomi.source.getOrThrowSourceRuntimeException
import eu.kanade.tachiyomi.source.isRecoverableSourceRuntimeFailure
import eu.kanade.tachiyomi.source.unwrapSourceRuntimeCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

// KMK v0.8.25 -->
/**
 * The chapter counts recommendation screens filter on: what the library already knows, plus counts For You or a Recommendations
 * screen loaded from a source in the last two weeks. One checker so For You, a manga's Recommendations and a group's
 * Recommendations agree on the minimum chapter count from Your taste.
 */
class RecommendationChapterCountChecker(
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    // test seam: how one manga's chapter count is loaded
    private val loadCount: (suspend (Manga) -> Long?)? = null,
) {
    private companion object {
        val cacheLock = Any()
    }

    /** [local] counts (the library's) win; checked counts fill in the rest. */
    fun knownCounts(ids: Collection<Long>, local: Map<Long, Long>): Map<Long, Long> {
        val checked = try {
            ForYouChapterCountCheck.fresh(ForYouChapterCountCheck.parse(sourcePreferences.recommendationCheckedChapterCounts().get()), now())
        } catch (_: Exception) {
            emptyMap()
        }
        return ids.mapNotNull { id -> (local[id] ?: checked[id])?.let { id to it } }.toMap()
    }

    /** Loads the chapter lists of [manga], a few at a time, remembers the counts and returns them. Failures are left unknown. */
    suspend fun check(manga: List<Manga>, onCount: (Map<Long, Long>) -> Unit = {}): Map<Long, Long> {
        if (manga.isEmpty()) return emptyMap()
        val slots = Semaphore(ForYouChapterCountCheck.CONCURRENT_CHECKS)
        val completed = ConcurrentHashMap<Long, Long>()
        try {
            return coroutineScope {
                manga.filter { it.id > 0 }.distinctBy { it.id }.map { item ->
                    async {
                        slots.withPermit {
                            val count = try {
                                withTimeoutOrNull(ForYouChapterCountCheck.CHECK_TIMEOUT_MS) { (loadCount ?: ::loadFromSource)(item) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                if (!e.unwrapSourceRuntimeCause().isRecoverableSourceRuntimeFailure()) throw e
                                null
                            }
                            // an empty list more likely means the source could not read its chapters: unknown, not zero
                            count?.takeIf { it > 0 }?.let {
                                completed[item.id] = it
                                onCount(mapOf(item.id to it))
                                item.id to it
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        } finally {
            // Keep successful work when navigation cancels another source; persist once per batch.
            rememberCounts(completed)
        }
    }

    private fun rememberCounts(counts: Map<Long, Long>) {
        if (counts.isNotEmpty()) {
            try {
                synchronized(cacheLock) {
                    val preference = sourcePreferences.recommendationCheckedChapterCounts()
                    val at = now()
                    val merged = ForYouChapterCountCheck.parse(preference.get()) + counts.mapValues { ForYouChapterCountCheck.Checked(it.value, at) }
                    preference.set(ForYouChapterCountCheck.serialize(merged, at))
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Apply remembered counts immediately, then publish each new count without waiting for slower sources. */
    suspend fun checkShown(
        rows: List<List<Manga>>,
        local: Map<Long, Long>,
        limit: Int,
        onCounts: (Map<Long, Long>) -> Unit,
    ) {
        val byId = rows.flatten().filter { it.id > 0 }.associateBy { it.id }
        val known = knownCounts(byId.keys, local)
        onCounts(known)
        val ids = ForYouChapterCountCheck.pick(rows.map { row -> row.map { it.id } }, known.keys, limit)
        check(ids.mapNotNull(byId::get), onCounts)
    }

    private suspend fun loadFromSource(manga: Manga): Long? {
        val source = sourceManager.get(manga.source) ?: return null
        return SourceRuntime.run(source, SourceRuntimeOperation.MangaUpdate, dispatcher) {
            getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters.size.toLong()
        }.getOrThrowSourceRuntimeException()
    }
}
// KMK <--
