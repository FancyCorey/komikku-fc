package eu.kanade.tachiyomi.ui.manga.track

import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus

internal object LocalTrackingManualProgressPolicy {
    fun resolveStatus(
        existingStatus: LocalTrackedWorkStatus,
        chapterNumber: Double?,
        completedAtFinalChapter: Boolean,
    ): LocalTrackedWorkStatus = when {
        existingStatus == LocalTrackedWorkStatus.ON_HOLD || existingStatus == LocalTrackedWorkStatus.DROPPED -> {
            existingStatus
        }
        completedAtFinalChapter -> LocalTrackedWorkStatus.COMPLETED
        chapterNumber != null && chapterNumber > 0.0 -> LocalTrackedWorkStatus.READING
        else -> LocalTrackedWorkStatus.PLANNED
    }

    fun chapterLabel(chapterNumber: Double?, sourceLabel: String?): String? {
        if (chapterNumber == null) return null
        return sourceLabel ?: "Chapter ${formatChapterNumber(chapterNumber)}"
    }

    private fun formatChapterNumber(chapterNumber: Double): String =
        if (chapterNumber % 1.0 == 0.0) chapterNumber.toLong().toString() else chapterNumber.toString()
}
