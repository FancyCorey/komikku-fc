package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppUpdateCheckerSourceContractTest {

    @Test
    fun `automatic download is scheduled for every supported installer path`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt").readText()

        assertTrue(source.contains("if (autoUpdate && result is GetApplicationRelease.Result.NewUpdate)"))
        assertTrue(source.contains("AppUpdateDownloadJob.start("))
        assertTrue(source.contains("scheduled = true"))
        assertFalse(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
    }
}
