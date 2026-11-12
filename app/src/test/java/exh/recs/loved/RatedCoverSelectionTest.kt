package exh.recs.loved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover

class RatedCoverSelectionTest {
    private fun cover(id: Long) = MangaCover(id, id, false, "/$id.jpg", 0L)

    @Test
    fun `failed covers advance once and late callbacks cannot skip a working fallback`() {
        val first = cover(1)
        val second = cover(2)
        val last = cover(3)
        val state = RatedCoverSelection(listOf(first, first, second, last))
        assertEquals(first, state.current)
        state.onError(first)
        assertEquals(second, state.current)
        state.onError(first)
        assertEquals(second, state.current)
        state.onError(second)
        assertEquals(last, state.current)
        state.onError(last)
        assertEquals(last, state.current)
    }
}
