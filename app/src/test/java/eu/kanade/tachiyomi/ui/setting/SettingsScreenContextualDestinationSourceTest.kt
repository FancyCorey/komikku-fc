package eu.kanade.tachiyomi.ui.setting

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SettingsScreenContextualDestinationSourceTest {

    private val source by lazy {
        File("src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsScreen.kt").readText()
    }

    @Test
    fun `tracking shortcut bypasses the tablet settings sidebar`() {
        assertTrue(source.contains("if (!isTabletUi() || destination == Destination.Tracking.id)"))
        assertTrue(source.contains("screen = when (destination)"))
    }
}
