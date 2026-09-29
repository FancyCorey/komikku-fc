package exh.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrResourcePolicyTest {

    @Test
    fun `pixel policy rejects oversized images before decode`() {
        assertTrue(OcrResourcePolicy.isPixelCountAllowed(2000, 20000))
        assertFalse(OcrResourcePolicy.isPixelCountAllowed(5000, 10000))
        assertFalse(OcrResourcePolicy.isPixelCountAllowed(0, 100))
    }
}
