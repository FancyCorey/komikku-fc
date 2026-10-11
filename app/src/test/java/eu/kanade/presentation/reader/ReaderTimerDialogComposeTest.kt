package eu.kanade.presentation.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerPhase
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [30],
    qualifiers = "en-rUS-w360dp-h640dp-xxhdpi",
)
class ReaderTimerDialogComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun runningDialogReachesIdleWithFixedSession() {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderTimerDialog(
                    onDismissRequest = {},
                    session = ReaderTimerSession(
                        phase = ReaderTimerPhase.RUNNING,
                        totalDurationMs = 60_000L,
                    ),
                    onStart = { _, _, _ -> },
                    onPause = {},
                    onResume = {},
                    onReset = {},
                    onStop = {},
                    customMinutes = "1",
                    warnMinutesSelected = emptySet(),
                    finishCurrentChapter = true,
                    allowExtraChapter = false,
                    onDraftChange = { _, _, _, _ -> },
                    onConfigureSchedule = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Running").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:00 remaining").assertIsDisplayed()
        listOf("Pause", "Reset", "Stop").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
        }
    }
}
