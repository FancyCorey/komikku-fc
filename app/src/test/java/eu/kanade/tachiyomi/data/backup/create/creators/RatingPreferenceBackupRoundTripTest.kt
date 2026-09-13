package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import exh.util.FakePreferenceStore
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class RatingPreferenceBackupRoundTripTest {
    private val keys = listOf(
        "chapter_completion_rating_prompt_enabled",
        "confirmed_tracked_version_rating_propagation_enabled",
        "confirmed_tracked_version_local_tracking_propagation_enabled",
        "chapter_completion_rating_other_versions_prompt_enabled",
        "automatic_local_tracking_status_inference_enabled",
        "automatic_rated_group_primary_enabled",
        "rated_manga_actions_use_selection",
        "pref_auto_sync_progress_from_trackers_key",
        "pref_auto_sync_local_tracking_from_trackers_key",
        "pref_auto_create_local_tracking_from_rating_key",
        "pref_auto_inherit_local_progress_key",
        "pref_match_tracker_progress_by_reading_order_key",
    )

    @Test
    fun `stored v0_8_22 automatic behavior choices are included in the generic preference backup`() {
        val sourceStore = FakePreferenceStore()
        val sourcePreferences = SourcePreferences(sourceStore)
        sourcePreferences.chapterCompletionRatingPromptEnabled().set(false)
        sourcePreferences.confirmedTrackedVersionRatingPropagationEnabled().set(false)
        sourcePreferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().set(false)
        sourcePreferences.chapterCompletionRatingOtherVersionsPromptEnabled().set(false)
        sourcePreferences.automaticLocalTrackingStatusInferenceEnabled().set(false)
        sourcePreferences.automaticRatedGroupPrimaryEnabled().set(false)
        sourcePreferences.ratedMangaActionsUseSelection().set(false)
        val trackPreferences = TrackPreferences(sourceStore)
        trackPreferences.autoSyncProgressFromTrackers().set(false)
        trackPreferences.autoSyncLocalTrackingFromTrackers().set(false)
        trackPreferences.autoCreateLocalTrackingFromRating().set(false)
        trackPreferences.autoInheritLocalProgress().set(false)
        trackPreferences.matchTrackerProgressByReadingOrder().set(false)

        val backup = PreferenceBackupCreator(
            sourceManager = mockk<SourceManager>(relaxed = true),
            preferenceStore = sourceStore,
        ).createApp(includePrivatePreferences = false)

        val values = backup.filter { it.key in keys }.associate { it.key to (it.value as BooleanPreferenceValue).value }
        assertEquals(keys.associateWith { false }, values)

        val restoredStore = FakePreferenceStore()
        restoreBooleanPreferences(backup.filter { it.key in keys }, restoredStore)
        val restored = SourcePreferences(restoredStore)
        assertEquals(false, restored.chapterCompletionRatingPromptEnabled().get())
        assertEquals(false, restored.confirmedTrackedVersionRatingPropagationEnabled().get())
        assertEquals(false, restored.confirmedTrackedVersionLocalTrackingPropagationEnabled().get())
        assertEquals(false, restored.chapterCompletionRatingOtherVersionsPromptEnabled().get())
        assertEquals(false, restored.automaticLocalTrackingStatusInferenceEnabled().get())
        assertEquals(false, restored.automaticRatedGroupPrimaryEnabled().get())
        assertEquals(false, restored.ratedMangaActionsUseSelection().get())
        val restoredTrackPreferences = TrackPreferences(restoredStore)
        assertEquals(false, restoredTrackPreferences.autoSyncProgressFromTrackers().get())
        assertEquals(false, restoredTrackPreferences.autoSyncLocalTrackingFromTrackers().get())
        assertEquals(false, restoredTrackPreferences.autoCreateLocalTrackingFromRating().get())
        assertEquals(false, restoredTrackPreferences.autoInheritLocalProgress().get())
        assertEquals(false, restoredTrackPreferences.matchTrackerProgressByReadingOrder().get())
    }

    private fun restoreBooleanPreferences(preferences: List<BackupPreference>, store: FakePreferenceStore) {
        preferences.forEach { preference ->
            store.getBoolean(preference.key, true).set((preference.value as BooleanPreferenceValue).value)
        }
    }
}
