package eu.kanade.presentation.more.settings.screen

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class V0822TrackingSettingsContractTest {

    @Test
    fun `every v0_8_22 automatic tracking choice remains visible in Tracking settings`() {
        val screen = File("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt").readText()
        val expectedPreferences = listOf(
            "autoSyncProgressFromTrackers()",
            "autoSyncLocalTrackingFromTrackers()",
            "autoCreateLocalTrackingFromRating()",
            "autoInheritLocalProgress()",
            "matchTrackerProgressByReadingOrder()",
        )
        val expectedSummaries = listOf(
            "pref_auto_sync_progress_from_trackers_summary",
            "pref_auto_sync_local_tracking_from_trackers_summary",
            "pref_auto_create_local_tracking_from_rating_summary",
            "pref_auto_inherit_local_progress_summary",
            "pref_match_tracker_progress_by_reading_order_summary",
        )

        expectedPreferences.forEach { preference ->
            assertTrue(preference in screen, "Tracking settings omitted $preference")
        }
        expectedSummaries.forEach { summary ->
            assertTrue(summary in screen, "Tracking settings omitted the explanation $summary")
        }
    }

    @Test
    fun `Tracking settings remain part of searchable app settings`() {
        val search = File("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt").readText()
        assertTrue("SettingsTrackingScreen" in search)
    }
}
