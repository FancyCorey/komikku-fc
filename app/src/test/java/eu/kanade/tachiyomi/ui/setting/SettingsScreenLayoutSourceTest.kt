package eu.kanade.tachiyomi.ui.setting

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SettingsScreenLayoutSourceTest {
    @Test
    fun `tablet settings keep the original two-panel navigator`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsScreen.kt").readText()

        assertTrue(source.contains("screen = when (destination)"))
        assertTrue(source.contains("SettingsMainScreen.Content(twoPane = true)"))
        assertTrue(source.contains("windowInsetsPadding(insets)"))
        assertTrue(source.contains("TwoPanelBox("))
    }
}
