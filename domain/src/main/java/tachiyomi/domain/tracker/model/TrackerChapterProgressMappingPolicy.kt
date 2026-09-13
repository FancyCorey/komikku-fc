package tachiyomi.domain.tracker.model

import tachiyomi.domain.chapter.model.Chapter
import kotlin.math.floor

/** Resolves tracker progress to a chapter exposed by one concrete manga source. */
object TrackerChapterProgressMappingPolicy {

    fun resolve(
        chapters: List<Chapter>,
        trackerProgress: Double,
        allowReadingOrderFallback: Boolean = true,
    ): Chapter? {
        if (!trackerProgress.isFinite() || trackerProgress <= 0.0) return null

        val recognized = chapters.filter {
            it.isRecognizedNumber && it.chapterNumber.isFinite() && it.chapterNumber >= 0.0
        }
        val numericTarget = recognized
            .filter { it.chapterNumber <= trackerProgress }
            .maxOfOrNull { it.chapterNumber }
        if (numericTarget != null) {
            return representativeFor(recognized, numericTarget)
        }
        if (!allowReadingOrderFallback) return null

        // Some sources number their first main chapter above 1. Treat integer tracker progress as
        // a position only when every main chapter through that position is present consecutively.
        // This keeps a gap before the requested position and fractional-only numbering from
        // producing a guessed read. Later chapters do not affect an already established prefix.
        if (trackerProgress != floor(trackerProgress)) return null
        val position = trackerProgress.toLong()
        if (position <= 0L || position > Int.MAX_VALUE) return null

        val mainChapterNumbers = recognized.asSequence()
            .map { it.chapterNumber }
            .filter { it >= 1.0 && it == floor(it) }
            .distinct()
            .sorted()
            .toList()
        if (mainChapterNumbers.size < position) return null

        val throughTarget = mainChapterNumbers.take(position.toInt())
        val first = throughTarget.firstOrNull() ?: return null
        if (first <= 1.0 || throughTarget.withIndex().any { (index, number) -> number != first + index }) {
            return null
        }
        return representativeFor(recognized, throughTarget.last())
    }

    private fun representativeFor(chapters: List<Chapter>, chapterNumber: Double): Chapter? =
        chapters.asSequence()
            .filter { it.chapterNumber == chapterNumber }
            .sortedWith(compareByDescending<Chapter> { it.sourceOrder }.thenBy { it.url })
            .firstOrNull()
}
