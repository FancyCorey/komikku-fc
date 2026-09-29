package eu.kanade.domain.track.interactor

import eu.kanade.tachiyomi.data.track.TrackerRegistrationResult
import kotlinx.coroutines.CancellationException

/** Runs local persistence before tracker follow-up work and reports failures without inviting rebinds. */
internal suspend fun runTrackerBindingFollowUps(
    persistLocalTrack: suspend () -> Unit,
    synchronize: suspend () -> Unit,
    onLocalPersistenceFailure: suspend (Exception) -> Unit,
    onSynchronizationFailure: suspend (Exception) -> Unit,
): TrackerRegistrationResult {
    val persistenceFailure = captureTrackerBindingFollowUpFailure(persistLocalTrack)
    if (persistenceFailure != null) {
        reportFailureSafely(onLocalPersistenceFailure, persistenceFailure)
        return TrackerRegistrationResult.RemoteAcceptedLocalSaveFailed
    }

    val synchronizationFailure = captureTrackerBindingFollowUpFailure(synchronize)
    if (synchronizationFailure != null) {
        reportFailureSafely(onSynchronizationFailure, synchronizationFailure)
    }
    return TrackerRegistrationResult.Linked
}

private suspend fun reportFailureSafely(
    report: suspend (Exception) -> Unit,
    failure: Exception,
) {
    try {
        report(failure)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Reporting must not turn a remotely accepted bind into a retryable failure.
    }
}

private suspend fun captureTrackerBindingFollowUpFailure(block: suspend () -> Unit): Exception? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e
}
