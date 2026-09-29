package eu.kanade.tachiyomi.ui.manga.track

import eu.kanade.tachiyomi.data.track.TrackerRegistrationResult
import kotlinx.coroutines.CancellationException

/** Records a linked receipt only after both local persistence and provider binding succeeded. */
internal suspend fun registerAndRecordTrackerBinding(
    register: suspend () -> TrackerRegistrationResult,
    record: () -> Unit,
    onReceiptFailure: (Exception) -> Unit,
): TrackerRegistrationResult {
    val result = register()
    if (result == TrackerRegistrationResult.Linked) {
        try {
            record()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                onReceiptFailure(e)
            } catch (reportingFailure: CancellationException) {
                throw reportingFailure
            } catch (_: Exception) { /* Binding already succeeded, so a reporting failure must not offer a rebind. */ }
        }
    }
    return result
}
