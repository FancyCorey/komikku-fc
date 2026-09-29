package exh.ocr

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class OcrIndexServiceLifecycleTest {

    @Test
    fun `page enumeration failure publishes a stable terminal error`() = runBlocking {
        val recognizer = RecordingRecognizer()
        val pageProvider = mockk<OcrDownloadPageProvider>()
        val repository = mockk<OcrIndexRepository>()
        coEvery { repository.beginIndexing() } returns 7L
        coEvery { pageProvider.enumeratePages(any(), any()) } throws IOException("/private/page.cbz")
        val progress = mutableListOf<OcrIndexProgress>()
        val service = OcrIndexService(
            context = mockk<Context>(relaxed = true),
            recognizer = recognizer,
            pageProvider = pageProvider,
            repository = repository,
        )

        service.runIndexing(OcrScope.AllDownloaded, onProgress = progress::add)

        assertEquals(1, progress.size)
        assertTrue(progress.single().isFailed)
        assertEquals(OcrErrorKey.Storage.storageKey, progress.single().lastError)
        assertTrue(progress.single().lastError!!.contains("ocr_error_"))
        assertTrue(!progress.single().lastError!!.contains("private"))
    }

    @Test
    fun `service close delegates to the recognizer`() {
        val recognizer = RecordingRecognizer()
        val service = OcrIndexService(
            context = mockk<Context>(relaxed = true),
            recognizer = recognizer,
            pageProvider = mockk(relaxed = true),
            repository = mockk(relaxed = true),
        )

        service.close()

        assertTrue(recognizer.closed)
    }

    private class RecordingRecognizer : OcrTextRecognizer {
        var closed = false

        override val engineKey = "test"
        override val engineVersion = "test"

        override suspend fun recognizeText(input: OcrImageInput): OcrPageText = OcrPageText("")

        override fun close() {
            closed = true
        }
    }
}
