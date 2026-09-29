package eu.kanade.tachiyomi.ui.manga.track

import eu.kanade.tachiyomi.data.track.TrackerRegistrationResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TrackerRegistrationReceiptTest {

    @Test
    fun `remote accepted local save failure is returned without recording linked history`() = runTest {
        var receiptCount = 0
        val result = registerAndRecordTrackerBinding(
            register = { TrackerRegistrationResult.RemoteAcceptedLocalSaveFailed },
            record = { receiptCount++ },
            onReceiptFailure = { throw AssertionError("receipt must not be attempted", it) },
        )

        assertEquals(TrackerRegistrationResult.RemoteAcceptedLocalSaveFailed, result)
        assertEquals(0, receiptCount)
    }

    @Test
    fun `a fully linked result records history once`() = runTest {
        var receiptCount = 0

        val result = registerAndRecordTrackerBinding(
            register = { TrackerRegistrationResult.Linked },
            record = { receiptCount++ },
            onReceiptFailure = { throw AssertionError("unexpected receipt failure", it) },
        )

        assertEquals(TrackerRegistrationResult.Linked, result)
        assertEquals(1, receiptCount)
    }

    @Test
    fun `receipt failure does not turn an existing local link into a retryable bind failure`() = runTest {
        val receiptFailure = IllegalStateException("journal write failed")
        var reportedFailure: Exception? = null

        val result = registerAndRecordTrackerBinding(
            register = { TrackerRegistrationResult.Linked },
            record = { throw receiptFailure },
            onReceiptFailure = { reportedFailure = it },
        )

        assertEquals(TrackerRegistrationResult.Linked, result)
        assertSame(receiptFailure, reportedFailure)
    }

    @Test
    fun `receipt reporting failure does not turn an existing local link into a retryable bind failure`() = runTest {
        val result = registerAndRecordTrackerBinding(
            register = { TrackerRegistrationResult.Linked },
            record = { throw IllegalStateException("journal write failed") },
            onReceiptFailure = { throw IllegalStateException("failure reporting failed") },
        )

        assertEquals(TrackerRegistrationResult.Linked, result)
    }
}
