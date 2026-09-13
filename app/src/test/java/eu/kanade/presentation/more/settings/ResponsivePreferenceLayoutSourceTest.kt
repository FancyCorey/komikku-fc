package eu.kanade.presentation.more.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ResponsivePreferenceLayoutSourceTest {
    @Test
    fun `settings keep trailing controls beside wrapping text at every pane width`() {
        val source = File(
            "src/main/java/eu/kanade/presentation/more/settings/widget/BasePreferenceWidget.kt",
        ).readText()

        assertFalse(source.contains("BoxWithConstraints"))
        assertFalse(source.contains("CompactPreferenceWidth"))
        assertFalse(source.contains(".align(Alignment.End)"))
        assertTrue(source.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains("maxLines = 2"))
    }
}
