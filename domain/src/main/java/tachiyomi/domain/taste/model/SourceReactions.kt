package tachiyomi.domain.taste.model

// KMK v0.8.25 -->
/**
 * What the user has done with titles from one source: [positive] points from Love (2) and Like (1), [negative] points from
 * Dislike (2) and Not interested (1). This is the best evidence of whether a source fits, because it is the user's own
 * reaction, not whether the source returned something we accepted.
 */
data class SourceReactions(val positive: Int, val negative: Int) {
    val evidence: Int get() = positive + negative

    /**
     * Share of positive reactions, pulled toward 50 percent by [PRIOR] pseudo-points so that two reactions never look like
     * a verdict. With no reactions this is exactly 0.5.
     */
    val shrunkRate: Double get() = (positive + PRIOR / 2.0) / (evidence + PRIOR.toDouble())

    /** Enough reactions for the rate to be allowed to change a label. */
    val isEnough: Boolean get() = evidence >= MIN_EVIDENCE

    companion object {
        const val PRIOR = 4
        const val MIN_EVIDENCE = 6
        const val GOOD_RATE = 0.70
        const val POOR_RATE = 0.30
    }
}
// KMK <--
