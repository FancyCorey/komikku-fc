package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import exh.recs.matching.GroupJoinConfirmations
import exh.util.FakePreferenceStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GroupJoinConfirmationBackupTest {
    @Test
    fun `backup excludes legacy and app-state ownership even with private preferences enabled`() {
        val store = FakePreferenceStore()
        store.getString(GroupJoinConfirmations.LEGACY_PREFERENCE_KEY, "").set("legacy")
        store.getString(GroupJoinConfirmations.PREFERENCE_KEY, "").set("current")
        store.getString("ordinary_setting", "").set("kept")
        val creator = PreferenceBackupCreator(mockk(relaxed = true), store)
        for (includePrivate in listOf(false, true)) {
            val backup = creator.createApp(includePrivate)
            assertFalse(backup.any { GroupJoinConfirmations.isOwnershipPreference(it.key) })
            assertEquals("kept", (backup.single { it.key == "ordinary_setting" }.value as StringPreferenceValue).value)
        }
    }

    @Test
    fun `old backup settings cannot overwrite local ownership but ordinary settings still restore`() = runTest {
        val store = FakePreferenceStore()
        store.getString(GroupJoinConfirmations.PREFERENCE_KEY, "").set("current")
        val restorer = PreferenceRestorer(mockk(relaxed = true), mockk(relaxed = true), store)
        restorer.restorePreferences(
            listOf(
                BackupPreference(GroupJoinConfirmations.LEGACY_PREFERENCE_KEY, StringPreferenceValue("old")),
                BackupPreference(GroupJoinConfirmations.PREFERENCE_KEY, StringPreferenceValue("foreign")),
                BackupPreference("ordinary_setting", StringPreferenceValue("restored")),
            ),
            store,
        )
        assertEquals("current", store.getString(GroupJoinConfirmations.PREFERENCE_KEY, "").get())
        assertFalse(store.getString(GroupJoinConfirmations.LEGACY_PREFERENCE_KEY, "").isSet())
        assertEquals("restored", store.getString("ordinary_setting", "").get())
    }
}
