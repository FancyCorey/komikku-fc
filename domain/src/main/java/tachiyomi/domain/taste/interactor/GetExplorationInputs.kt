package tachiyomi.domain.taste.interactor

import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.repository.TasteRepository

// KMK v0.8.25 -->
/**
 * What the exploration lanes need from the user's ratings: their most recently loved titles with the tags they carry, and the
 * tag sets of everything they liked or loved. One read of the ratings and one batched read of tags, like [GetTasteProfile].
 */
class GetExplorationInputs(
    private val tasteRepository: TasteRepository,
    private val mangaRepository: MangaRepository,
) {
    data class LovedTitle(val title: String, val tags: Set<String>)

    data class Inputs(val lovedByRecency: List<LovedTitle>, val positiveTagSets: List<Set<String>>)

    suspend fun await(maxLoved: Int = MAX_LOVED): Inputs {
        val tastes = tasteRepository.getAllMangaTastes()
        val positive = tastes.filter { it.rating == MangaRating.LOVE.value || it.rating == MangaRating.LIKE.value }
        val loved = positive.filter { it.rating == MangaRating.LOVE.value }
            .sortedByDescending { it.updatedAt }
            .take(maxLoved)
        val genres = mangaRepository.genresByMangaId(positive.map { it.mangaId })
        return Inputs(
            lovedByRecency = loved.map { LovedTitle(it.title, genres[it.mangaId].orEmpty().toSet()) },
            positiveTagSets = positive.mapNotNull { genres[it.mangaId]?.toSet()?.takeIf { tags -> tags.isNotEmpty() } },
        )
    }

    private companion object {
        const val MAX_LOVED = 12
    }
}
// KMK <--
