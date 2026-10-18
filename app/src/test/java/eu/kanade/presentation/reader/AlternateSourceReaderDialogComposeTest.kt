package eu.kanade.presentation.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderCandidateRow
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderLoadState
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderOpaqueToken
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderPresentation
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderRecoveryReason
import org.junit.Assert.assertEquals
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
class AlternateSourceReaderDialogComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun provisionalAlignmentWarningPrecedesTheSingleChapterConfirmation() {
        val chapterToken = AlternateSourceReaderOpaqueToken("chapter-choice")
        var presentation by mutableStateOf<AlternateSourceReaderPresentation>(
            AlternateSourceReaderPresentation.ChoosingChapter(
                sourceToken = AlternateSourceReaderOpaqueToken("source-choice"),
                content = AlternateSourceReaderLoadState.Content(
                    items = listOf(
                        AlternateSourceReaderCandidateRow(
                            token = chapterToken,
                            primaryLabel = "Chapter 15",
                            secondaryLabel = "15.0 • Alternate source",
                        ),
                    ),
                    hasPartialFailure = false,
                ),
            ),
        )
        var confirmCalls = 0
        composeTestRule.setContent {
            MaterialTheme {
                AlternateSourceReaderDialog(
                    presentation = presentation,
                    onDismiss = {},
                    onSearchMore = {},
                    onSearchInApp = {},
                    onSelectSource = {},
                    onConfirmSource = {},
                    onSelectChapter = { token ->
                        presentation = (presentation as AlternateSourceReaderPresentation.ChoosingChapter)
                            .copy(selectedToken = token)
                    },
                    onConfirmChapter = { confirmCalls++ },
                    onConfirmPair = {},
                    onConfirmProvisional = {},
                    onConfirmSkip = {},
                    onRetry = {},
                )
            }
        }

