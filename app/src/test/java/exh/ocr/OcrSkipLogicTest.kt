package exh.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// KMK --> OCR v0.1.1

/**
 * Tests the skip-logic rules without requiring a database.
 * The service skips a page only when:
 *   - state is not null (row exists for current engine version)
 *   - page_identity matches
 *   - known content metadata matches
 *   - ocr_status == "success"
 *   - recognized_word_count > 0
 *   - retry mode is not FORCE_ALL
 */
class OcrSkipLogicTest {

    private val currentIdentity = "content://media/external/0001"
    private val currentModifiedAt = 100L
    private val currentSize = 1000L

    private fun shouldSkip(state: OcrPageState?, currentIdentity: String, retryMode: OcrRetryMode): Boolean =
        shouldSkipOcrPage(state, currentIdentity, currentModifiedAt, currentSize, retryMode)

    @Test
    fun `success row with matching identity and words is skipped`() {
        val state = OcrPageState(
            pageIdentity = currentIdentity,
            ocrStatus = OCR_STATUS_SUCCESS,
            recognizedWordCount = 42,
            errorMessage = null,
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe true
    }

    @Test
    fun `null state means no current-engine row - not skipped`() {
        shouldSkip(null, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe false
    }

    @Test
    fun `empty row is not skipped`() {
        val state = OcrPageState(
            pageIdentity = currentIdentity,
            ocrStatus = OCR_STATUS_EMPTY,
            recognizedWordCount = 0,
            errorMessage = null,
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe false
    }

    @Test
    fun `failed row is not skipped`() {
        val state = OcrPageState(
            pageIdentity = currentIdentity,
            ocrStatus = OCR_STATUS_FAILED,
            recognizedWordCount = 0,
            errorMessage = "Decode failed",
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe false
    }

    @Test
    fun `success row with zero word count is not skipped`() {
        val state = OcrPageState(
            pageIdentity = currentIdentity,
            ocrStatus = OCR_STATUS_SUCCESS,
            recognizedWordCount = 0,
            errorMessage = null,
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe false
    }

    @Test
    fun `changed identity is not skipped even if success`() {
        val state = OcrPageState(
            pageIdentity = "content://media/external/OLD",
            ocrStatus = OCR_STATUS_SUCCESS,
            recognizedWordCount = 10,
            errorMessage = null,
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED) shouldBe false
    }

    @Test
    fun `force_all mode never skips even a perfect success row`() {
        val state = OcrPageState(
            pageIdentity = currentIdentity,
            ocrStatus = OCR_STATUS_SUCCESS,
            recognizedWordCount = 100,
            errorMessage = null,
            pageModifiedAt = currentModifiedAt,
            pageSize = currentSize,
        )
        shouldSkip(state, currentIdentity, OcrRetryMode.FORCE_ALL) shouldBe false
    }

    @Test
    fun `changed or unknown metadata requires fresh recognition`() {
        val state = OcrPageState(currentIdentity, OCR_STATUS_SUCCESS, 42, null, currentModifiedAt, currentSize)
        val mode = OcrRetryMode.SKIP_SUCCESS_RETRY_EMPTY_FAILED
        shouldSkipOcrPage(state, currentIdentity, currentModifiedAt + 1, currentSize, mode) shouldBe false
        shouldSkipOcrPage(state, currentIdentity, currentModifiedAt, currentSize + 1, mode) shouldBe false
        shouldSkipOcrPage(state, currentIdentity, null, currentSize, mode) shouldBe false
        shouldSkipOcrPage(state.copy(pageModifiedAt = null), currentIdentity, currentModifiedAt, currentSize, mode) shouldBe false
        shouldSkipOcrPage(state.copy(pageSize = null), currentIdentity, currentModifiedAt, currentSize, mode) shouldBe false
    }
}

// KMK <--
