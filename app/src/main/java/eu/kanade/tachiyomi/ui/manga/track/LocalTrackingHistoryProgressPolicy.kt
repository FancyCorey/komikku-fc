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
