package exh.ocr

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream

class OcrStreamCancellationTest {
    @Test
    fun `stream cancellation propagates and closes input`() = runTest {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled input")
            override fun close() {
                closed = true
            }
        }
        var cancelled = false
        try {
            MlKitLatinOcrTextRecognizer(mockk<Context>()).recognizeText(OcrImageInput(stream, "page"))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(closed)
    }
}
