package eu.kanade.presentation.more.settings.screen.about

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class AboutScreenStatePersistenceTest {
    @Test
    fun `about screen does not expose a companion object through readResolve`() {
        val source = File(
            "src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt",
        ).readText()

        assertFalse(
            source.contains("private fun readResolve(): Any = AboutScreen"),
            "AboutScreen is a class with a companion; returning AboutScreen here parcels the companion object",
        )
    }
}
