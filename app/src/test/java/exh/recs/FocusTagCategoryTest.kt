package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// KMK --> v0.8.24: what kind of tag a Focus genre is
class FocusTagCategoryTest {

    private fun kind(tag: String) = FocusTagCategory.classify(canonicalFocusGroup(tag, emptyMap()))

    @Test
    fun `common tags land in the expected group`() {
        mapOf(
            "Action" to FocusTagCategory.GENRE, "Slice of Life" to FocusTagCategory.GENRE, "Sci-Fi" to FocusTagCategory.GENRE,
            "Reincarnation" to FocusTagCategory.STORY, "Regression" to FocusTagCategory.STORY, "System" to FocusTagCategory.STORY,
            "Academy" to FocusTagCategory.SETTING, "Martial Arts" to FocusTagCategory.SETTING, "Murim" to FocusTagCategory.SETTING,
            "Dungeons" to FocusTagCategory.SETTING, "Post-Apocalyptic" to FocusTagCategory.SETTING,
            "Demon Lord" to FocusTagCategory.CHARACTERS, "Villainess" to FocusTagCategory.CHARACTERS,
            "Female Lead" to FocusTagCategory.CHARACTERS,
            "Harem" to FocusTagCategory.MATURE, "Boys' Love" to FocusTagCategory.MATURE, "18+" to FocusTagCategory.MATURE,
            "Legendary" to FocusTagCategory.OTHER, "Ichi" to FocusTagCategory.OTHER,
        ).forEach { (tag, expected) -> assertEquals(expected, kind(tag), tag) }
    }

    @Test
    fun `mature wins over other kinds so mixed tags stay with mature tags`() {
        assertEquals(FocusTagCategory.MATURE, kind("Yaoi School"))
    }
}
// KMK <--
