package eu.kanade.presentation.more.settings.screen.about

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v0.8.25: a collapsed What's New card shows only its version and summary; its notes appear when it is opened. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [30],
    qualifiers = "en-rUS-w360dp-h640dp-xxhdpi",
)
class WhatsNewVersionCardComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setCard(expandedByDefault: Boolean) {
        composeTestRule.setContent {
            MaterialTheme {
                WhatsNewVersionCard(
                    stateKey = "test-card",
                    title = "v9.9.9",
                    summary = "The one line summary",
                    markdown = "The full release notes body",
                    expandedByDefault = expandedByDefault,
                )
            }
        }
    }

    private fun count(text: String) = composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `a collapsed card shows the version and summary but does not compose its notes`() {
        setCard(expandedByDefault = false)

        composeTestRule.onNodeWithText("v9.9.9").assertIsDisplayed()
        composeTestRule.onNodeWithText("The one line summary").assertIsDisplayed()
        assertEquals(0, count("The full release notes body"))
    }

    @Test
    fun `opening a card shows its notes and hides the summary line`() {
        setCard(expandedByDefault = false)

        composeTestRule.onNodeWithText("v9.9.9").performClick()

        composeTestRule.onNodeWithText("The full release notes body").assertIsDisplayed()
        assertEquals(0, count("The one line summary"))
    }

    @Test
    fun `a card can start open`() {
        setCard(expandedByDefault = true)

        composeTestRule.onNodeWithText("The full release notes body").assertIsDisplayed()
    }
}
