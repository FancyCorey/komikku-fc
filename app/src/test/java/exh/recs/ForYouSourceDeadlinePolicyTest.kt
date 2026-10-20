package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// KMK --> v0.8.24: the per-source limit follows the other sources in the same refresh
class ForYouSourceDeadlinePolicyTest {

    @Test
    fun `before any source finishes only the safety cap applies`() {
        assertEquals(ForYouSourceDeadlinePolicy.MAX_MS, ForYouSourceDeadlinePolicy().limitMs())
    }

    @Test
    fun `fast sources give an outlier the minimum limit`() {
        val policy = ForYouSourceDeadlinePolicy()
        listOf(500L, 1_100L, 1_800L, 3_000L, 6_000L).forEach(policy::recordFinished)
        assertEquals(ForYouSourceDeadlinePolicy.MIN_MS, policy.limitMs())
    }

    @Test
    fun `on a slow network the limit grows with everyone`() {
        val policy = ForYouSourceDeadlinePolicy()
        listOf(20_000L, 25_000L, 30_000L).forEach(policy::recordFinished)
        assertEquals(100_000L, policy.limitMs(), "four times the median of 25 s")
        listOf(90_000L, 120_000L, 150_000L).forEach(policy::recordFinished)
        assertEquals(ForYouSourceDeadlinePolicy.MAX_MS, policy.limitMs(), "never above the cap")
    }
}
// KMK <--
