package exh.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrRecognitionCallbackGateTest {

    @Test
    fun `cancellation closes the callback gate`() {
        val gate = OcrRecognitionCallbackGate()

        assertTrue(gate.isOpen())
        gate.cancel()
        assertFalse(gate.isOpen())
    }
}
