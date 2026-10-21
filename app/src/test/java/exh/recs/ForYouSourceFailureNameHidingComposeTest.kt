package exh.recs

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import exh.util.EvaluationModeFormatter
import exh.util.FakePreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class ForYouSourceFailureNameHidingComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun confirmationUsesMaskedNameAndRespondsToPreferenceToggle() {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.evaluationMode().set(true)
        Injekt.addSingleton(preferences)
        val source = testSource()
        val maskedName = EvaluationModeFormatter.sourceLabel(source.id)
        var stopCalls = 0
        composeTestRule.setContent {
            MaterialTheme {
                ForYouSourceFailureActions(
                    source = source,
                    advice = ForYouSourceFailurePolicy.Advice(ForYouSourceFailurePolicy.Cause.CONNECTION, true, 3, 3),
                    extensionObsolete = false,
                    extensionHasUpdate = false,
                    check = null,
                    onRetry = {},
                    onStopUsing = { stopCalls++ },
                    onCheck = {},
                    onManageExtension = null,
                )
            }
        }

        composeTestRule.onNodeWithText("Don't use in For You").performClick()
        composeTestRule.runOnIdle {
            val confirmation = ShadowToast.getTextOfLatestToast().toString()
            assertEquals(1, stopCalls)
            assertTrue(confirmation.contains(maskedName))
            assertFalse(confirmation.contains(source.name))
            preferences.evaluationMode().set(false)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Don't use in For You").performClick()
        composeTestRule.runOnIdle {
            assertEquals(2, stopCalls)
            assertTrue(ShadowToast.getTextOfLatestToast().toString().contains(source.name))
        }
    }

    private fun testSource(): Source = object : Source {
        override val id = 820024L
        override val name = "Private example source"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) =
            SMangaUpdate(manga, emptyList())
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
        override fun getFilterList() = FilterList()
    }
}
