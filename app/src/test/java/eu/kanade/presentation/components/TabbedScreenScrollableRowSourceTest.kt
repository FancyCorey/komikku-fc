package eu.kanade.presentation.components

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK v0.8.25 -->
/** With five Browse tabs a fixed row cut "Sources" and "Extensions" short on a phone; more than four tabs scroll instead. */
class TabbedScreenScrollableRowSourceTest {
    private val source = File("src/main/java/eu/kanade/presentation/components/TabbedScreen.kt").readText().replace("\r\n", "\n")

    @Test
    fun `more than four tabs scroll and four or fewer keep the fixed row`() {
        assertTrue(source.contains("if (tabs.size > FIXED_TAB_ROW_MAX) {\n                PrimaryScrollableTabRow("))
        assertTrue(source.contains("private const val FIXED_TAB_ROW_MAX = 4"))
        assertTrue(source.contains("PrimaryTabRow("), "screens with few tabs look as before")
    }
}
// KMK <--
