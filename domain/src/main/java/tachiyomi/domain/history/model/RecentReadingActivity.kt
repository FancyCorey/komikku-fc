package tachiyomi.domain.history.model

// KMK -->
/**
 * v0.8.24 recent-reading signal: what one manga's reading history looks like inside a recent window.
 *
 * Derived only from the existing `history` and `chapters` rows (no new storage). `history` keeps a single row
 * per chapter, so "opens" cannot be counted; [activeDays] (distinct local days on which a chapter's last read
 * falls inside the window) is the closest honest proxy, and a chapter re-read on a later day replaces its
 * earlier date.
 *
 * @property chaptersRead chapters opened inside the window that were read (marked read or with reading time).
 * @property activeDays distinct local calendar days that contain at least one such chapter's last read.
 * @property lastReadAt epoch millis of the most recent read inside the window.
 * @property totalChapters chapters the manga currently has locally (0 when no chapter list is stored).
 */
data class RecentReadingActivity(
    val mangaId: Long,
    val chaptersRead: Long,
    val activeDays: Long,
    val lastReadAt: Long,
    val totalChapters: Long,
)
// KMK <--
