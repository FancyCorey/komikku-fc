package eu.kanade.tachiyomi.ui.manga.track

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackerRegistrationRequestGateTest {

    @Test
    fun `a second tap is ignored while registration is still pending`() = runTest {
        val gate = TrackerRegistrationRequestGate()
        val finishRegistration = CompletableDeferred<Unit>()

        assertTrue(gate.tryBegin())
        val registration = launch {
            try {
                finishRegistration.await()
            } finally {
                gate.finish()
            }
        }
        runCurrent()

        assertFalse(gate.tryBegin())

        finishRegistration.complete(Unit)
        registration.join()
        assertTrue(gate.tryBegin())
        gate.finish()
    }

    @Test
    fun `a failed attempt releases the gate so the user can retry`() {
        val gate = TrackerRegistrationRequestGate()

        assertTrue(gate.tryBegin())
        try {
            error("simulated tracker failure")
        } catch (_: IllegalStateException) {
            gate.finish()
        }

        assertTrue(gate.tryBegin())
        gate.finish()
    }
}
