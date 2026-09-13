package tachiyomi.domain.taste.interactor

// KMK --> v0.7.26: batch chapter count lookup for minimum-chapter filter
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Returns a map of mangaId → locally-stored chapter count when chapter rows are available.
 * Uses local DB only. Does not fetch chapter lists from sources. Manga IDs without chapter
 * rows are omitted because a localized recommendation can exist before its source chapter list
 * has been loaded; omission means unknown, not zero.
 * Fails open — callers should catch and treat failures as empty map.
 */
class GetChapterCountsByMangaIds(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(mangaIds: Collection<Long>): Map<Long, Long> =
        mangaRepository.getChapterCountsByMangaIds(mangaIds)
}
// KMK <--
