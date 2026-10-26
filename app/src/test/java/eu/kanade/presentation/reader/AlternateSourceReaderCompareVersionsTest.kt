package eu.kanade.presentation.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderCandidateRow
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderLoadState
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderOpaqueToken
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderPresentation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v0.8.25: the reader's source chooser can open Best Version to compare the versions and pick one to read from. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [30],
    qualifiers = "en-rUS-w360dp-h640dp-xxhdpi",
)
class AlternateSourceReaderCompareVersionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setChooser(onCompareVersions: (() -> Unit)?) {
        composeTestRule.setContent {
            MaterialTheme {
                AlternateSourceReaderDialog(
                    presentation = AlternateSourceReaderPresentation.ChoosingSource(
                        content = AlternateSourceReaderLoadState.Content(
                            items = listOf(
                                AlternateSourceReaderCandidateRow(
                                    token = AlternateSourceReaderOpaqueToken("source-choice"),
                                    primaryLabel = "Some manga",
                                    secondaryLabel = "Source A",
                                ),
                            ),
                            hasPartialFailure = false,
                        ),
                        currentSourceLabel = "Source R",
                    ),
                    onDismiss = {},
                    onSearchMore = {},
                    onSearchInApp = {},
                    onCompareVersions = onCompareVersions,
                    onSelectSource = {},
                    onConfirmSource = {},
                    onSelectChapter = {},
                    onConfirmChapter = {},
                    onConfirmPair = {},
                    onConfirmProvisional = {},
                    onConfirmSkip = {},
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun `the chooser offers Compare versions next to the search actions and calls back`() {
        var calls = 0
        setChooser(onCompareVersions = { calls++ })

        composeTestRule.onNodeWithText("Compare versions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search another title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search more sources").assertIsDisplayed()
        composeTestRule.onNodeWithText("Compare versions").performClick()

        assertEquals(1, calls)
    }

    @Test
    fun `without a callback the chooser has no Compare versions button`() {
        setChooser(onCompareVersions = null)

        assertEquals(0, composeTestRule.onAllNodes(hasText("Compare versions")).fetchSemanticsNodes().size)
    }
}
