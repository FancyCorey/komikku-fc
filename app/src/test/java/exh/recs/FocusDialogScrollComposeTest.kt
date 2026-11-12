package exh.recs

import android.app.Application
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xhdpi")
class FocusDialogScrollComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTextKeepsSavedModesTagsAndApplyReachable() {
        var chosen = ""
        var applied = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Focus") },
                        text = {
                            FocusTagPicker(
                                layout = FocusPickerLayout.build(listOf("adventure"), emptySet(), emptySet(), emptySet()),
                                include = emptySet(),
                                exclude = emptySet(),
                                searching = false,
                                onCycle = { chosen = it },
                                beforeTags = {
                                    item { ForYouMinChapterRow(10) {} }
                                    (1..12).forEach { index ->
                                        item(key = "saved-$index") { Text("Long saved focus mode number $index") }
                                    }
                                },
                                afterTags = { item { Text("Save as") } },
                            )
                        },
                        confirmButton = { TextButton(onClick = { applied = true }) { Text("Apply") } },
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Long saved focus mode number 12"))
        compose.onNodeWithText("Long saved focus mode number 12").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Adventure"))
        compose.onNodeWithText("Adventure").performClick()
        assertEquals("adventure", chosen)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save as"))
        compose.onNodeWithText("Save as").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed().performClick()
        assertTrue(applied)
    }
}
