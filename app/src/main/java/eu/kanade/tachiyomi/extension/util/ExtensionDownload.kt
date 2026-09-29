package eu.kanade.tachiyomi.extension.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.File

internal suspend fun Call.downloadExtensionTo(target: File) = coroutineScope {
    // Cancellation must close the socket without waiting for the blocked IO thread.
    val finished = CompletableDeferred<Unit>()
    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            finished.await()
        } catch (e: CancellationException) {
            this@downloadExtensionTo.cancel()
            throw e
        }
    }
    try {
        ensureActive()
        execute().use { response ->
            check(response.isSuccessful) { "Failed to download extension" }
            target.outputStream().use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        ensureActive()
    } catch (e: Exception) {
        ensureActive()
        throw e
    } finally {
        finished.complete(Unit)
    }
}
