package exh.recs.loved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.CrossSourceMangaLink

class LovedMangaScreenModelMergeSelectionTest {

    private fun link(source: Long, url: String, groupId: String) = CrossSourceMangaLink(
        source = source,
        url = url,
        groupId = groupId,
        title = url,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `screen model merge seam includes every member from two populated selected groups`() {
        val selected = listOf(
            RatedGroupMergePlanner.SelectedEntry(RatedMangaKey(1L, "/a"), "A", "group-b"),
            RatedGroupMergePlanner.SelectedEntry(RatedMangaKey(3L, "/c"), "C", "group-a"),
        )
        val groups = mapOf(
            "group-a" to listOf(link(3L, "/c", "group-a"), link(4L, "/d", "group-a")),
            "group-b" to listOf(link(1L, "/a", "group-b"), link(2L, "/b", "group-b")),
        )

        val plan = planRatedGroupMerge(selected, groups, now = 10L) { "unused" }

        assertNotNull(plan)
        assertEquals("group-a", plan!!.targetGroupId)
        // /d already belongs to the target group and therefore needs no rewrite; all members of
        // the merged-away group (/a and /b) must be included alongside the selected target entry.
        assertEquals(setOf("/a", "/b", "/c"), plan.writes.map { it.url }.toSet())
        assertEquals(setOf("group-a"), plan.writes.map { it.groupId }.toSet())
    }
}
