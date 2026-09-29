package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppUpdateDownloadJobSourceContractTest {

    @Test
    fun `download worker cleans terminal failures and keeps cancellation separate`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()
        val workBlock = source.substringAfter("private suspend fun downloadApk")
            .substringBefore("@RequiresApi(31)")

        assertTrue(workBlock.contains("AppUpdateDownloadResultPolicy.classify("))
        assertTrue(workBlock.contains("Outcome.Retry -> DownloadOutcome.Retry"))
        assertTrue(workBlock.contains("Outcome.Failure -> {"))
        assertTrue(workBlock.contains("cleanupDownloadedApk()"))
        assertTrue(workBlock.contains("Outcome.Cancelled -> DownloadOutcome.Cancelled"))
    }

    @Test
    fun `download worker does not retain dead global worker state`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()

        assertTrue(!source.contains("WeakReference"))
        assertTrue(!source.contains("instanceLock"))
        assertTrue(!source.contains("private var instance"))
    }

    @Test
    fun `missing download input cleans stale apk before failing`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()
        val missingInputBlock = source.substringAfter("if (url.isNullOrEmpty())")
            .substringBefore("setForegroundSafely()")

        assertTrue(missingInputBlock.contains("cleanupDownloadedApk()"))
        assertTrue(missingInputBlock.contains("return Result.failure()"))
    }

    @Test
    fun `successful download keeps delivery and install fallback paths`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()
        val downloadBlock = source.substringAfter("private suspend fun downloadApk")
            .substringBefore("@RequiresApi(31)")

        assertTrue(downloadBlock.contains("DownloadOutcome.Completed"))
        assertTrue(downloadBlock.contains("startInstalling(apkFile, title, attemptId)"))
        assertTrue(downloadBlock.contains("notifier.promptInstall(apkFile.getUriCompat(context))"))
    }

    @Test
    fun `cleanup reports an undeletable failed download`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt").readText()
        val cleanupBlock = source.substringAfter("private fun cleanupDownloadedApk")
            .substringBefore("companion object")

        assertTrue(cleanupBlock.contains("apkFile.exists() && !apkFile.delete()"))
        assertTrue(cleanupBlock.contains("Unable to remove failed app update download"))
    }
}
