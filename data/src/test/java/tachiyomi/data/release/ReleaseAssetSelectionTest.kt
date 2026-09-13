package tachiyomi.data.release

import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseAssetSelectionTest {
    private val service = ReleaseServiceImpl(mockk(), Json)
    private val release = GithubRelease(
        version = "v0.8.22",
        info = "Notes",
        releaseLink = "https://example.com/release",
        assets = listOf(
            GitHubAsset("Komikku-KMK-v0.8.22.apk", "universal"),
            GitHubAsset("Komikku-KMK-arm64-v8a-v0.8.22.apk", "arm64"),
            GitHubAsset("Komikku-KMK-armeabi-v7a-v0.8.22.apk", "arm32"),
            GitHubAsset("Komikku-KMK-x86_64-v0.8.22.apk", "x64"),
            GitHubAsset("Komikku-KMK-x86-v0.8.22.apk", "x86"),
            GitHubAsset("SHA256SUMS.txt", "checksums"),
            GitHubAsset("CERTIFICATE_SHA256.txt", "certificate"),
            GitHubAsset("RELEASE_MANIFEST.json", "manifest"),
        ),
    )

    @Test
    fun `release assets select the matching APK for each architecture`() {
        for ((abi, link) in listOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm32", "x86_64" to "x64", "x86" to "x86")) {
            assertEquals(link, service.getDownloadLink(release, false, listOf(abi)))
        }
    }

    @Test
    fun `fallback selects universal APK instead of release metadata`() {
        assertEquals("universal", service.getDownloadLink(release, false, listOf("unsupported")))
        assertEquals("universal", service.getDownloadLink(release, false, emptyList()))
        val missingUniversal = release.copy(assets = release.assets.filterNot { it.downloadLink == "universal" })
        assertEquals("arm32", service.getDownloadLink(missingUniversal, false, listOf("arm64-v8a-missing", "armeabi-v7a")))
        assertNull(service.getDownloadLink(missingUniversal, false, listOf("unsupported")))
    }

    @Test
    fun `FOSS users never receive a different build variant`() {
        assertNull(service.getDownloadLink(release, true, listOf("arm64-v8a")))
        val foss = release.copy(assets = release.assets + GitHubAsset("Komikku-KMK-foss-v0.8.22.apk", "foss"))
        assertEquals("foss", service.getDownloadLink(foss, true, listOf("arm64-v8a")))
    }
}
