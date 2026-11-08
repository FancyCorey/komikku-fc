package exh.reader.presets

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.util.FakePreferenceStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.model.CrossSourceMangaLink

// KMK v0.8.25 -->
/** Choosing Usual settings means no preset for the series, on every confirmed version of it. */
class ReaderPresetsAssignmentTest {

    private fun link(source: Long, url: String) = CrossSourceMangaLink(source, url, "group", "T", 0L, 0L)

    private val a = Manga.create().copy(id = 1L, source = 1L, url = "/a")
    private val b = Manga.create().copy(id = 2L, source = 2L, url = "/b")
    private val members = listOf(link(1L, "/a"), link(2L, "/b"))

    private fun presets(): ReaderPresets {
        val links = mockk<GetCrossSourceMangaLinks>()
        coEvery { links.awaitBySourceUrl(any(), any()) } coAnswers { members.first { it.source == firstArg<Long>() } }
        coEvery { links.awaitByGroupId("group") } returns members
        val resolver = mockk<CrossSourceIdentityAuthorizationResolver>()
        coEvery { resolver.confirmedGroupMembers(any(), any(), any()) } returns members
        val store = FakePreferenceStore()
        return ReaderPresets(ReaderPreferences(store), store, links, resolver)
    }

    @Test
    fun `a preset follows the series to its other versions`() = runTest {
        val presets = presets()
        presets.assign(a, "Webtoon")
        assertEquals("Webtoon", presets.presetNameFor(b))
    }

    @Test
    fun `Usual settings on a version that only inherited the preset really stops using it`() = runTest {
        val presets = presets()
        presets.assign(a, "Webtoon")

        presets.assign(b, null)

        assertNull(presets.presetNameFor(b), "the sibling's preset must not come straight back")
        assertNull(presets.presetNameFor(a))
    }

    @Test
    fun `Usual settings with no preset anywhere changes nothing`() = runTest {
        val presets = presets()
        presets.assign(a, null)
        assertNull(presets.presetNameFor(a))
    }
}
// KMK <--
