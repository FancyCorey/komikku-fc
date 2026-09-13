package eu.kanade.domain.track.service

import exh.util.FakePreferenceStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackPreferencesTest {

    @Test
    fun `local progress inheritance is enabled by default and durable`() {
        val preferences = TrackPreferences(FakePreferenceStore())

        assertFalse(preferences.autoUpdateTrack().get())
        assertTrue(preferences.autoSyncProgressFromTrackers().get())
        assertTrue(preferences.autoSyncLocalTrackingFromTrackers().get())
        assertTrue(preferences.autoCreateLocalTrackingFromRating().get())
        assertTrue(preferences.autoInheritLocalProgress().get())
        assertTrue(preferences.matchTrackerProgressByReadingOrder().get())

        preferences.autoInheritLocalProgress().set(false)
        preferences.matchTrackerProgressByReadingOrder().set(false)

        assertFalse(preferences.autoInheritLocalProgress().get())
        assertFalse(preferences.matchTrackerProgressByReadingOrder().get())
    }
}
