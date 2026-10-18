package tachiyomi.domain.taste.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.taste.repository.TasteRepository

// KMK -->
class SetMangaTasteBatch(
    private val repository: TasteRepository,
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(mangas: List<Manga>, rating: MangaRating) {
        val now = System.currentTimeMillis()
        mangas.forEach { manga ->
            val persistedManga = mangaRepository.persistedTasteTarget(manga)
            repository.upsertMangaTaste(
                MangaTaste(
                    mangaId = persistedManga.id,
                    source = persistedManga.source,
                    url = persistedManga.url,
                    title = persistedManga.title,
                    rating = rating.value,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}

class InsertMangaTasteIfAbsent(
    private val repository: TasteRepository,
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(manga: Manga, rating: MangaRating): Boolean {
        val persistedManga = mangaRepository.persistedTasteTarget(manga)
        val now = System.currentTimeMillis()
        return repository.insertMangaTasteIfAbsent(
            MangaTaste(
                mangaId = persistedManga.id,
                source = persistedManga.source,
                url = persistedManga.url,
                title = persistedManga.title,
                rating = rating.value,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

private suspend fun MangaRepository.persistedTasteTarget(manga: Manga): Manga {
    require(manga.url.isNotBlank()) {
        "A MangaTaste requires a nonblank source URL identity"
    }
    val persistedManga = getMangaByUrlAndSourceId(manga.url, manga.source)
        ?: insertNetworkManga(
            listOf(manga.copy(id = 0L, favorite = false)),
            updateInfo = false,
        ).single()
    check(persistedManga.id > 0L && persistedManga.source == manga.source && persistedManga.url == manga.url) {
        "A MangaTaste requires a persisted Manga row with the same source and URL"
    }
    return persistedManga
}
// KMK <--
