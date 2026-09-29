package eu.kanade.tachiyomi.ui.reader

internal data class ReaderRouteIntent(
    val mangaId: Long,
    val chapterId: Long,
    val page: Int?,
)

internal fun parseReaderRouteIntent(
    mangaId: Long,
    chapterId: Long,
    page: Int,
): ReaderRouteIntent? {
    if (mangaId <= 0L || chapterId <= 0L) return null
    return ReaderRouteIntent(
        mangaId = mangaId,
        chapterId = chapterId,
        page = page.takeUnless { it < 0 },
    )
}
