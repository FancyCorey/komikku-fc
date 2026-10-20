package exh.recs

// KMK -->
/**
 * v0.8.24: makes the minimum chapter count apply to For You picks. A pick's chapter count is only known locally when its
 * chapter list was loaded (usually when the manga was opened), so the filter let almost every pick through. After a
 * refresh has shown its rows, For You loads the chapter lists of a few shown picks whose count is unknown, removes those
 * below the minimum and remembers the counts here, so later refreshes filter them straight away.
 */
internal object ForYouChapterCountCheck {

    /** Provisional: chapter lists loaded per refresh, across all sources. */
    const val MAX_CHECKS_PER_REFRESH = 20

    /** Provisional: chapter lists loaded at the same time. */
    const val CONCURRENT_CHECKS = 3

    /** Provisional: how long one chapter-list load may take. */
    const val CHECK_TIMEOUT_MS = 20_000L

    /** A remembered count is trusted this long; series keep adding chapters. */
    const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** At most this many remembered counts (oldest dropped first). */
    const val MAX_REMEMBERED = 3_000

    data class Checked(val count: Long, val checkedAt: Long)

    fun parse(stored: String): Map<Long, Checked> = stored.split(";").mapNotNull { row ->
        val parts = row.split(":")
        if (parts.size != 3) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val count = parts[1].toLongOrNull() ?: return@mapNotNull null
        val at = parts[2].toLongOrNull() ?: return@mapNotNull null
        id to Checked(count, at)
    }.toMap()

    fun serialize(entries: Map<Long, Checked>, now: Long): String =
        entries.entries
            .filter { now - it.value.checkedAt < TTL_MS }
            .sortedByDescending { it.value.checkedAt }
            .take(MAX_REMEMBERED)
            .joinToString(";") { "${it.key}:${it.value.count}:${it.value.checkedAt}" }

    /** Remembered counts still fresh at [now]. */
    fun fresh(entries: Map<Long, Checked>, now: Long): Map<Long, Long> =
        entries.filterValues { now - it.checkedAt < TTL_MS }.mapValues { it.value.count }

    /**
     * Which shown picks to check: rows in their shown order, the first picks of every row before the later ones, only
     * picks whose count is unknown, each manga once, at most [MAX_CHECKS_PER_REFRESH].
     */
    fun pick(rows: List<List<Long>>, knownCounts: Set<Long>): List<Long> {
        val result = LinkedHashSet<Long>()
        val longest = rows.maxOfOrNull { it.size } ?: 0
        for (position in 0 until longest) {
            for (row in rows) {
                val id = row.getOrNull(position) ?: continue
                if (id != 0L && id !in knownCounts) result += id
                if (result.size >= MAX_CHECKS_PER_REFRESH) return result.toList()
            }
        }
        return result.toList()
    }
}
// KMK <--
