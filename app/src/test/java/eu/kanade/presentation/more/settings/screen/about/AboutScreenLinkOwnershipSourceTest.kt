package eu.kanade.presentation.more.settings.screen.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.25: About separates Komikku FC from the original Komikku, and every link sits under the project that owns it. */
class AboutScreenLinkOwnershipSourceTest {

    private val source = File("src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt").readText()
    private val fcSection = source.substringAfter("about_section_komikku_fc").substringBefore("about_section_original_komikku")
    private val originalSection = source.substringAfter("about_section_original_komikku").substringBefore("Checks version")

    @Test
    fun `the page has a Komikku FC section before an Original Komikku section`() {
        assertTrue(source.indexOf("about_section_komikku_fc") < source.indexOf("about_section_original_komikku"))
    }

    @Test
    fun `Komikku FC links point at this fork`() {
        assertTrue(source.contains("private val FORK_REPOSITORY_URL = \"https://github.com/\${getGithubRepo()}\""))
        assertTrue(fcSection.contains("url = FORK_REPOSITORY_URL"))
        assertTrue(fcSection.contains("KmkRecsWhatsNewScreen()"))
        assertTrue(fcSection.contains("\$FORK_REPOSITORY_URL/blob/master/docs/kmk/privacy-and-data.md"))
        listOf("komikku-app", "discord.gg", "weblate", "Constants.SPONSOR").forEach {
            assertFalse(fcSection.contains(it), "original-project link $it is under Komikku FC")
        }
    }

    @Test
    fun `the original project's links and release notes are all under Original Komikku`() {
        assertTrue(originalSection.contains("OriginalKomikkuWhatsNewScreen()"))
        assertTrue(originalSection.contains("https://komikku-app.github.io/privacy/"))
        assertTrue(originalSection.contains("https://hosted.weblate.org/engage/komikku-app/"))
        assertTrue(originalSection.contains("https://discord.gg/85jB7V5AJR"))
        assertTrue(originalSection.contains("https://komikku-app.github.io"))
        assertTrue(originalSection.contains("https://github.com/\$ORIGINAL_KOMIKKU_REPOSITORY"))
        assertTrue(originalSection.contains("Constants.SPONSOR"))
        assertTrue(originalSection.contains("about_original_privacy_subtitle"), "the original privacy policy says whose policy it is")
        assertFalse(originalSection.contains("FORK_REPOSITORY_URL"))
    }

    @Test
    fun `the original project is never used for updates`() {
        val updater = File("src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt").readText()

        val repo = Regex("const val ORIGINAL_KOMIKKU_REPOSITORY = \"([^\"]+)\"").find(updater)!!.groupValues[1]
        assertEquals("komikku-app/komikku", repo)
        assertEquals(1, Regex("repository = ORIGINAL_KOMIKKU_REPOSITORY").findAll(updater).count(), "only the read-only release list uses it")
        assertTrue(updater.contains("fun getGithubRepo(peekIntoPreview: Boolean = false): String = \"FancyCorey/komikku-fc\""))
        val release = updater.substringAfter("suspend fun getOriginalKomikkuReleases()").substringBefore("// KMK <--")
        assertFalse(release.contains("promptUpdate") || release.contains("AppUpdateDownloadJob"))
    }
}
