package tachiyomi.domain.taste.model

// KMK -->
/**
 * Computed profile representing a user's content preferences.
 *
 * [learnedTagWeights] — per-group-key weight derived from manga ratings and their genres.
 *   Positive values indicate liked content, negative disliked.
 * [explicitTagPreferences] — per-group-key preference from direct tag taste rows (-1 dislike, +1 prefer).
 * [sourceAffinity] — weak per-source signal derived from rating distribution.
 * [blockedGroups] — group keys that must cause hard rejection before scoring.
 * [recentTagWeights] — v0.8.24: per-group-key positive weight derived from what the person actually read in the recent
 *   window (chapters read, share of the manga read, recency). Never negative; it is empty when the feature is off,
 *   and it never contains a group with an explicit Dislike or Block.
 */
data class TasteProfile(
    val learnedTagWeights: Map<String, Double>,
    val explicitTagPreferences: Map<String, Int>,
    val sourceAffinity: Map<Long, Double>,
    val blockedGroups: Set<String>,
    val recentTagWeights: Map<String, Double> = emptyMap(),
) {
    fun isEmpty(): Boolean =
        learnedTagWeights.isEmpty() && explicitTagPreferences.isEmpty() && blockedGroups.isEmpty() &&
            recentTagWeights.isEmpty()

    companion object {
        val EMPTY = TasteProfile(
            learnedTagWeights = emptyMap(),
            explicitTagPreferences = emptyMap(),
            sourceAffinity = emptyMap(),
            blockedGroups = emptySet(),
        )
    }
}
// KMK <--
