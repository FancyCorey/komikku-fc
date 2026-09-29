package eu.kanade.tachiyomi.ui.reader

/**
 * Chooses the chapter used to start a reader session.
 *
 * A restored or delayed launch can refer to a chapter that is no longer in the current list. The
 * requested chapter always wins when present; otherwise the first chapter in the caller's already
 * ordered list is used. An empty list stays an explicit failure rather than inventing content.
 */
internal object ReaderInitialChapterPolicy {
    fun resolveIndex(chapterIds: List<Long?>, requestedId: Long): Int? {
        if (chapterIds.isEmpty()) return null
        return chapterIds.indexOfFirst { it == requestedId }.takeUnless { it == -1 } ?: 0
    }

    fun resolvePage(requestedId: Long, selectedId: Long, requestedPage: Int?, restoredPage: Int? = null): Int? {
        if (requestedId != selectedId) return null
        return restoredPage?.takeIf { it >= 0 } ?: requestedPage?.takeIf { it >= 0 }
    }
}
