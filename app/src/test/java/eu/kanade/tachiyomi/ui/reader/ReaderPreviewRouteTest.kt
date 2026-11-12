package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Wiring checks complement the real ViewModel preview/normal request regression. */
class ReaderPreviewRouteTest {
    @Test
    fun `both handback chapter actions stay in the comparison fullscreen owner`() {
        val source = File("src/main/java/exh/recs/bestversion/BestVersionCompareScreen.kt").readText()
        val actions = source.split("onOpenReader = { manga, chapter ->").drop(1)
        assertEquals(2, actions.size)
        actions.forEach { action ->
            val handback = action.substringBefore("} else {")
            assertTrue(handback.contains("if (returnSelection)"))
            assertTrue(handback.contains("screenModel.selectManualChapter(key, chapter)"))
            assertTrue(handback.contains("screenModel.openFullscreenCandidate(key)"))
            assertFalse(handback.contains("startActivity"))
            assertFalse(handback.contains("updateMangaFromRemote"))
        }
    }
}
