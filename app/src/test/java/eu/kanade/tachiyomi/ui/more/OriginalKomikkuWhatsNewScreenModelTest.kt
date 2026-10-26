package eu.kanade.tachiyomi.ui.more

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import java.io.IOException

/** v0.8.25: the original Komikku release notes screen loads on open, shows a failure with Retry, and never crashes. */
@OptIn(ExperimentalCoroutinesApi::class)
class OriginalKomikkuWhatsNewScreenModelTest {

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun release(version: String) = Release(
        version = version,
        info = "## $version\n\nNotes",
        releaseLink = "https://github.com/komikku-app/komikku/releases/tag/$version",
        downloadLink = "https://example.invalid/$version.apk",
    )

    @Test
    fun `releases load into the screen on open in the order given`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val model = OriginalKomikkuWhatsNewScreenModel(
            loadReleases = { listOf(release("v1.14.4"), release("v1.14.3")) },
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<OriginalKomikkuWhatsNewScreenModel.State.Loaded>()
        state.releases.map { it.version } shouldBe listOf("v1.14.4", "v1.14.3")
    }

    @Test
    fun `no releases is an error without a cause so the screen can say nothing was found`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val model = OriginalKomikkuWhatsNewScreenModel(loadReleases = { emptyList() }, ioDispatcher = dispatcher)
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<OriginalKomikkuWhatsNewScreenModel.State.Error>().cause shouldBe null
    }

    @Test
    fun `a failed load keeps the cause and Retry loads again`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var attempts = 0
        val failure = IOException("offline")

        val model = OriginalKomikkuWhatsNewScreenModel(
            loadReleases = {
                attempts++
                if (attempts == 1) throw failure else listOf(release("v1.14.4"))
            },
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<OriginalKomikkuWhatsNewScreenModel.State.Error>().cause shouldBe failure

        model.load()
        advanceUntilIdle()

        attempts shouldBe 2
        model.state.value.shouldBeInstanceOf<OriginalKomikkuWhatsNewScreenModel.State.Loaded>()
    }
}
