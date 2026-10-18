package exh.recs.matching

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import tachiyomi.domain.taste.model.MangaRating

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class ConfirmedGroupRatingControlComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadingDisablesControlThenSelectionAndClearAreDelivered() {
        var loading by mutableStateOf(true)
        var updating by mutableStateOf(false)
        var selected: MangaRating? = MangaRating.LIKE
        val options = listOf(MangaRating.LOVE to "Love", MangaRating.LIKE to "Like")

        composeTestRule.setContent {
            ConfirmedGroupRatingControl(
                loading = loading,
                updating = updating,
                buttonLabel = "Group rating: ${if (loading) "Loading" else "Like"}",
                notRatedLabel = "Not rated",
                ratingOptions = options,
                onSetRating = { selected = it },
            )
        }

        composeTestRule.onNodeWithText("Group rating: Loading").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.runOnIdle { loading = false }
        composeTestRule.onNodeWithText("Group rating: Like").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Love").assertIsDisplayed().performClick()
        assertEquals(MangaRating.LOVE, selected)

        composeTestRule.onNodeWithText("Group rating: Like").performClick()
        composeTestRule.onNodeWithText("Not rated").assertIsDisplayed().performClick()
        assertEquals(null, selected)

        composeTestRule.runOnIdle { updating = true }
        composeTestRule.onNodeWithText("Group rating: Like").assertIsNotEnabled()
    }
}
