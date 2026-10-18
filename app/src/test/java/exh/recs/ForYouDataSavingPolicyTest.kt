package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// KMK --> v0.8.24 For You data saving
class ForYouDataSavingPolicyTest {

    @Test
    fun `always and never ignore the network and automatic follows it`() {
        assertTrue(ForYouDataSavingPolicy.isActive(ForYouDataSaving.ALWAYS, networkConstrained = false))
        assertFalse(ForYouDataSavingPolicy.isActive(ForYouDataSaving.NEVER, networkConstrained = true))
        assertTrue(ForYouDataSavingPolicy.isActive(ForYouDataSaving.AUTO, networkConstrained = true))
        assertFalse(ForYouDataSavingPolicy.isActive(ForYouDataSaving.AUTO, networkConstrained = false))
    }

    @Test
    fun `a metered or known slow connection is constrained and an unknown estimate is not`() {
        assertTrue(ForYouDataSavingPolicy.isConstrained(metered = true, downstreamKbps = 100_000))
        assertTrue(ForYouDataSavingPolicy.isConstrained(metered = false, downstreamKbps = 500))
        assertTrue(ForYouDataSavingPolicy.isConstrained(metered = false, downstreamKbps = ForYouDataSavingPolicy.SLOW_DOWNSTREAM_KBPS - 1))
        assertFalse(ForYouDataSavingPolicy.isConstrained(metered = false, downstreamKbps = ForYouDataSavingPolicy.SLOW_DOWNSTREAM_KBPS))
        assertFalse(ForYouDataSavingPolicy.isConstrained(metered = false, downstreamKbps = 0), "0 means the OS does not know")
    }

    @Test
    fun `a stored value that is not recognised falls back to automatic`() {
        assertEquals(ForYouDataSaving.AUTO, ForYouDataSaving.resolve("garbage"))
        assertEquals(ForYouDataSaving.ALWAYS, ForYouDataSaving.resolve("on"))
        assertEquals(ForYouDataSaving.NEVER, ForYouDataSaving.resolve("off"))
        assertEquals(ForYouDataSaving.AUTO, ForYouDataSaving.resolve("auto"))
    }

    @Test
    fun `saving stops earlier than the normal row limit`() {
        assertTrue(ForYouDataSavingPolicy.SAVING_MAX_VISIBLE_SOURCE_ROWS < 20)
    }
}
// KMK <--
