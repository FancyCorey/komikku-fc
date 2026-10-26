package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: For You's first load runs off the main thread, so Browse's own first composition is not held up by it. Source-level
 * guard: the tab passes the background dispatcher, the model uses it for [load], and host tests keep the screen scope's.
 */
class ForYouStartupSourceTest {

    private val model = File("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt").readText()
    private val tab = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText()

    @Test
    fun `the production tab runs the load on a background dispatcher`() {
        assertTrue(tab.contains("loadDispatcher = kotlinx.coroutines.Dispatchers.Default"))
    }

    @Test
    fun `the model starts load on its load dispatcher and defaults to the screen scope for host tests`() {
        assertTrue(model.contains("private val loadDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null"))
        assertEquals(
            1,
            Regex("screenModelScope\\.launch\\(loadDispatcher \\?: kotlin\\.coroutines\\.EmptyCoroutineContext\\)").findAll(model).count(),
        )
    }

    @Test
    fun `the start-up trace logs only in debug builds`() {
        val trace = File("src/main/java/exh/recs/ForYouStartupTrace.kt").readText()

        assertEquals(2, Regex("if \\(!BuildConfig\\.DEBUG\\) return").findAll(trace).count())
    }
}
