package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant

class GetApplicationReleaseTest {

    @Test
    fun `fork release comparison accepts only newer compatible tags`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) } answers { }
        val cases = listOf(
            "v0.8.23" to true,
            "v0.8.22-fix1" to true,
            "v0.8.22" to false,
            "v0.8.21" to false,
            "v0.8" to false,
            "v0.8.22.0" to false,
            "r999999" to false,
            "latest" to false,
            "v999999999999999999999.1" to false,
        )
        for ((tag, shouldUpdate) in cases) {
            val release = Release(tag, "Notes", "https://example.com/release", "https://example.com/app.apk")
            coEvery { releaseService.releaseNotes(any()) } returns listOf(release)
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(false, false, 0, "v0.8.22", "FancyCorey/komikku-KMK", true),
            )
            (result is GetApplicationRelease.Result.NewUpdate) shouldBe shouldUpdate
        }
    }

    @Test
    fun `draft and prerelease builds are not offered to stable users`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) } answers { }
        coEvery { releaseService.releaseNotes(any()) } returns listOf(
            Release("v0.8.24", "Draft", "link", "apk", draft = true),
            Release("v0.8.23", "Preview", "link", "apk", preRelease = true),
        )
        getApplicationRelease.await(
            GetApplicationRelease.Arguments(false, false, 0, "v0.8.22", "FancyCorey/komikku-KMK", true),
        ) shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `preview checks skip stable and malformed tags without crashing`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) } answers { }
        val releases = listOf(
            Release("v0.8.23", "Stable", "link", "apk"),
            Release("r99999999999999999999", "Malformed", "link", "apk"),
            Release("r2000", "Preview", "link", "apk"),
        )
        coEvery { releaseService.releaseNotes(any()) } returns releases
        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(false, true, 1000, "", "FancyCorey/komikku-KMK", true),
        )
        (result as GetApplicationRelease.Result.NewUpdate).release.version shouldBe "r2000"
    }

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService
    private lateinit var preference: Preference<Long>

    @BeforeEach
    fun beforeEach() {
        val preferenceStore = mockk<PreferenceStore>()
        preference = mockk()
        every { preferenceStore.getLong(any(), any()) } returns preference
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService, preferenceStore)
    }

    @Test
    fun `When has update but is preview expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "r2000",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
            ),
        )

        // KMK: Don't cast, will throw exception if the result is different from expected
        result shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `When has update expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases =
            listOf(
                Release(
                    "v2.0.0",
                    "info",
                    "http://example.com/release_link",
                    "http://example.com/release_link.apk",
                ),
            )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        // KMK: Don't cast, will throw exception if the result is different from expected
        result shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "v1.0.0",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `When now is before two days expect no new update`() = runTest {
        every { preference.get() } returns Instant.now().toEpochMilli()
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "v2.0.0",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        coVerify(exactly = 0) { releaseService.latest(any()) }
        coVerify(exactly = 0) { releaseService.releaseNotes(any()) }
        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }
}
