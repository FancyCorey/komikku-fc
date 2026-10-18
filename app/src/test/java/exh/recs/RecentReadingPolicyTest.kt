package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.RecentReadingActivity

// KMK --> v0.8.24 recent-reading profile: the invariants the user asked for, independent of the exact constants.
class RecentReadingPolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun activity(read: Long, total: Long, activeDays: Long = 1L, ageDays: Long = 0L) =
        RecentReadingActivity(
            mangaId = 1L,
            chaptersRead = read,
            activeDays = activeDays,
            lastReadAt = now - ageDays * day,
            totalChapters = total,
        )

    private fun strength(a: RecentReadingActivity, window: Int = 7, minChapters: Int = 3) =
        RecentReadingPolicy.strength(a, window, minChapters, now)

    @Test
    fun `settings fall back to their defaults outside the allowed ranges`() {
        assertEquals(7, RecentReadingPolicy.resolveWindowDays(0))
        assertEquals(7, RecentReadingPolicy.resolveWindowDays(31))
        assertEquals(1, RecentReadingPolicy.resolveWindowDays(1))
        assertEquals(30, RecentReadingPolicy.resolveWindowDays(30))
        assertEquals(3, RecentReadingPolicy.resolveMinChapters(0))
        assertEquals(3, RecentReadingPolicy.resolveMinChapters(51))
        assertEquals(1, RecentReadingPolicy.resolveMinChapters(1))
        assertEquals(50, RecentReadingPolicy.resolveMinChapters(50))
    }

    @Test
    fun `reading ten of twenty chapters counts more than ten of a hundred`() {
        assertTrue(strength(activity(read = 10, total = 20)) > strength(activity(read = 10, total = 100)))
    }

    @Test
    fun `more chapters in a longer series does not beat the same share of a short one`() {
        // 20 of 200 is the same 10% as 2 of 20; the long series must not win just because it is long.
        assertEquals(
            strength(activity(read = 2, total = 20)),
            strength(activity(read = 20, total = 200)),
            0.0005,
        )
    }

    @Test
    fun `a one chapter manga cannot reach full strength and the minimum chapters floor is configurable`() {
        val tiny = activity(read = 1, total = 1)
        assertTrue(strength(tiny) < strength(activity(read = 10, total = 20)))
        assertTrue(strength(tiny, minChapters = 1) > strength(tiny, minChapters = 10), "a higher floor shrinks it")
        assertEquals(1.0, strength(tiny, minChapters = 1), 0.0005)
    }

    @Test
    fun `reading on more distinct days adds a bounded bonus`() {
        val oneDay = strength(activity(read = 10, total = 20, activeDays = 1))
        val fiveDays = strength(activity(read = 10, total = 20, activeDays = 5))
        val everyDay = strength(activity(read = 10, total = 20, activeDays = 7))
        val moreThanTheWindow = strength(activity(read = 10, total = 20, activeDays = 40))
        assertTrue(fiveDays > oneDay)
        assertEquals(1.0 + RecentReadingPolicy.SPREAD_BONUS_MAX, everyDay / oneDay, 0.01)
        assertEquals(everyDay, moreThanTheWindow, 0.0005)
    }

    @Test
    fun `a more recent read counts more and the recency floor holds beyond the window`() {
        val today = strength(activity(read = 10, total = 20, ageDays = 0))
        val lastDay = strength(activity(read = 10, total = 20, ageDays = 6))
        val beyond = strength(activity(read = 10, total = 20, ageDays = 30))
        assertTrue(today > lastDay)
        assertTrue(lastDay > beyond)
        assertEquals(RecentReadingPolicy.RECENCY_FLOOR, beyond / today, 0.01)
    }

    @Test
    fun `nothing read means no strength`() {
        assertEquals(0.0, strength(activity(read = 0, total = 20)), 0.0)
    }

    @Test
    fun `a read through recent manga outweighs a Love rating while a typical partial read is about a Like`() {
        val loveRatingWeight = 2.0
        val likeRatingWeight = 1.0
        val readThrough = RecentReadingPolicy.perGenreWeight(
            strength(activity(read = 20, total = 20, activeDays = 3, ageDays = 0)),
        )
        val partial = RecentReadingPolicy.perGenreWeight(strength(activity(read = 10, total = 20, ageDays = 2)))
        assertTrue(readThrough > loveRatingWeight, "recent reading is stronger than the long-term rating")
        assertTrue(partial >= likeRatingWeight && partial < loveRatingWeight, "partial read is about a Like")
    }

    @Test
    fun `group weight is capped and rounded to two decimals`() {
        assertEquals(RecentReadingPolicy.GROUP_CAP, RecentReadingPolicy.finalGroupWeight(100.0), 0.0)
        assertEquals(1.23, RecentReadingPolicy.finalGroupWeight(1.23456), 0.0)
    }

    @Test
    fun `the window starts the configured number of days before now`() {
        assertEquals(now - 3 * day, RecentReadingPolicy.windowStartMillis(now, 3))
        assertEquals(now - 7 * day, RecentReadingPolicy.windowStartMillis(now, 999), "invalid falls back to the default")
    }
}
// KMK <--
