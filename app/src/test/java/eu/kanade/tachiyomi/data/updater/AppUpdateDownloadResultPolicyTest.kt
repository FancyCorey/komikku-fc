package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.network.HttpException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class AppUpdateDownloadResultPolicyTest {
    @Test
    fun `transient network failures retry below the cap`() {
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Retry,
            AppUpdateDownloadResultPolicy.classify(SocketTimeoutException(), 0, 3),
        )
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Retry,
            AppUpdateDownloadResultPolicy.classify(HttpException(503), 2, 3),
        )
    }

    @Test
    fun `permanent HTTP and non-network failures are discarded`() {
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Failure,
            AppUpdateDownloadResultPolicy.classify(HttpException(404), 0, 3),
        )
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Failure,
            AppUpdateDownloadResultPolicy.classify(IllegalArgumentException("bad APK URL"), 0, 3),
        )
    }

    @Test
    fun `transient failures are discarded at the cap`() {
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Failure,
            AppUpdateDownloadResultPolicy.classify(IOException("connection reset"), 3, 3),
        )
    }

    @Test
    fun `cancellation is never converted into a retry or failure`() {
        assertEquals(
            AppUpdateDownloadResultPolicy.Outcome.Cancelled,
            AppUpdateDownloadResultPolicy.classify(CancellationException(), 0, 3),
        )
    }
}
