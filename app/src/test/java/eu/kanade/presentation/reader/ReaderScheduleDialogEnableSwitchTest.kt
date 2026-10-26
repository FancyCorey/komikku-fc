package eu.kanade.presentation.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.schedule.ReaderScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v0.8.25: the reader's schedule editor offers the schedule's own on/off switch, so a schedule configured from inside a
 * manga can never look active while Reader settings still has it off. The switch is part of the draft: applied on Save
 * (before the schedule is saved), discarded by Cancel, and absent where the caller has its own switch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [30],
    qualifiers = "en-rUS-w360dp-h640dp-xxhdpi",
)
class ReaderScheduleDialogEnableSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setDialog(
        initialEnabled: Boolean?,
        events: MutableList<String>,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderScheduleDialog(
                    onDismissRequest = { events += "dismiss" },
                    initialMode = ReaderScheduleMode.RESTRICTED,
                    initialWindows = emptyList(),
                    onSave = { _, _ -> events += "save" },
                    initialEnabled = initialEnabled,
                    onEnabledSave = { events += "enabled=$it" },
                )
            }
        }
    }

    @Test
    fun `an off schedule says it is off and saving with the switch on enables it before the schedule is saved`() {
        val events = mutableListOf<String>()
        setDialog(initialEnabled = false, events = events)

        composeTestRule.onNodeWithText("Enable reading schedule").assertIsDisplayed()
        composeTestRule.onNodeWithText("Off: your times are saved", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithText("Enable reading schedule").performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("enabled=true", "save", "dismiss"), events)
    }

    @Test
    fun `cancel discards the switch change`() {
        val events = mutableListOf<String>()
        setDialog(initialEnabled = false, events = events)

        composeTestRule.onNodeWithText("Enable reading schedule").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun `saving an already enabled schedule keeps it enabled`() {
        val events = mutableListOf<String>()
        setDialog(initialEnabled = true, events = events)

        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("enabled=true", "save", "dismiss"), events)
    }

    @Test
    fun `no switch is shown where the caller has its own`() {
        val events = mutableListOf<String>()
        setDialog(initialEnabled = null, events = events)

        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("save", "dismiss"), events)
        composeTestRule.onAllNodesWithTextCount("Enable reading schedule", 0)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, expected: Int) {
    assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size)
}
