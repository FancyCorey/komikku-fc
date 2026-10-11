package eu.kanade.presentation.more.settings.screen.about

import com.mikepenz.aboutlibraries.entity.License
import eu.kanade.tachiyomi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest

class OpenSourceLicenseEntriesTest {
    @Test
    fun `maps every inline and URL-only license while preserving order`() {
        val entries = listOf(
            License(
                name = "Inline",
                url = "https://example.test/inline",
                year = null,
                spdxId = "MIT",
                licenseContent = "  terms  ",
                hash = "inline-license",
            ),
            License(
                name = "Remote",
                url = "https://example.test/license",
                year = null,
                spdxId = "BSD-3-Clause",
                licenseContent = null,
                hash = "remote-license",
            ),
            License(
                name = "HTTP license",
                url = "http://example.test/license",
                year = null,
                spdxId = "BSD-2-Clause",
                licenseContent = null,
                hash = "http-license",
            ),
        ).toOpenSourceLicenseEntries()

        assertEquals(
            listOf(
                OpenSourceLicenseEntry("Inline", "terms", "https://example.test/inline"),
                OpenSourceLicenseEntry("Remote", "", "https://example.test/license"),
                OpenSourceLicenseEntry("HTTP license", "", "http://example.test/license"),
            ),
            entries,
        )
    }

    @Test
    fun `uses SPDX name and omits empty or unsafe records`() {
        val entries = listOf(
            License(
                name = "",
                url = "https://example.test/license",
                year = null,
                spdxId = "MIT",
                licenseContent = null,
                hash = "spdx-license",
            ),
            License(
                name = "",
                url = "javascript:alert(1)",
                year = null,
                spdxId = null,
                licenseContent = null,
                hash = "unsafe-license",
            ),
            License(
                name = "Inline with unsafe URL",
                url = "javascript:alert(1)",
                year = null,
                spdxId = null,
                licenseContent = "  retained terms  ",
                hash = "unsafe-url-inline-license",
            ),
            License(
                name = "  ",
                url = " ",
                year = null,
                spdxId = null,
                licenseContent = " ",
                hash = "blank-license",
            ),
        ).toOpenSourceLicenseEntries()

        assertEquals(
            listOf(
                OpenSourceLicenseEntry("MIT", "", "https://example.test/license"),
                OpenSourceLicenseEntry("Inline with unsafe URL", "retained terms", null),
            ),
            entries,
        )
    }

    @Test
    fun `maps every bundled notice to its matching library identity`() {
        val notices = listOf(
            Triple(
                "com.github.komikku-app:UniFile",
                R.raw.unifile_notice,
                "unifile_notice.txt" to "96EBE8275BAF1AE95FBF8B670F6BCECA3D36AB1A4101917B37F4E675C779A02A",
            ),
            Triple(
                "commons-codec:commons-codec",
                R.raw.commons_codec_notice,
                "commons_codec_notice.txt" to "FC5BECECCC4708BA23267644C1DBCA212CFB015F21713F8080F99E441592D2E2",
            ),
            Triple(
                "commons-logging:commons-logging",
                R.raw.commons_logging_notice,
                "commons_logging_notice.txt" to "785071A94AB6133000164BC6ED8543DA88231503768DFEADCC83847AF99CF8F8",
            ),
            Triple(
                "org.apache.httpcomponents:httpclient",
                R.raw.apache_httpclient_notice,
                "apache_httpclient_notice.txt" to "F5783FBB52E5FCFB26B6916CE3BB9C717D22759B030CEE6DEAA03145F1F8D819",
            ),
            Triple(
                "org.apache.httpcomponents:httpcore",
                R.raw.apache_httpcore_notice,
                "apache_httpcore_notice.txt" to "A8FF0D486F1BDD29331E0E244F96245354779EF818EC25ECD60545AD335B68DA",
            ),
        )

        notices.forEach { (libraryId, resourceId, source) ->
            assertEquals(resourceId, OpenSourceLicenseNotices.forLibrary(libraryId)?.resourceId)
            assertEquals(source.second, sha256(File("src/main/res/raw/${source.first}").readBytes()))
        }
        assertNull(OpenSourceLicenseNotices.forLibrary("unknown:library"))
    }

    @Test
    fun `Voyager license screen payload survives Java serialization round trip`() {
        val licenses = listOf(OpenSourceLicenseEntry("Apache-2.0", "terms", "https://example.test/license"))
        val notice = OpenSourceLicenseNotice("Apache Commons Codec NOTICE", "Copyright notice")
        val screen = OpenSourceLibraryLicenseScreen(
            name = "Apache Commons Codec",
            website = "https://example.test",
            licenses = licenses,
            notice = notice,
        )
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(screen) }
            output.toByteArray()
        }

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as OpenSourceLibraryLicenseScreen
        }

        assertEquals(screen.key, restored.key)
        assertEquals(licenses, restored.licenses)
        assertEquals(notice, restored.notice)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }
}
