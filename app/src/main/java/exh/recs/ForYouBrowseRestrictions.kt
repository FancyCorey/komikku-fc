package exh.recs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.taste.model.normalizeTag

@Serializable
internal data class ForYouBrowseRestrictions(
    val blockedGroups: Set<String> = emptySet(),
    val hiddenUrls: Set<String> = emptySet(),
    val aliases: Map<String, String> = emptyMap(),
    val minimumKnownChapterCount: Int = 0,
) {
    fun allows(manga: Manga, knownChapterCount: Long? = null): Boolean {
        if (manga.url in hiddenUrls) return false
        if (minimumKnownChapterCount > 0 && knownChapterCount != null && knownChapterCount < minimumKnownChapterCount) return false
        val blocked = blockedGroups.mapTo(mutableSetOf(), ::canonical)
        if (blocked.isEmpty()) return true

        val genres = manga.genre.orEmpty().mapTo(mutableSetOf(), ::canonical)
        return genres.none { it in blocked }
    }

    private fun canonical(value: String): String {
        val normalized = value.normalizeTag()
        val userCanonical = aliases[normalized]?.normalizeTag() ?: normalized
        return (aliases[userCanonical]?.normalizeTag() ?: userCanonical)
    }

    companion object {
        fun from(
            blockedGroups: Set<String>,
            hiddenUrls: Set<String>,
            aliases: Map<String, String>,
        ) = ForYouBrowseRestrictions(blockedGroups, hiddenUrls, aliases)

        fun fromProfile(
            sourceId: Long,
            blockedGroups: Set<String>,
            tastes: Collection<MangaTaste>,
            aliases: Map<String, String>,
            minimumKnownChapterCount: Int = 0,
        ) = from(
            blockedGroups = blockedGroups,
            hiddenUrls = tastes.asSequence()
                .filter { it.source == sourceId && hidesRating(it.rating) }
                .map { it.url }
                .toSet(),
            aliases = aliases,
        ).copy(minimumKnownChapterCount = minimumKnownChapterCount)
    }
}

internal data class ForYouBrowseRestrictionContext private constructor(
    val restrictions: ForYouBrowseRestrictions?,
    val isInvalid: Boolean,
) {
    fun allows(manga: Manga): Boolean = !isInvalid && restrictions?.allows(manga) != false

    companion object {
        fun decode(json: String?): ForYouBrowseRestrictionContext {
            if (json == null) return ForYouBrowseRestrictionContext(restrictions = null, isInvalid = false)
            return runCatching { Json.decodeFromString<ForYouBrowseRestrictions>(json) }
                .fold(
                    onSuccess = { ForYouBrowseRestrictionContext(it, isInvalid = false) },
                    onFailure = { ForYouBrowseRestrictionContext(restrictions = null, isInvalid = true) },
                )
        }
    }
}

private fun hidesRating(rating: Int): Boolean =
    rating == MangaRating.DISLIKE.value || rating == MangaRating.NOT_INTERESTED.value
