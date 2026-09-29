package eu.kanade.domain.track.interactor

import eu.kanade.tachiyomi.data.track.TrackerRegistrationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackerBindingFollowUpsTest {

    @Test
    fun `local persistence failure is reported and later sync is skipped after provider acceptance`() = runTest {
        val providerAcceptedBinding = true
        var followUpRan = false
        var localFailure: Exception? = null
        var syncFailure: Exception? = null
        val failure = IllegalStateException("simulated local insert failure")

        val outcome = runTrackerBindingFollowUps(
            persistLocalTrack = {
                assertTrue(providerAcceptedBinding)
                throw failure
            },
            synchronize = { followUpRan = true },
            onLocalPersistenceFailure = { localFailure = it },
            onSynchronizationFailure = { syncFailure = it },
        )

        assertSame(failure, localFailure)
        assertEquals(TrackerRegistrationResult.RemoteAcceptedLocalSaveFailed, outcome)
        assertNull(syncFailure)
        assertFalse(followUpRan)
    }

    @Test
    fun `warning callback failure does not make a remotely accepted binding retryable`() = runTest {
        val outcome = runTrackerBindingFollowUps(
            persistLocalTrack = { throw IllegalStateException("simulated local insert failure") },
            synchronize = { throw AssertionError("sync must be skipped") },
            onLocalPersistenceFailure = { throw IllegalStateException("warning UI failed") },
            onSynchronizationFailure = { throw AssertionError("unexpected sync warning") },
        )

        assertEquals(TrackerRegistrationResult.RemoteAcceptedLocalSaveFailed, outcome)
    }

    @Test
    fun `sync failure is reported after the local track has been saved`() = runTest {
        var localTrackSaved = false
        var syncFailure: Exception? = null
        val failure = IllegalStateException("simulated progress sync failure")

        val outcome = runTrackerBindingFollowUps(
            persistLocalTrack = { localTrackSaved = true },
            synchronize = {
                assertTrue(localTrackSaved)
                throw failure
            },
            onLocalPersistenceFailure = { throw AssertionError("unexpected persistence failure", it) },
            onSynchronizationFailure = { syncFailure = it },
        )

        assertTrue(localTrackSaved)
        assertEquals(TrackerRegistrationResult.Linked, outcome)
        assertSame(failure, syncFailure)
    }

    @Test
    fun `successful persistence and sync do not report partial failure`() = runTest {
        var localTrackSaved = false
        var syncRan = false

        val outcome = runTrackerBindingFollowUps(
            persistLocalTrack = { localTrackSaved = true },
            synchronize = {
                assertTrue(localTrackSaved)
                syncRan = true
            },
            onLocalPersistenceFailure = { throw AssertionError("unexpected persistence failure", it) },
            onSynchronizationFailure = { throw AssertionError("unexpected sync failure", it) },
        )

        assertTrue(syncRan)
        assertEquals(TrackerRegistrationResult.Linked, outcome)
    }

    @Test
    fun `cancellation is rethrown and is not converted to partial success`() = runTest {
        val cancellation = CancellationException("cancelled")

        try {
            runTrackerBindingFollowUps(
                persistLocalTrack = { throw cancellation },
                synchronize = {},
                onLocalPersistenceFailure = {},
                onSynchronizationFailure = {},
            )
            throw AssertionError("expected cancellation to be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