        // KMK v0.8.24: plainer wording that still states the possible misalignment, and buttons that say what they do
        composeTestRule.onNodeWithText(
            "Pick the chapter on this source that continues where you stopped. " +
                "The match may be approximate; you can correct it later from the reader menu.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Stay on this source").assertIsDisplayed()
        composeTestRule.onNodeWithText("Read this chapter").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Chapter 15").performClick()
        composeTestRule.onNodeWithText("Read this chapter").assertIsEnabled().performClick()
        assertEquals(1, confirmCalls)
    }

    @Test
    fun failedSourceChooserRetryIsVisibleScopedAndReplacementCanBeSelected() {
        val sourceToken = AlternateSourceReaderOpaqueToken("source-choice")
        val healthyToken = AlternateSourceReaderOpaqueToken("healthy-choice")
        var presentation by mutableStateOf<AlternateSourceReaderPresentation>(
            AlternateSourceReaderPresentation.ChoosingSource(
                content = AlternateSourceReaderLoadState.Failed(AlternateSourceReaderRecoveryReason.FAILED),
            ),
        )
        var retryCalls = 0
        var selectedSourceToken: AlternateSourceReaderOpaqueToken? = null
        var dismissCalls = 0
        var searchMoreCalls = 0
        var inAppSearchCalls = 0
        var selectCalls = 0
        var confirmCalls = 0

        composeTestRule.setContent {
            MaterialTheme {
                AlternateSourceReaderDialog(
                    presentation = presentation,
                    onDismiss = { dismissCalls++ },
                    onSearchMore = { searchMoreCalls++ },
                    onSearchInApp = { inAppSearchCalls++ },
                    onSelectSource = { token ->
                        selectCalls++
                        selectedSourceToken = token
                        presentation = (presentation as AlternateSourceReaderPresentation.ChoosingSource)
                            .copy(selectedToken = token)
                    },
                    onConfirmSource = { confirmCalls++ },
                    onSelectChapter = { selectCalls++ },
                    onConfirmChapter = { confirmCalls++ },
                    onConfirmPair = { confirmCalls++ },
                    onConfirmProvisional = { confirmCalls++ },
                    onConfirmSkip = { confirmCalls++ },
                    onRetry = { retryCalls++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Choose an alternate source").assertIsDisplayed()
        composeTestRule.onNodeWithText("The alternate-source action could not be completed.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("OK").assertIsNotEnabled()
        assertEquals(1, retryCalls)
        assertEquals(0, dismissCalls)
        assertEquals(0, searchMoreCalls)
        assertEquals(0, inAppSearchCalls)
        assertEquals(0, selectCalls)
        assertEquals(0, confirmCalls)

        composeTestRule.runOnIdle {
            presentation = AlternateSourceReaderPresentation.ChoosingSource(
                content = AlternateSourceReaderLoadState.Content(
                    items = listOf(
                        AlternateSourceReaderCandidateRow(
                            token = healthyToken,
                            primaryLabel = "Healthy alternate",
                            secondaryLabel = "Source One",
                        ),
                    ),
                    hasPartialFailure = false,
                ),
            )
        }

        composeTestRule.onNodeWithText("Healthy alternate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("OK").assertIsNotEnabled()
        assertEquals(1, retryCalls)
        assertEquals(0, selectCalls)
        assertEquals(0, confirmCalls)

        composeTestRule.onNodeWithText("Healthy alternate").performClick()
        composeTestRule.runOnIdle {
            assertEquals(healthyToken, selectedSourceToken)
        }
        composeTestRule.onNodeWithText("Healthy alternate").assertIsSelected()
        composeTestRule.onNodeWithText("OK").assertIsEnabled()
        assertEquals(1, selectCalls)
        assertEquals(0, confirmCalls)

        composeTestRule.runOnIdle {
            presentation = AlternateSourceReaderPresentation.ChoosingChapter(
                sourceToken = sourceToken,
                content = AlternateSourceReaderLoadState.Failed(AlternateSourceReaderRecoveryReason.FAILED),
            )
        }
        composeTestRule.onNodeWithText("Choose the matching chapter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(2, retryCalls)
        assertEquals(0, confirmCalls)
    }

    @Test
    fun partialResultsRequireManualSelectionConfirmationAndDismissal() {
        val firstToken = AlternateSourceReaderOpaqueToken("candidate-alpha")
        val secondToken = AlternateSourceReaderOpaqueToken("candidate-beta")
        var presentation by mutableStateOf<AlternateSourceReaderPresentation>(
            AlternateSourceReaderPresentation.ChoosingSource(
                content = AlternateSourceReaderLoadState.Content(
                    items = listOf(
                        AlternateSourceReaderCandidateRow(
                            token = firstToken,
                            primaryLabel = "Manga Alpha",
                            secondaryLabel = "Source Alpha",
                        ),
                        AlternateSourceReaderCandidateRow(
                            token = secondToken,
                            primaryLabel = "Manga Beta",
                            secondaryLabel = "Source Beta",
                        ),
                    ),
                    hasPartialFailure = true,
                ),
            ),
        )
        var selectedToken: AlternateSourceReaderOpaqueToken? = null
        var confirmedToken: AlternateSourceReaderOpaqueToken? = null
        var dismissCalls = 0
        var searchMoreCalls = 0
        var inAppSearchCalls = 0
        var confirmCalls = 0
        var retryCalls = 0
        val selections = mutableListOf<AlternateSourceReaderOpaqueToken>()

        composeTestRule.setContent {
            MaterialTheme {
                AlternateSourceReaderDialog(
                    presentation = presentation,
                    onDismiss = {
                        dismissCalls++
                        presentation = AlternateSourceReaderPresentation.Hidden
                    },
                    onSearchMore = { searchMoreCalls++ },
                    onSearchInApp = { inAppSearchCalls++ },
                    onSelectSource = { token ->
                        selections += token
                        selectedToken = token
                        presentation = (presentation as AlternateSourceReaderPresentation.ChoosingSource)
                            .copy(selectedToken = token)
                    },
                    onConfirmSource = {
                        confirmCalls++
                        confirmedToken = selectedToken
                    },
                    onSelectChapter = {},
                    onConfirmChapter = {},
                    onConfirmPair = {},
                    onConfirmProvisional = {},
                    onConfirmSkip = {},
                    onRetry = { retryCalls++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Some sources could not be checked. Available results are shown.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, retryCalls)
        composeTestRule.onNodeWithText("Manga Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manga Beta").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").assertIsNotEnabled()
        assertEquals(0, confirmCalls)
        assertEquals(emptyList<AlternateSourceReaderOpaqueToken>(), selections)

        composeTestRule.onNodeWithText("Manga Beta").performClick()
        composeTestRule.runOnIdle {
            assertEquals(secondToken, selectedToken)
            assertEquals(listOf(secondToken), selections)
        }
        composeTestRule.onNodeWithText("Manga Beta").assertIsSelected()
        composeTestRule.onNodeWithText("OK").assertIsEnabled()
        assertEquals(0, confirmCalls)

        composeTestRule.onNodeWithText("OK").performClick()
        assertEquals(1, confirmCalls)
        assertEquals(secondToken, confirmedToken)
        assertEquals(listOf(secondToken), selections)

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Choose an alternate source").assertDoesNotExist()
        composeTestRule.onNodeWithText("Manga Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Manga Beta").assertDoesNotExist()
        assertEquals(1, dismissCalls)
        assertEquals(1, confirmCalls)
        assertEquals(listOf(secondToken), selections)
        assertEquals(0, searchMoreCalls)
        assertEquals(0, inAppSearchCalls)
    }
}
