package exh.recs.matching

import exh.recs.links.LibraryGroupSuggestionPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.io.File

// KMK v0.8.25 -->
/** Track other versions offers the groups you already have for a title, so a version joins them instead of a new group. */
class ExistingGroupJoinSourceTest {
    private val model = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()

    @Test
    fun `a title matches an existing group by its name or another name, the way Group library automatically does`() {
        val manga = Manga.create().copy(source = 9L, url = "/x", ogTitle = "Solo Leveling!")
        val groups = listOf(
            LibraryGroupSuggestionPlanner.GroupNames("g1", "Na Honjaman Level Up", setOf("Na Honjaman Level Up", "Solo Leveling")),
            LibraryGroupSuggestionPlanner.GroupNames("g2", "Omniscient Reader", setOf("Omniscient Reader")),
        )
        val suggestion = LibraryGroupSuggestionPlanner.plan(listOf(manga), groups).single()
        assertEquals("g1", (suggestion.target as LibraryGroupSuggestionPlanner.Target.Group).groupId)
    }

    @Test
    fun `the section is only for a manga that is not in a group, and joining checks Not the same manga before writing`() {
        val load = model.substringAfter("private suspend fun loadExistingGroups(").substringBefore("fun joinExistingGroup(")
        assertTrue(load.contains("if (getCrossSourceMangaLinks.awaitBySourceUrl(manga.source, manga.url) != null) return"))
        val join = model.substringAfter("fun joinExistingGroup(").substringBefore("// KMK <--")
        assertTrue(join.indexOf("rejectedAmong(") in 1 until join.indexOf("upsertCrossSourceMangaLinks.await("))
        assertTrue(join.contains("ConfirmedGroupJoinSynchronizer().join(group.groupId"), "the group's rating and progress come with it")
    }
}
// KMK <--
