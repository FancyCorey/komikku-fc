package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.connections.discord.DOWNLOAD_BUTTON_URL
import exh.recs.KmkRecsReleaseNotes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateCheckerRepositoryTest {

    @Test
    fun `stable update identity uses the fork release version`() {
        assertEquals(KmkRecsReleaseNotes.VERSION_NAME.removePrefix("KMK-Recs "), getUpdateVersionName())
        assertEquals(getUpdateVersionName(), getReleaseTag())
    }

    @Test
    fun `stable and preview checks stay inside the KMK fork`() {
        assertEquals("FancyCorey/komikku-fc", getGithubRepo(peekIntoPreview = false))
        assertEquals("FancyCorey/komikku-fc", getGithubRepo(peekIntoPreview = true))
    }

    @Test
    fun `download links use the canonical fork repository`() {
        assertEquals("https://github.com/FancyCorey/komikku-fc/releases", DOWNLOAD_BUTTON_URL)
        assertTrue(RELEASE_URL.startsWith("https://github.com/FancyCorey/komikku-fc/releases/"))
    }

    @Test
    fun `development build is isolated from public update and Drive channels`() {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".dev"))
        assertFalse(BuildConfig.UPDATER_ENABLED)
        assertFalse(BuildConfig.GOOGLE_DRIVE_SYNC_ENABLED)
    }
}
