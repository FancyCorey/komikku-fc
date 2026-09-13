package eu.kanade.tachiyomi.ui.manga.track

import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus

/** Resolves an initial local status from reading facts without overriding explicit terminal choices. */
internal object AutomaticLocalTrackingStatusPolicy {
    fun resolve(
        enabled: Boolean,
        existingStatus: LocalTrackedWorkStatus?,
        hasGenuineProgress: Boolean,
        hasReadFinalChapter: Boolean,
        metadataStatusCompleted: Boolean,
    ): LocalTrackedWorkStatus {
        if (!enabled) return existingStatus ?: LocalTrackedWorkStatus.PLANNED
        if (existingStatus == LocalTrackedWorkStatus.ON_HOLD ||
            existingStatus == LocalTrackedWorkStatus.DROPPED ||
            existingStatus == LocalTrackedWorkStatus.COMPLETED ||
            existingStatus == LocalTrackedWorkStatus.READING
        ) {
            return existingStatus
        }
        if (!hasGenuineProgress) return LocalTrackedWorkStatus.PLANNED
        return if (hasReadFinalChapter && metadataStatusCompleted) {
            LocalTrackedWorkStatus.COMPLETED
        } else {
            LocalTrackedWorkStatus.READING
        }
    }
}
