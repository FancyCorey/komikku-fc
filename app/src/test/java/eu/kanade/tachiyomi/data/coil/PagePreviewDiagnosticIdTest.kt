package eu.kanade.tachiyomi.data.coil

import eu.kanade.domain.manga.model.PagePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePreviewDiagnosticIdTest {

    @Test
    fun `trace id is stable and does not expose image url`() {
        val page = PagePreview(index = 4, imageUrl = "https://example.test/private/page.jpg?token=secret", source = 42)

        val traceId = page.previewTraceId()

        assertEquals(16, traceId.length)
        assertEquals(traceId, page.previewTraceId())
        assertFalse(traceId.contains("example"))
        assertFalse(traceId.contains("secret"))
    }

    @Test
    fun `trace id changes when image request changes`() {
        val page = PagePreview(index = 4, imageUrl = "https://example.test/page-a.jpg", source = 42)

        assertFalse(page.previewTraceId() == page.copy(imageUrl = "https://example.test/page-b.jpg").previewTraceId())
    }

    @Test
    fun `error trace reports exception types without messages`() {
        val error = IllegalStateException("private response text", IllegalArgumentException("secret"))

        assertEquals("IllegalStateException->IllegalArgumentException", error.safeTypeChain())
        assertFalse(error.safeTypeChain().contains("private"))
        assertFalse(error.safeTypeChain().contains("secret"))
    }

    @Test
    fun `generic preview request fallback is limited to source linkage failures`() {
        assertTrue(shouldFallbackToGenericPreviewRequest(NoClassDefFoundError("missing extension class")))
        assertFalse(shouldFallbackToGenericPreviewRequest(java.io.IOException("HTTP 403")))
        assertFalse(shouldFallbackToGenericPreviewRequest(IllegalStateException("source request failed")))
    }
}
