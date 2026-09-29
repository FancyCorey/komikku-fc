package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderInitialChapterPolicyTest {
    @Test
    fun `restored position wins over the original launch page`() {
        assertEquals(1, ReaderInitialChapterPolicy.resolvePage(10L, 10L, 0, 1))
        assertEquals(0, ReaderInitialChapterPolicy.resolvePage(10L, 10L, 7, 0))
    }

    @Test
    fun `fresh explicit page remains selected without restored progress`() {
        assertEquals(7, ReaderInitialChapterPolicy.resolvePage(10L, 10L, 7))
        assertEquals(0, ReaderInitialChapterPolicy.resolvePage(10L, 10L, 0, -1))
    }

    @Test
    fun `missing page leaves chapter progress to the loader`() {
        assertNull(ReaderInitialChapterPolicy.resolvePage(10L, 10L, null))
        assertNull(ReaderInitialChapterPolicy.resolvePage(10L, 10L, -1, -2))
    }

    @Test
    fun `fallback chapter does not inherit requested or restored page`() {
        assertNull(ReaderInitialChapterPolicy.resolvePage(10L, 20L, 7, 9))
    }

    @Test
    fun `requested chapter remains preferred`() {
        assertEquals(1, ReaderInitialChapterPolicy.resolveIndex(listOf(10L, 20L, 30L), 20L))
    }

    @Test
    fun `stale requested chapter falls back to first ordered chapter`() {
        assertEquals(0, ReaderInitialChapterPolicy.resolveIndex(listOf(10L, 20L, 30L), 99L))
    }

    @Test
    fun `empty chapter list remains unavailable`() {
        assertNull(ReaderInitialChapterPolicy.resolveIndex(emptyList(), 99L))
    }

    @Test
    fun `null chapter ids do not prevent deterministic fallback`() {
        assertEquals(0, ReaderInitialChapterPolicy.resolveIndex(listOf(null, 20L), 99L))
    }
}
