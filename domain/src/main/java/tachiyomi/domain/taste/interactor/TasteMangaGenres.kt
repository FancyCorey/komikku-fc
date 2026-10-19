package tachiyomi.domain.taste.interactor

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.manga.repository.MangaRepository

// KMK -->
/**
 * v0.8.24: genres of the rated manga, read in batches. The taste profile, taste suggestions and taste diagnostics each
 * looked up every rated manga with its own query (931 queries apiece on the user's library), which slowed both the start
 * of a For You refresh and the Troubleshooting page. Missing manga are simply absent, as before. If a batch fails, its
 * ids are read one by one, so a bad batch never drops more than the old per-manga lookup did.
 */
internal suspend fun MangaRepository.genresByMangaId(ids: Collection<Long>): Map<Long, List<String>?> {
    val result = HashMap<Long, List<String>?>(ids.size)
    for (chunk in ids.distinct().chunked(MAX_IDS_PER_QUERY)) {
        val batch = try {
            getMangaByIds(chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (batch != null) {
            batch.forEach { result[it.id] = it.genre }
        } else {
            chunk.forEach { id ->
                runCatching { getMangaById(id) }.getOrNull()?.let { result[id] = it.genre }
            }
        }
    }
    return result
}

/** Stays well under SQLite's bound-parameter limit. */
private const val MAX_IDS_PER_QUERY = 500
// KMK <--
