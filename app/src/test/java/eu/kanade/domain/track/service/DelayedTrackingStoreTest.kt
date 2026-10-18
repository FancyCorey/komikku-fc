package eu.kanade.domain.track.service

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DelayedTrackingStoreTest {

    @Test
    fun `lower satisfied progress does not remove a higher pending target`() {
        val (preferences, editor, store) = storeWithQueuedProgress(10F)

        store.removeIfAtMost(trackId = 1L, satisfiedThrough = 8.0)

        verify(exactly = 0) { preferences.edit() }
        verify(exactly = 0) { editor.remove("1") }
    }

    @Test
    fun `progress at the queued target removes the satisfied entry`() {
        val (_, editor, store) = storeWithQueuedProgress(8F)

        store.removeIfAtMost(trackId = 1L, satisfiedThrough = 8.0)

        verify { editor.remove("1") }
    }

    private fun storeWithQueuedProgress(progress: Float): Fixture {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE) } returns preferences
        every { preferences.contains("1") } returns true
        every { preferences.getFloat("1", 0F) } returns progress
        every { preferences.edit() } returns editor
        every { editor.remove("1") } returns editor

        return Fixture(preferences, editor, DelayedTrackingStore(context))
    }

    private data class Fixture(
        val preferences: SharedPreferences,
        val editor: SharedPreferences.Editor,
        val store: DelayedTrackingStore,
    )
}
