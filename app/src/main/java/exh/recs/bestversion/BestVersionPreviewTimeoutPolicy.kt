package exh.recs.bestversion

object BestVersionPreviewTimeoutPolicy {
    fun shouldShowWarning(
        timedOut: Boolean,
        pendingPageCount: Int,
        failedPageCount: Int,
        sourceFailedPageCount: Int,
    ): Boolean =
        timedOut &&
            pendingPageCount > 0 &&
            failedPageCount == 0 &&
            sourceFailedPageCount == 0
}
