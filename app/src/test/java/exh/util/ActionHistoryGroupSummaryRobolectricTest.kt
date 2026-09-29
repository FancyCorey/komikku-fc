package exh.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class ActionHistoryGroupSummaryRobolectricTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ActionHistoryRegistry.clearAll()
    }

    @Test
    fun `add member history summary selects singular and plural resources from registry snapshot`() {
        assertRegistrySummary(
            action = GroupJournalActionType.ADD_MEMBER,
            count = 1,
            expected = "1 version added to a group",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.ADD_MEMBER,
            count = 2,
            expected = "2 versions added to a group",
        )
    }

    @Test
    fun `move member history summary selects singular and plural resources from registry snapshot`() {
        assertRegistrySummary(
            action = GroupJournalActionType.MOVE_MEMBER,
            count = 1,
            expected = "1 version moved to another group",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.MOVE_MEMBER,
            count = 2,
            expected = "2 versions moved to another group",
        )
    }

    @Test
    fun `merge group history summary selects singular and plural resources from registry snapshot`() {
        assertRegistrySummary(
            action = GroupJournalActionType.MERGE,
            count = 1,
            expected = "1 version merged into a group",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.MERGE,
            count = 2,
            expected = "2 versions merged into a group",
        )
    }

    @Test
    fun `remove and ungroup history summaries select singular and plural resources from registry snapshot`() {
        assertRegistrySummary(
            action = GroupJournalActionType.REMOVE_FROM_GROUP,
            count = 1,
            expected = "1 version removed from a group",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.REMOVE_FROM_GROUP,
            count = 2,
            expected = "2 versions removed from a group",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.UNGROUP,
            count = 1,
            expected = "1 version ungrouped",
        )
        assertRegistrySummary(
            action = GroupJournalActionType.UNGROUP,
            count = 2,
            expected = "2 versions ungrouped",
        )
    }

    private fun assertRegistrySummary(
        action: GroupJournalActionType,
        count: Int,
        expected: String,
    ) {
        val entryId = "${action.name.lowercase()}-$count"
        val keys = (1..count).map { RatedLinkKey(it.toLong(), "/manga/$it") }.toSet()
        val previousLinks: Map<RatedLinkKey, GroupLinkSnapshot?> = if (action == GroupJournalActionType.ADD_MEMBER) {
            keys.associateWith { null }
        } else {
            keys.associateWith { key -> snapshot(key, "old-group") }
        }
        val entry = GroupJournalEntry(
            id = entryId,
            timestamp = count.toLong(),
            actionType = action,
            touchedKeys = keys,
            previousLinks = previousLinks,
            expectedPostLinks = keys.associateWith { key ->
                if (action == GroupJournalActionType.REMOVE_FROM_GROUP || action == GroupJournalActionType.UNGROUP) {
                    null
                } else {
                    snapshot(key, "new-group")
                }
            },
            touchedGroupIds = setOf("old-group", "new-group"),
            previousPrimaries = emptyMap(),
            expectedPostPrimaries = emptyMap(),
        )
        GroupUndoJournal.record(entry)

        val row = ActionHistoryRegistry.snapshot().single { it.id == "group:$entryId" }

        assertEquals(expected, row.summary(context))
        assertEquals(expected, row.evaluationModeSummary?.invoke(context))
    }

    private fun snapshot(key: RatedLinkKey, groupId: String) = GroupLinkSnapshot(
        source = key.source,
        url = key.url,
        groupId = groupId,
        title = "Manga ${key.url}",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
