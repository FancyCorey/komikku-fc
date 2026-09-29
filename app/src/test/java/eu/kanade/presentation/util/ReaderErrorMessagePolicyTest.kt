package eu.kanade.presentation.util

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.RecoverableSourceRuntimeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

class ReaderErrorMessagePolicyTest {
    @Test
    fun `unknown reader errors use a page message instead of recommendation wording`() {
        assertEquals(
            ReaderErrorMessageKind.PAGE_LOAD,
            ReaderErrorMessagePolicy.classify(IllegalStateException("synthetic-reader-error")),
        )
    }

    @Test
    fun `network HTTP and recoverable extension failures keep their useful categories`() {
        assertEquals(
            ReaderErrorMessageKind.NETWORK,
            ReaderErrorMessagePolicy.classify(UnknownHostException("offline")),
        )
        assertEquals(
            ReaderErrorMessageKind.NETWORK,
            ReaderErrorMessagePolicy.classify(IOException("socket failure")),
        )
        assertEquals(
            ReaderErrorMessageKind.FILE_ACCESS,
            ReaderErrorMessagePolicy.classify(FileNotFoundException("missing local page")),
        )
        assertEquals(
            ReaderErrorMessageKind.HTTP,
            ReaderErrorMessagePolicy.classify(HttpException(503)),
        )
        val extensionFailure = RecoverableSourceRuntimeException(
            NoClassDefFoundError("synthetic-extension-linkage"),
        )
        assertEquals(
            ReaderErrorMessageKind.EXTENSION_INCOMPATIBLE,
            ReaderErrorMessagePolicy.classify(extensionFailure),
        )
    }

    @Test
    fun `cyclic exception causes stop at the last distinct cause`() {
        val outer = IllegalStateException("outer")
        val networkFailure = IOException("network")
        outer.initCause(networkFailure)
        networkFailure.initCause(outer)

        assertEquals(ReaderErrorMessageKind.NETWORK, ReaderErrorMessagePolicy.classify(outer))
    }
}
