package eu.kanade.domain.track.interactor

import tachiyomi.domain.track.model.Track

// KMK v0.8.24 -->
/**
 * Two manga rows (versions of one manga from different sources) can be bound to the same remote tracker entry. After one
 * of them records progress, the other's local copy of that entry is stale until it is refreshed. [mirror] returns the
 * sibling row updated with the remote entry's state, or null when nothing would change, or when the change would lower
 * progress and [allowLower] is false (the reading path).
 * The sibling keeps its own id, manga and library id.
 */
object TrackLinkedVersionMirrorPolicy {

    fun mirror(sibling: Track, updated: Track, allowLower: Boolean = false): Track? {
        if (sibling.trackerId != updated.trackerId || sibling.remoteId != updated.remoteId || updated.remoteId <= 0L) return null
        if (!allowLower && updated.lastChapterRead < sibling.lastChapterRead) return null
        val mirrored = sibling.copy(
            lastChapterRead = updated.lastChapterRead,
            totalChapters = updated.totalChapters,
            status = updated.status,
            score = updated.score,
            startDate = updated.startDate,
            finishDate = updated.finishDate,
        )
        return mirrored.takeIf { it != sibling }
    }
}
// KMK <--
