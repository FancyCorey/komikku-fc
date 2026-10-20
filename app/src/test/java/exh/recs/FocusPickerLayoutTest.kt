package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.TasteProfile

// KMK --> v0.8.24: Focus picker sections, the user's tags and plural merges
class FocusPickerLayoutTest {

    @Test
    fun `genres are grouped by kind after your tags, with tags shown now first`() {
        val layout = FocusPickerLayout.build(
            options = listOf("academy", "action", "comedy", "harem", "legendary", "murim", "regression", "romance", "webtoon", "yaoi"),
            preferred = setOf("murim"),
            shownNow = setOf("romance"),
            selected = setOf("yaoi"),
        )
        assertEquals(listOf("murim", "yaoi"), layout.yourTags, "liked and selected tags first")
        val byCategory = layout.categories.toMap()
        assertEquals(listOf("romance", "action", "comedy"), byCategory[FocusTagCategory.GENRE], "shown now first, then a-z")
        assertEquals(listOf("regression"), byCategory[FocusTagCategory.STORY])
        assertEquals(listOf("academy"), byCategory[FocusTagCategory.SETTING])
        assertEquals(listOf("harem"), byCategory[FocusTagCategory.MATURE])
        assertEquals(listOf("legendary"), byCategory[FocusTagCategory.OTHER])
        assertEquals(FocusTagCategory.displayOrder.filter { it in byCategory }, layout.categories.map { it.first })
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
