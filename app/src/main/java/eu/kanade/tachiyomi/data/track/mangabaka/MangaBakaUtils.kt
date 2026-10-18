// KMK --> v0.8.24: MangaBaka tracker ported from Mihon (mihonapp/mihon, Apache-2.0) and adapted to Komikku.
package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.database.models.Track

internal fun Track.toApiStatus() = when (status) {
    MangaBaka.CONSIDERING -> "considering"
    MangaBaka.COMPLETED -> "completed"
    MangaBaka.DROPPED -> "dropped"
    MangaBaka.PAUSED -> "paused"
    MangaBaka.PLAN_TO_READ -> "plan_to_read"
    MangaBaka.READING -> "reading"
    MangaBaka.REREADING -> "rereading"
    else -> throw NotImplementedError("Unknown status: $status")
}
// KMK <--
