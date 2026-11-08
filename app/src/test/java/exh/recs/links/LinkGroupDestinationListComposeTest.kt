package exh.recs.links

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.domain.taste.model.CrossSourceMangaLink

// KMK v0.8.25 -->
/** Move and Merge: a scrollable list of groups where the one chosen is visibly and audibly selected. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class LinkGroupDestinationListComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun group(id: String, title: String) = LinkGroup(
        groupId = id,
        links = listOf(CrossSourceMangaLink(1L, "/$id-1", id, title, 1L, 1L), CrossSourceMangaLink(2L, "/$id-2", id, title, 1L, 1L)),
    )

    @Test
    fun choosingAGroupMarksItSelectedAndOnlyThatOne() {
        var chosen by mutableStateOf<String?>(null)
        val groups = listOf(group("g1", "Solo Leveling"), group("g2", "Omniscient Reader"))
        composeTestRule.setContent { LinkGroupDestinationList(groups, chosen) { chosen = it } }

        composeTestRule.onNodeWithText("Solo Leveling").assertIsNotSelected()
        composeTestRule.onNodeWithText("Omniscient Reader").performClick()

        assertEquals("g2", chosen)
        composeTestRule.onNode(hasText("Omniscient Reader"), useUnmergedTree = false).assertIsSelected()
        composeTestRule.onNodeWithText("Solo Leveling").assertIsNotSelected()
    }

    @Test
    fun aGroupWhoseMainTitleIsEmptyStillShowsAName() {
        val group = LinkGroup(
            groupId = "g",
            links = listOf(CrossSourceMangaLink(1L, "/a", "g", "", 1L, 1L), CrossSourceMangaLink(2L, "/b", "g", "Solo Leveling", 1L, 1L)),
        )
        assertEquals("Solo Leveling", group.primaryTitle)
    }

    @Test
    fun largeTextKeepsEveryGroupReadableAndSelectable() {
        var chosen by mutableStateOf<String?>(null)
        val groups = (1..6).map { group("g$it", "A rather long group title number $it") }
        composeTestRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 2f),
            ) {
                LinkGroupDestinationList(groups, chosen) { chosen = it }
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("A rather long group title number 6"))
        composeTestRule.onNodeWithText("A rather long group title number 6").performClick()

        assertEquals("g6", chosen)
        composeTestRule.onNodeWithText("A rather long group title number 6").assertIsSelected()
    }

    @Test
    fun aLongListScrollsToTheLastGroup() {
        var chosen by mutableStateOf<String?>(null)
        val groups = (1..40).map { group("g$it", "Group $it") }
        composeTestRule.setContent { LinkGroupDestinationList(groups, chosen) { chosen = it } }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Group 40"))
        composeTestRule.onNodeWithText("Group 40").performClick()

        assertEquals("g40", chosen)
    }
}
// KMK <--
