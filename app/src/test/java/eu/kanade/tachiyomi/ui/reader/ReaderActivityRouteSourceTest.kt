package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReaderActivityRouteSourceTest {

    @Test
    fun `fresh reader launch uses the same route validation as reader reuse`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")
        val initBlock = source.substringAfter("if (viewModel.needsInit()) {")
            .substringBefore("config = ReaderConfig()")

        assertTrue(initBlock.contains("parseReaderRouteIntent("))
        assertTrue(initBlock.contains("if (route == null)"))
        assertTrue(initBlock.contains("viewModel.init(route.mangaId, route.chapterId"))
        assertTrue(initBlock.contains("route.page"))
    }

    @Test
    fun `reader activity source uses the replacement-intent route`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")
        val replacement = source.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("/**")

        assertTrue(replacement.contains("parseReaderRouteIntent("))
        assertTrue(replacement.contains("setIntent(intent)"))
        assertTrue(replacement.contains("NotificationReceiver.dismissNotification(this, route.mangaId.hashCode(), Notifications.ID_NEW_CHAPTERS)"))
        assertTrue(replacement.contains("viewModel.openFromIntent(route.mangaId, route.chapterId, route.page)"))
    }

    @Test
    fun `reader activity is configured to receive replacement intents`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val readerActivity = manifest.substringAfter("android:name=\".ui.reader.ReaderActivity\"")
            .substringBefore("</activity>")

        assertTrue(readerActivity.contains("android:launchMode=\"singleTask\""))
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
