package eu.kanade.presentation.reader

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerGracePolicy
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerPhase
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerSession
import eu.kanade.tachiyomi.ui.reader.timer.ReaderTimerWarningPolicy
import exh.recs.KmkRecsReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTimerDialogPolicyDeviceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun currentWhatsNewDocumentsTheReaderTimerStartChoices() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("**Reader timer:**"))
        assertTrue(currentSection.contains("current warning and chapter-end choices"))
    }

    @Test
    fun uncheckedFinishCurrentChapterStartsTheProductionTimerWithTheEffectivePolicy() {
        var customMinutes by mutableStateOf("1")
        var warningMinutes by mutableStateOf(emptySet<Int>())
        var finishCurrentChapter by mutableStateOf(true)
        var allowExtraChapter by mutableStateOf(false)
        val startCalls = mutableListOf<StartCall>()
        val sessionsAfterStart = mutableListOf<ReaderTimerSession>()
        val viewModelStore = ViewModelStore()
        var readerViewModel: ReaderViewModel? = null

        try {
            composeTestRule.runOnUiThread {
                val application = ApplicationProvider.getApplicationContext<App>()
                assertEquals(App::class.java, application.javaClass)
                val viewModel = ReaderViewModel(SavedStateHandle())
                viewModelStore.put("reader-timer-policy-device-test", viewModel)
                readerViewModel = viewModel
            }
            val viewModel = requireNotNull(readerViewModel)

            composeTestRule.setContent {
                TachiyomiTheme(seedColor = null) {
                    ReaderTimerDialog(
                        onDismissRequest = {},
                        session = viewModel.timerState.collectAsState().value,
                        onStart = { durationMs, warningPolicy, gracePolicy ->
                            startCalls += StartCall(durationMs, warningPolicy, gracePolicy)
                            viewModel.startTimer(durationMs, warningPolicy, gracePolicy)
                            sessionsAfterStart += viewModel.timerState.value
                        },
                        onPause = viewModel::pauseTimer,
                        onResume = viewModel::resumeTimer,
                        onReset = viewModel::resetTimer,
                        onStop = viewModel::stopTimer,
                        customMinutes = customMinutes,
                        warnMinutesSelected = warningMinutes,
                        finishCurrentChapter = finishCurrentChapter,
                        allowExtraChapter = allowExtraChapter,
                        onDraftChange = { custom, warnings, finish, extra ->
                            customMinutes = custom
                            warningMinutes = warnings
                            finishCurrentChapter = finish
                            allowExtraChapter = extra
                        },
                        onConfigureSchedule = {},
                    )
                }
            }

            val finishCurrentControl = composeTestRule.onNode(
                hasText("Let me finish the current chapter") and isToggleable(),
            )
            finishCurrentControl.performScrollTo().assertIsOn().performClick().assertIsOff()
            composeTestRule.runOnIdle {
                assertFalse(finishCurrentChapter)
                assertEquals(0, startCalls.size)
            }

            composeTestRule.onNodeWithText("Start").performScrollTo().assertIsEnabled().performClick()
            composeTestRule.runOnIdle {
                assertEquals(1, startCalls.size)
                val call = startCalls.single()
                assertEquals(60_000L, call.durationMs)
                assertEquals(emptySet<Int>(), call.warningPolicy.minutesBeforeExpiry)
                assertFalse(call.gracePolicy.finishCurrentChapter)
                assertFalse(call.gracePolicy.allowExtraChapter)

                val session = sessionsAfterStart.single()
                assertEquals(ReaderTimerPhase.RUNNING, session.phase)
                assertEquals(60_000L, session.totalDurationMs)
                assertEquals(emptySet<Int>(), session.warningPolicy.minutesBeforeExpiry)
                assertFalse(session.gracePolicy.finishCurrentChapter)
                assertFalse(session.gracePolicy.allowExtraChapter)
            }
        } finally {
            composeTestRule.runOnUiThread {
                try {
                    readerViewModel?.stopTimer()
                } finally {
                    viewModelStore.clear()
                }
            }
        }
    }

    @Test
    fun presetStartUsesTheLatestWarningAndGraceDraftAfterRecomposition() {
        var customMinutes by mutableStateOf("1")
        var warningMinutes by mutableStateOf(emptySet<Int>())
        var finishCurrentChapter by mutableStateOf(true)
        var allowExtraChapter by mutableStateOf(false)
        val startCalls = mutableListOf<StartCall>()

        composeTestRule.setContent {
            TachiyomiTheme(seedColor = null) {
                ReaderTimerDialog(
                    onDismissRequest = {},
                    session = ReaderTimerSession(),
                    onStart = { durationMs, warningPolicy, gracePolicy ->
                        startCalls += StartCall(durationMs, warningPolicy, gracePolicy)
                    },
                    onPause = {},
                    onResume = {},
                    onReset = {},
                    onStop = {},
                    customMinutes = customMinutes,
                    warnMinutesSelected = warningMinutes,
                    finishCurrentChapter = finishCurrentChapter,
                    allowExtraChapter = allowExtraChapter,
                    onDraftChange = { custom, warnings, finish, extra ->
                        customMinutes = custom
                        warningMinutes = warnings
                        finishCurrentChapter = finish
                        allowExtraChapter = extra
                    },
                    onConfigureSchedule = {},
                )
            }
        }

        composeTestRule.onNode(
            hasText("5 minutes remaining") and isToggleable(),
        ).performScrollTo().performClick()
        composeTestRule.onNode(
            hasText("Let me finish the current chapter") and isToggleable(),
        ).performScrollTo().assertIsOn().performClick().assertIsOff()
        composeTestRule.runOnIdle {
            assertFalse(finishCurrentChapter)
            assertEquals(setOf(5), warningMinutes)
            assertEquals(0, startCalls.size)
        }

        composeTestRule.onNodeWithText("15 min").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, startCalls.size)
            val call = startCalls.single()
            assertEquals(15 * 60_000L, call.durationMs)
            assertEquals(setOf(5), call.warningPolicy.minutesBeforeExpiry)
            assertFalse(call.gracePolicy.finishCurrentChapter)
            assertFalse(call.gracePolicy.allowExtraChapter)
        }
    }

    private data class StartCall(
        val durationMs: Long,
        val warningPolicy: ReaderTimerWarningPolicy,
        val gracePolicy: ReaderTimerGracePolicy,
    )
}
