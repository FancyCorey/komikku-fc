package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// KMK --> v0.8.24: remembered chapter counts and which shown picks get checked
class ForYouChapterCountCheckTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `counts round trip and expire after two weeks`() {
        val now = 100 * day
        val stored = ForYouChapterCountCheck.serialize(
            mapOf(
                1L to ForYouChapterCountCheck.Checked(12, now - day),
                2L to ForYouChapterCountCheck.Checked(3, now - 20 * day),
            ),
            now,
        )
        val parsed = ForYouChapterCountCheck.parse(stored)
        assertEquals(setOf(1L), parsed.keys, "expired counts are not kept")
        assertEquals(mapOf(1L to 12L), ForYouChapterCountCheck.fresh(parsed, now))
        assertEquals(emptyMap<Long, Long>(), ForYouChapterCountCheck.fresh(parsed, now + 14 * day))
    }

    @Test
    fun `malformed rows are ignored`() {
        assertEquals(mapOf(5L to ForYouChapterCountCheck.Checked(7, 9)), ForYouChapterCountCheck.parse("x;5:7:9;1:2;;"))
    }

    @Test
    fun `the first picks of every row are checked before later ones, unknown counts only, within the budget`() {
        val rows = listOf(listOf(1L, 2L, 3L), listOf(10L, 11L), listOf(20L))
        assertEquals(listOf(1L, 10L, 20L, 2L, 11L, 3L), ForYouChapterCountCheck.pick(rows, knownCounts = emptySet()))
        assertEquals(listOf(10L, 20L, 2L, 3L), ForYouChapterCountCheck.pick(rows, knownCounts = setOf(1L, 11L)))

        val many = (1L..10L).map { row -> (1L..10L).map { row * 100 + it } }
        val picked = ForYouChapterCountCheck.pick(many, emptySet())
        assertEquals(ForYouChapterCountCheck.MAX_CHECKS_PER_REFRESH, picked.size)
        assertTrue(picked.take(10).all { it % 100 == 1L }, "every row's first pick comes first")
    }
}
// KMK <--
