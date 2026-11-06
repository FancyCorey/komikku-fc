package exh.recs.links

import exh.recs.links.LibraryGroupSuggestionPlanner.Confidence
import exh.recs.links.LibraryGroupSuggestionPlanner.GroupNames
import exh.recs.links.LibraryGroupSuggestionPlanner.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LibraryGroupSuggestionPlannerTest {

    private fun manga(id: Long, title: String) = Manga.create().copy(id = id, source = id, url = "/$id", ogTitle = title)
    private val solo = GroupNames("g-solo", "Solo Leveling", setOf("Solo Leveling", "Only I Level Up"))
    private val tower = GroupNames("g-tower", "Tower of God", setOf("Tower of God", "Kami no Tou"))

    @Test
    fun `same name as a group member joins that group, ignoring case and punctuation`() {
        val s = LibraryGroupSuggestionPlanner.plan(listOf(manga(1, "SOLO  Leveling!")), listOf(solo, tower)).single()
        assertEquals(Confidence.EXACT, s.confidence)
        assertEquals(Target.Group("g-solo", "Solo Leveling"), s.target)
    }

    @Test
    fun `another name used by a group member also matches`() {
        val s = LibraryGroupSuggestionPlanner.plan(listOf(manga(1, "Kami no Tou")), listOf(solo, tower)).single()
        assertEquals(Target.Group("g-tower", "Tower of God"), s.target)
    }

    @Test
    fun `two ungrouped titles with the same name suggest a new group`() {
        val list = listOf(manga(1, "One Piece"), manga(2, "one piece"), manga(3, "Unrelated"))
        val s = LibraryGroupSuggestionPlanner.plan(list, emptyList())
        assertEquals(2, s.size)
        assertTrue(s.all { it.target is Target.NewGroup && it.confidence == Confidence.EXACT })
    }

    @Test
    fun `a close name is only a possible match and preselects nothing`() {
        val s = LibraryGroupSuggestionPlanner.plan(listOf(manga(1, "Tower of God Season 2")), listOf(solo, tower)).single()
        assertEquals(Confidence.POSSIBLE, s.confidence)
        assertNull(s.target)
        assertEquals("g-tower", s.alternatives.first().groupId)
    }

    @Test
    fun `a title matching two different groups makes the user choose`() {
        val other = GroupNames("g-other", "Other", setOf("Tower of God"))
        val s = LibraryGroupSuggestionPlanner.plan(listOf(manga(1, "Tower of God")), listOf(tower, other)).single()
        assertEquals(Confidence.POSSIBLE, s.confidence)
        assertNull(s.target)
        assertEquals(2, s.alternatives.size)
    }

    @Test
    fun `the grouping screen hides source names when technical names are hidden`() {
        val screen = java.io.File("src/main/java/exh/recs/links/BulkGroupSuggestionsScreen.kt").readText()
        assertTrue(screen.contains("rememberEvaluationModeEnabled()"))
        assertTrue(screen.contains("EvaluationModeFormatter.sourceLabel(it.manga.source)"))
    }

    @Test
    fun `unrelated titles produce no suggestion and exact ones sort first`() {
        val list = listOf(manga(1, "Tower of God Season 2"), manga(2, "Kami no Tou"), manga(3, "Something Else"))
        val s = LibraryGroupSuggestionPlanner.plan(list, listOf(solo, tower))
        assertEquals(listOf(2L, 1L), s.map { it.manga.id })
    }
}
