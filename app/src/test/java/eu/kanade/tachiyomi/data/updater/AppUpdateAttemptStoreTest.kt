package eu.kanade.tachiyomi.data.updater

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AppUpdateAttemptStoreTest {
    private val store by lazy {
        AppUpdateAttemptStore(ApplicationProvider.getApplicationContext())
    }

    @Before
    fun resetAttempt() {
        store.clear()
    }

    @Test
    fun `obsolete callbacks cannot run effects after a replacement attempt`() {
        store.begin("new-attempt")
        var handled = false

        val accepted = store.handleCallback("old-attempt", 12, terminal = true) {
            handled = true
        }

        assertFalse(accepted)
        assertFalse(handled)
    }

    @Test
    fun `run-if-current suppresses effects from an obsolete malformed callback`() {
        store.begin("new-attempt")
        var handled = false

        val accepted = store.runIfCurrentAndRun("old-attempt") {
            handled = true
        }

        assertFalse(accepted)
        assertFalse(handled)
    }

    @Test
    fun `terminal callback is handled once while pending action remains repeatable`() {
        store.begin("current-attempt")
        var effects = 0

        assertTrue(store.handleCallback("current-attempt", 13, terminal = false) { effects++ })
        assertTrue(store.handleCallback("current-attempt", 13, terminal = false) { effects++ })
        assertTrue(store.handleCallback("current-attempt", 13, terminal = true) { effects++ })
        assertFalse(store.handleCallback("current-attempt", 13, terminal = true) { effects++ })

        assertEquals(3, effects)
    }

    @Test
    fun `legacy worker can only adopt when no newer attempt owns the updater`() {
        store.begin("current-attempt")

        assertFalse(store.adoptIfEmpty("legacy-old-worker"))
        assertTrue(store.adoptIfEmpty("current-attempt"))
    }
}
