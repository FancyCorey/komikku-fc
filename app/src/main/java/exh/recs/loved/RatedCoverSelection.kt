package exh.recs.loved

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import tachiyomi.domain.manga.model.MangaCover

/** Advance only after the displayed, size-aware cover request fails; no separate probe or decode. */
internal class RatedCoverSelection(candidates: List<MangaCover>) {
    private val candidates = candidates.distinct().also { require(it.isNotEmpty()) }
    private var index by mutableIntStateOf(0)
    val current: MangaCover get() = candidates[index]

    fun onError(failed: MangaCover) {
        // A late callback from a replaced request must not skip the current candidate.
        if (failed == current && index < candidates.lastIndex) index++
    }
}
