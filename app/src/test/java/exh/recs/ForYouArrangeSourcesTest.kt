package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.25: arranging the For You sources on the page itself. */
class ForYouArrangeSourcesTest {

    @Test
    fun `leaving the mode refreshes only when the stored order changed`() {
        assertFalse(ForYouSourceArrangement.changed("1,2,3", "1,2,3"))
        assertFalse(ForYouSourceArrangement.changed("1, 2 ,3", "1,2,3"), "spacing is not a change")
        assertFalse(ForYouSourceArrangement.changed("", ""))
        assertTrue(ForYouSourceArrangement.changed("1,2,3", "2,1,3"))
        assertTrue(ForYouSourceArrangement.changed("", "1,2"), "a first order is a change")
        assertTrue(ForYouSourceArrangement.changed("1,2,3", "1,2"))
    }

    private val tab = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText()
    private val panel = File("src/main/java/exh/recs/ForYouArrangeSources.kt").readText()

    @Test
    fun `the For You menu offers Arrange sources and the settings model exists only while arranging`() {
        assertTrue(tab.contains("title = stringResource(KMR.strings.rec_arrange_sources)"))
        assertTrue(tab.contains("onClick = { arrangingSources = true }"))
        assertTrue(tab.contains("var arrangingSources by rememberSaveable { mutableStateOf(false) }"))
        assertEquals(1, Regex("RecommendationsSettingsScreenModel\\(\\)").findAll(tab).count())
        val arranging = tab.substringAfter("if (arrangingSources) {").substringBefore("} else Box(")
        assertTrue(arranging.contains("rememberScreenModel { exh.recs.settings.RecommendationsSettingsScreenModel() }"))
        assertTrue(arranging.contains("if (orderChanged) screenModel.refresh()"))
    }

    @Test
    fun `the panel reuses the Settings rows and the Settings model for ordering`() {
        assertTrue(panel.contains("SourcePriorityItem("))
        assertTrue(panel.contains("screenModel.setSourceOrder(sourcesState.map { it.id })"))
        assertTrue(panel.contains("screenModel.toggleSource(source.id)"))
        assertTrue(panel.contains("BackHandler(onBack = finish)"), "Back leaves the mode the same way as Done")
        assertTrue(panel.contains("onMoveUp") && panel.contains("onMoveDown"), "reordering has a non-drag alternative")
    }
}
