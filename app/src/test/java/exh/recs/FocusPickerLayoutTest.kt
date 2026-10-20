package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.TasteProfile

// KMK --> v0.8.24: Focus picker sections, the user's tags and plural merges
class FocusPickerLayoutTest {

    @Test
    fun `genres split into your tags, tags shown now and the rest`() {
        val layout = FocusPickerLayout.build(
            options = listOf("action", "fantasy", "murim", "romance", "webtoon", "yaoi"),
            preferred = setOf("action", "murim"),
            shownNow = setOf("fantasy", "action"),
            selected = setOf("yaoi"),
        )
        assertEquals(listOf("action", "murim", "yaoi"), layout.yourTags, "liked and selected tags first")
        assertEquals(listOf("fantasy"), layout.common)
        assertEquals(listOf("romance"), layout.more)
        assertTrue(layout.dimensions.any { (dimension, groups) -> dimension == FocusDimension.FORMAT && "webtoon" in groups })
    }

    @Test
    fun `your tags come from explicit likes, recent and long-term taste, never from dislikes`() {
        val profile = TasteProfile.EMPTY.copy(
            learnedTagWeights = mapOf("action" to 3.0, "harem" to -2.0, "comedy" to 1.0),
            explicitTagPreferences = mapOf("murim" to 1, "gore" to -1),
            recentTagWeights = mapOf("fantasy" to 2.0),
        )
        assertEquals(listOf("murim", "fantasy", "action", "comedy"), FocusPickerLayout.preferredGroups(profile, emptyMap()).toList())
    }

    @Test
    fun `plural spellings merge only when both forms are present`() {
        val merged = FocusPickerLayout.withPluralMerges(
            emptyMap(),
            setOf("monster", "monsters", "witch", "witches", "glasses", "magical girl", "magical girls", "games"),
        )
        assertEquals("monster", canonicalFocusGroup("Monsters", merged))
        assertEquals("witch", canonicalFocusGroup("Witches", merged))
        assertEquals("magical girl", canonicalFocusGroup("Magical Girls", merged))
        assertEquals("glasses", canonicalFocusGroup("Glasses", merged), "no 'glasse' or 'glass' tag exists")
        assertEquals("games", canonicalFocusGroup("Games", merged), "no singular present")
    }

    @Test
    fun `a tag cycles through include, exclude and clear`() {
        var state = emptySet<String>() to emptySet<String>()
        state = cycleFocusGroup("action", state.first, state.second)
        assertEquals(setOf("action") to emptySet<String>(), state)
        state = cycleFocusGroup("action", state.first, state.second)
        assertEquals(emptySet<String>() to setOf("action"), state)
        state = cycleFocusGroup("action", state.first, state.second)
        assertEquals(emptySet<String>() to emptySet<String>(), state)
    }
}
// KMK <--
