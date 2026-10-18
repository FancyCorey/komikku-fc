package exh.recs.matching

import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste

internal object ConfirmedGroupTasteRatingPolicy {
    /** Matches the existing display rule: the most recently updated taste wins. */
    fun effectiveRating(tastes: Iterable<MangaTaste>): MangaRating? {
        var latest: MangaTaste? = null
        for (taste in tastes) {
            val current = latest
            if (current == null || taste.updatedAt > current.updatedAt) latest = taste
        }
        return latest?.rating?.let(MangaRating::fromValue)
    }

    /** Automatic joins fill an empty destination only; any existing row is an explicit choice. */
    fun joiningRating(
        enabled: Boolean,
        destinationTaste: MangaTaste?,
        existingGroupTastes: Iterable<MangaTaste>,
    ): MangaRating? = if (enabled && destinationTaste == null) effectiveRating(existingGroupTastes) else null
}
