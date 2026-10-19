package eu.kanade.tachiyomi.ui.manga.track

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History

object LocalTrackingHistoryProgressPolicy {

    data class ReadProgress(
        val chapterId: Long,
        val progressAt: Long,
        val firstReadAt: Long,
    )

    fun resolve(history: List<History>): ReadProgress? {
        val readHistory = history.mapNotNull { entry ->
            entry.readAt?.time?.takeIf { it > 0L }?.let { readAt -> entry to readAt }
        }
        if (readHistory.isEmpty()) return null

        return ReadProgress(
            chapterId = readHistory.maxBy { it.second }.first.chapterId,
            progressAt = readHistory.maxOf { it.second },
            firstReadAt = readHistory.minOf { it.second },
        )
    }

    // KMK --> v0.8.24
    /** One confirmed version's reading history and chapters, for [seed]. */
    data class VersionProgress<T>(
        val version: T,
        val history: List<History>,
        val chapters: List<Chapter>,
    )

    /** Where a new local tracking work starts: [chapter] of [version]. */
    data class Seed<T>(
        val version: T,
        val chapter: Chapter,
        val progressAt: Long,
        val firstReadAt: Long?,
    )

    /**
     * The starting chapter for new local tracking across confirmed [versions].
     *
     * The most recently read chapter (history) is used, as before, unless chapters are marked read further than any
     * chapter that has reading history: those marks were made by hand, restored or synced, so the furthest of them wins.
     * When the furthest read chapter does have history, the user really read that far and went back, so the deliberate
     * reread is kept. Chapters whose number is not recognized are never compared.
     */
    fun <T> seed(versions: List<VersionProgress<T>>, now: Long): Seed<T>? {
        val fromHistory = versions
            .mapNotNull { v -> resolve(v.history)?.let { progress -> v to progress } }
            .maxByOrNull { it.second.progressAt }
            ?.let { (v, progress) ->
                v.chapters.firstOrNull { it.id == progress.chapterId }?.let { chapter ->
                    Seed(v.version, chapter, progress.progressAt, progress.firstReadAt)
                }
            }
        val furthestMark = versions
            .mapNotNull { v ->
                v.chapters.filter { it.read && it.isRecognizedNumber }.maxByOrNull { it.chapterNumber }?.let { v to it }
            }
            .maxByOrNull { it.second.chapterNumber }
            ?.let { (v, chapter) ->
                Seed(v.version, chapter, (chapter.lastModifiedAt * 1000).takeIf { it > 0L } ?: now, firstReadAt = null)
            }
        if (fromHistory == null) return furthestMark
        if (furthestMark == null || !fromHistory.chapter.isRecognizedNumber) return fromHistory

        val furthestWithHistory = versions.mapNotNull { v ->
            val readIds = v.history.filter { (it.readAt?.time ?: 0L) > 0L }.mapTo(HashSet()) { it.chapterId }
            v.chapters.filter { it.id in readIds && it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }
        }.maxOrNull()
        val marksGoFurther = furthestMark.chapter.chapterNumber > fromHistory.chapter.chapterNumber &&
            (furthestWithHistory == null || furthestMark.chapter.chapterNumber > furthestWithHistory)
        return if (marksGoFurther) furthestMark.copy(firstReadAt = fromHistory.firstReadAt) else fromHistory
    }
    // KMK <--

    fun hasReachedFinalChapter(
        history: List<History>,
        chapters: List<Chapter>,
        finalChapter: Chapter,
    ): Boolean {
        if (!finalChapter.isRecognizedNumber) return false
        val readChapterIds = history.asSequence()
            .filter { it.readAt?.time?.let { time -> time > 0L } == true }
            .map { it.chapterId }
            .toSet()
        return chapters.asSequence()
            .filter { it.id in readChapterIds && it.isRecognizedNumber }
            .any { it.chapterNumber >= finalChapter.chapterNumber }
    }
}
