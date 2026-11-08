package eu.kanade.tachiyomi.ui.reader.bridge

import kotlin.math.floor

// KMK v0.8.25 -->
/**
 * What the last chapter of a series shows: which linked versions have something after the chapter just finished.
 *
 * A version counts only when it has a chapter whose number is above the finished one. A version whose numbers are not
 * recognised (negative, NaN) is never shown as ahead, and the same chapter number found twice is not "ahead".
 */
object AlternateSourceReaderAheadPolicy {

    /** The most linked versions looked at for one finished chapter. */
    const val MAX_VERSIONS = 6

    /** How many versions are asked at the same time. */
    const val MAX_CONCURRENT = 2

    /** A finished chapter's answer is reused for this long before the versions are asked again. */
    const val CACHE_MILLIS = 30L * 60L * 1000L

    data class VersionChapters(val key: String, val chapterNumbers: List<Float>)

    data class Ahead(
        val key: String,
        /** The first chapter after the one finished, which is what the Next row names. */
        val nextChapter: Float,
        /** The newest chapter this version has. */
        val latestChapter: Float,
        /** How many chapters it is ahead, at least one. */
        val chaptersAhead: Int,
    )

    sealed interface Offer {
        /** Nothing is ahead, nothing is linked, or the check could not answer: the screen is what it was before. */
        data object None : Offer

        data class One(val ahead: Ahead) : Offer

        data class Several(val versions: List<Ahead>) : Offer
    }

    fun ahead(finishedChapter: Float, versions: List<VersionChapters>): List<Ahead> {
        if (!finishedChapter.isUsable()) return emptyList()
        return versions.asSequence()
            .mapNotNull { version ->
                val above = version.chapterNumbers.filter { it.isUsable() && it > finishedChapter }
                val next = above.minOrNull() ?: return@mapNotNull null
                val latest = above.max()
                Ahead(
                    key = version.key,
                    nextChapter = next,
                    latestChapter = latest,
                    chaptersAhead = floor(latest - finishedChapter).toInt().coerceAtLeast(1),
                )
            }
            .sortedWith(compareByDescending<Ahead> { it.latestChapter }.thenBy { it.key })
            .toList()
    }

    fun offer(ahead: List<Ahead>): Offer = when (ahead.size) {
        0 -> Offer.None
        1 -> Offer.One(ahead.single())
        else -> Offer.Several(ahead)
    }

    /** The chooser lists versions that are ahead first, each by how far ahead; the rest keep their order after them. */
    fun <T> orderAheadFirst(items: List<T>, aheadKeys: List<String>, keyOf: (T) -> String): List<T> {
        if (aheadKeys.isEmpty()) return items
        val rank = aheadKeys.withIndex().associate { (index, key) -> key to index }
        return items.withIndex()
            .sortedWith(compareBy({ rank[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** The first chapter a version has after [finishedChapter], which is where continuing from it starts. */
    fun nextChapterAfter(
        chapters: List<AlternateSourceReaderChapterCandidate>,
        finishedChapter: Float,
    ): AlternateSourceReaderChapterCandidate? {
        if (!finishedChapter.isUsable()) return null
        return chapters
            .filter { it.chapterNumber.isUsable() && it.chapterNumber > finishedChapter }
            .minWithOrNull(compareBy({ it.chapterNumber }, { it.url }))
    }

    /** The newest chapter each version has, ahead or not; a version with no recognised number is left out. */
    fun latestByVersion(versions: List<VersionChapters>): Map<String, Float> =
        versions.mapNotNull { version ->
            version.chapterNumbers.filter { it.isUsable() }.maxOrNull()?.let { version.key to it }
        }.toMap()

    private fun Float.isUsable() = !isNaN() && !isInfinite() && this >= 0f
}
// KMK <--
