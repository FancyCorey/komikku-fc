package exh.recs.loved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.MangaTaste

class RatedSelectionActionsTest {

    private fun item(key: RatedMangaKey, groupId: String? = null, confirmed: Boolean = false) = LovedDisplayItem(
        taste = MangaTaste(key.source, key.source, key.url, key.url, 2, 0L, 0L),
        manga = null,
        versionCount = 1,
        confirmedGroupId = groupId,
        memberKeys = listOf(key),
        hasConfirmedGroup = confirmed,
    )

    @Test
    fun `overflow and More share the same applicable action model`() {
        val selected = listOf(
            item(RatedMangaKey(1L, "/a"), groupId = "group-a", confirmed = true),
            item(RatedMangaKey(2L, "/b"), groupId = "group-a", confirmed = true),
        )

        val overflow = ratedSelectionActions(selected, selectedCount = 2, selectedGroupId = "group-a", isBulkRatingActionInProgress = false)
        val more = ratedSelectionActions(selected, selectedCount = 2, selectedGroupId = "group-a", isBulkRatingActionInProgress = false)

        assertEquals(overflow, more)
        assertTrue(RatedSelectionActionKind.MergeGroups in overflow.map { it.kind })
    }

    @Test
    fun `merge action is absent until at least two entries are selected`() {
        val actions = ratedSelectionActions(
            selectedItems = listOf(item(RatedMangaKey(1L, "/a"))),
            selectedCount = 1,
            selectedGroupId = null,
            isBulkRatingActionInProgress = false,
        )

        assertTrue(RatedSelectionActionKind.MergeGroups !in actions.map { it.kind })
    }
}
