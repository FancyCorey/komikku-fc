package exh.recs

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.model.TasteProfile

class ForYouDebugFixtureTest {
    @Test
    fun `only fixture-enabled builds and known preferences resolve a fixture mode`() {
        assertEquals(ForYouDebugFixtureMode.OFF, ForYouDebugFixture.resolveMode(false, "top_picks"))
        assertEquals(ForYouDebugFixtureMode.TOP_PICKS, ForYouDebugFixture.resolveMode(true, "top_picks"))
        assertEquals(ForYouDebugFixtureMode.INSTALLED_CORPUS, ForYouDebugFixture.resolveMode(true, "installed_corpus"))
        assertEquals(
            ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE,
            ForYouDebugFixture.resolveMode(true, "installed_corpus_slow_source"),
        )
        assertEquals(ForYouDebugFixtureMode.OFF, ForYouDebugFixture.resolveMode(true, "unknown"))
    }

    @Test
    fun `slow installed corpus delays only alpha and cancellation prevents source request`() = runTest {
        val alpha = mockk<CatalogueSource>(relaxed = true)
        val beta = mockk<CatalogueSource>(relaxed = true)
        every { alpha.id } returns 910_000_000_000_000_001L
        every { beta.id } returns 910_000_000_000_000_002L
        coEvery { alpha.getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)
        coEvery { beta.getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)

        val slowSources = ForYouDebugFixture.sources(
            ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE,
            listOf(alpha, beta),
        )
        val cancelledRequest = launch {
            (slowSources.first() as CatalogueSource).getSearchManga(1, "", FilterList())
        }
        runCurrent()

        assertTrue(cancelledRequest.isActive)
        cancelledRequest.cancelAndJoin()
        coVerify(exactly = 0) { alpha.getSearchManga(any(), any(), any()) }

        val normalSources = ForYouDebugFixture.sources(ForYouDebugFixtureMode.INSTALLED_CORPUS, listOf(alpha, beta))
        (normalSources.first() as CatalogueSource).getSearchManga(1, "", FilterList())
        (slowSources.last() as CatalogueSource).getSearchManga(1, "", FilterList())
        coVerify(exactly = 1) { alpha.getSearchManga(any(), any(), any()) }
        coVerify(exactly = 1) { beta.getSearchManga(any(), any(), any()) }
    }

    @Test
    fun `fixture supplies generic deterministic sources instead of real inputs`() {
        val sources = ForYouDebugFixture.sources(ForYouDebugFixtureMode.TOP_PICKS, emptyList())

        assertEquals(listOf("Fixture Source A", "Fixture Source B"), sources.map { it.name })
        assertTrue(sources.all { it.lang == "en" })
        assertTrue(ForYouDebugFixture.sourceIds(ForYouDebugFixtureMode.TOP_PICKS).containsAll(sources.map { it.id }))
    }

    @Test
    fun `partial fixture supplies one success and two independent failures`() {
        val sources = ForYouDebugFixture.sources(ForYouDebugFixtureMode.TOP_PICKS_PARTIAL_FAILURE, emptyList())

        assertEquals(
            listOf("Fixture Source A", "Fixture Source B", "Fixture Source C"),
            sources.map { it.name },
        )
        assertEquals(3, ForYouDebugFixture.sourceIds(ForYouDebugFixtureMode.TOP_PICKS_PARTIAL_FAILURE).size)
    }

    @Test
    fun `installed corpus keeps only the matching real source identities`() {
        val alpha = mockk<eu.kanade.tachiyomi.source.Source>(relaxed = true)
        val beta = mockk<eu.kanade.tachiyomi.source.Source>(relaxed = true)
        val unrelated = mockk<eu.kanade.tachiyomi.source.Source>(relaxed = true)
        every { alpha.id } returns 910_000_000_000_000_001L
        every { beta.id } returns 910_000_000_000_000_002L
        every { unrelated.id } returns 910_000_000_000_000_999L

        val sources = ForYouDebugFixture.sources(
            ForYouDebugFixtureMode.INSTALLED_CORPUS,
            listOf(alpha, beta, unrelated),
        )

        assertEquals(listOf(alpha, beta), sources)
        assertNull(ForYouDebugFixture.browseSource(alpha.id, true, "installed_corpus"))
    }

    @Test
    fun `installed corpus profile includes negative and blocked policy cases`() = kotlinx.coroutines.test.runTest {
        val profile = ForYouDebugFixture.profile(ForYouDebugFixtureMode.INSTALLED_CORPUS) { TasteProfile.EMPTY }

        assertEquals(1, profile.explicitTagPreferences["action"])
        assertEquals(-1, profile.explicitTagPreferences["comedy"])
        assertTrue("blocked" in profile.blockedGroups)
    }

    @Test
    fun `browse fixture resolves only when a fixture build enables recommendations`() {
        val sourceId = ForYouDebugFixture.sourceIds(ForYouDebugFixtureMode.TOP_PICKS).first()

        assertNotNull(
            ForYouDebugFixture.browseSource(sourceId, fixtureBuildEnabled = true, preferenceValue = "top_picks"),
        )
        assertNull(
            ForYouDebugFixture.browseSource(sourceId, fixtureBuildEnabled = false, preferenceValue = "top_picks"),
        )
        assertNull(ForYouDebugFixture.browseSource(sourceId, fixtureBuildEnabled = true, preferenceValue = "off"))
    }

    @Test
    fun `browse fixture applies included and excluded genre filters`() = kotlinx.coroutines.test.runTest {
        val sourceId = ForYouDebugFixture.sourceIds(ForYouDebugFixtureMode.TOP_PICKS).first()
        val source = requireNotNull(
            ForYouDebugFixture.browseSource(sourceId, fixtureBuildEnabled = true, preferenceValue = "top_picks"),
        )
        val filters = source.getFilterList()
        val genres = filters.filterIsInstance<Filter.Group<*>>().single().state.filterIsInstance<Filter.TriState>()
        genres.single { it.name == "Comedy" }.state = Filter.TriState.STATE_INCLUDE
        genres.single { it.name == "Action" }.state = Filter.TriState.STATE_EXCLUDE

        val results = source.getSearchManga(1, "", filters).mangas

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.genre == "Comedy" })
    }

    @Test
    fun `fixture profile avoids reading the normal profile`() = kotlinx.coroutines.test.runTest {
        var normalProfileRead = false

        val profile = ForYouDebugFixture.profile(ForYouDebugFixtureMode.TOP_PICKS) {
            normalProfileRead = true
            TasteProfile.EMPTY
        }

        assertFalse(normalProfileRead)
        assertFalse(profile.isEmpty())
    }

    @Test
    fun `off fixture preserves normal source and profile inputs`() = kotlinx.coroutines.test.runTest {
        val normalSources = emptyList<eu.kanade.tachiyomi.source.Source>()
        var normalProfileRead = false

        assertEquals(normalSources, ForYouDebugFixture.sources(ForYouDebugFixtureMode.OFF, normalSources))
        assertEquals(
            TasteProfile.EMPTY,
            ForYouDebugFixture.profile(ForYouDebugFixtureMode.OFF) {
                normalProfileRead = true
                TasteProfile.EMPTY
            },
        )
        assertTrue(normalProfileRead)
    }
}
