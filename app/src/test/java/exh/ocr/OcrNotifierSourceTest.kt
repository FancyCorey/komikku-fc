package exh.ocr

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the notification contract visible while the real Android notification manager remains
 * outside the JVM test environment.
 */
class OcrNotifierSourceTest {

    @Test
    fun `ocr notifier keeps progress and completion notifications distinct`() {
        val source = source("app/src/main/java/exh/ocr/OcrNotifier.kt")
        val progressBuilder = source.substringAfter("private val progressBuilder")
            .substringBefore("fun buildProgressNotification")
        val progressMethod = source.substringAfter("override fun updateProgress")
            .substringBefore("override fun dismissProgress")
        val completionMethod = source.substringAfter("override fun showComplete")

        assertTrue(progressBuilder.contains("Notifications.CHANNEL_OCR_INDEXING"))
        assertTrue(progressMethod.contains("context.notify(Notifications.ID_OCR_INDEX_PROGRESS"))
        assertTrue(completionMethod.contains("Notifications.CHANNEL_OCR_INDEXING"))
        assertTrue(completionMethod.contains("context.notify(Notifications.ID_OCR_INDEX_COMPLETE"))
        assertTrue(completionMethod.contains("context.cancelNotification(Notifications.ID_OCR_INDEX_PROGRESS)"))
    }

    @Test
    fun `progress notification remains ongoing while completion is dismissible`() {
        val source = source("app/src/main/java/exh/ocr/OcrNotifier.kt")
        val progressBlock = source.substringAfter("private val progressBuilder")
            .substringBefore("fun buildProgressNotification")
        val completionBlock = source.substringAfter("fun showComplete")

        assertTrue(progressBlock.contains("setOngoing(true)"))
        assertTrue(progressBlock.contains("setOnlyAlertOnce(true)"))
        assertTrue(completionBlock.contains("setAutoCancel(true)"))
        assertTrue(completionBlock.contains("context.cancelNotification(Notifications.ID_OCR_INDEX_PROGRESS)"))
    }

    private fun source(relativePath: String): String {
        val direct = Path.of(relativePath)
        val fromParent = Path.of("..").resolve(relativePath)
        val path = listOf(direct, fromParent)
            .map(Path::toAbsolutePath)
            .firstOrNull(Files::isRegularFile)
            ?: error("Unable to resolve source file: $relativePath")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
