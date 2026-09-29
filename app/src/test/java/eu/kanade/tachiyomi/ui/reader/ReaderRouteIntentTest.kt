package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderRouteIntentTest {
    @Test
    fun `missing manga or chapter does not replace the active reader route`() {
        assertNull(parseReaderRouteIntent(-1L, 20L, -1))
        assertNull(parseReaderRouteIntent(10L, -1L, -1))
        assertNull(parseReaderRouteIntent(0L, 20L, -1))
        assertNull(parseReaderRouteIntent(10L, 0L, -1))
    }

    @Test
    fun `valid route preserves manga chapter and explicit page`() {
        assertEquals(
            ReaderRouteIntent(mangaId = 10L, chapterId = 20L, page = 0),
            parseReaderRouteIntent(10L, 20L, 0),
        )
        assertEquals(
            ReaderRouteIntent(mangaId = 10L, chapterId = 20L, page = 7),
            parseReaderRouteIntent(10L, 20L, 7),
        )
    }

    @Test
    fun `missing page leaves the existing reader position policy in control`() {
        assertEquals(
            ReaderRouteIntent(mangaId = 10L, chapterId = 20L, page = null),
            parseReaderRouteIntent(10L, 20L, -1),
        )
        assertEquals(
            ReaderRouteIntent(mangaId = 10L, chapterId = 20L, page = null),
            parseReaderRouteIntent(10L, 20L, -2),
        )
    }
}
