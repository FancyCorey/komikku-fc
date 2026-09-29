package exh.ocr

import androidx.work.Data
import androidx.work.WorkInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class OcrPersistedProgressTest {
    @Test
    fun `persisted progress is decoded without losing terminal state`() {
        val data = Data.Builder()
            .putString("current_manga", "Example")
            .putString("current_chapter", "Chapter 2")
            .putInt("completed_pages", 4)
            .putInt("recognized_pages", 3)
            .putInt("empty_pages", 1)
            .putInt("total_pages", 4)
            .putInt("failed_pages", 0)
            .build()

        val progress = data.toOcrIndexProgress(
            isRunning = false,
            isComplete = true,
            isFailed = false,
            isCancelled = false,
        )

        assertEquals("Example", progress.currentManga)
        assertEquals(4, progress.completedPages)
        assertEquals(3, progress.recognizedPages)
        assertEquals(1, progress.emptyPages)
        assertEquals(true, progress.isComplete)
    }

    @Test
    fun `persisted cancellation is not presented as completion`() {
        val data = Data.Builder().putBoolean("is_cancelled", true).build()

        val progress = data.toOcrIndexProgress(
            isRunning = false,
            isComplete = true,
            isFailed = false,
            isCancelled = false,
        )

        assertEquals(true, progress.isCancelled)
        assertEquals(false, progress.isComplete)
    }

    @Test
    fun `persisted failure is not presented as completion or running`() {
        val info = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.FAILED
            every { progress } returns Data.Builder()
                .putString("last_error", "ocr_error_storage")
                .build()
        }

        val progress = OcrIndexWorker.progressFromWorkInfo(info)

        assertEquals(true, progress.isFailed)
        assertEquals("ocr_error_storage", progress.lastError)
        assertFalse(progress.isComplete)
        assertFalse(progress.isRunning)
    }
}
