package eu.kanade.presentation.track

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.manga.track.TrackerEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class TrackInfoDialogHomeSettingsComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun untrackedLocalRowExposesSettingsWithoutStartingLocalTracking() {
        var localClicks = 0
        var settingsClicks = 0

        composeTestRule.setContent {
            TrackInfoDialogHome(
                entries = listOf(TrackerEntry.Local(work = null)),
                dateFormat = DateTimeFormatter.ISO_LOCAL_DATE,
                onStatusClick = {},
                onChapterClick = {},
                onScoreClick = {},
                onStartDateEdit = {},
                onEndDateEdit = {},
                onNewSearch = {},
                onOpenInBrowser = {},
                onRemoved = {},
                onCopyLink = {},
                onTogglePrivate = {},
                onLocalClick = { localClicks++ },
                onLocalSettingsClick = { settingsClicks++ },
            )
        }

        composeTestRule.onNodeWithText("Track locally").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Settings")
            .assertIsDisplayed()
            .performClick()

        assertEquals("settings should be independently available for the empty Local row", 1, settingsClicks)
        assertEquals("opening settings must not create local work", 0, localClicks)
    }
}
