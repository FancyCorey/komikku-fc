package exh.recs

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.restoreBrowseSourceFilters
import exh.recs.sources.GenreFilterMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.nio.file.Files
import java.nio.file.Path

class BrowsePersonalRecommendationsSourceContextTest {

    @Test
    fun `serialized source filters can be carried with the search context`() {
        val genre = object : Filter.TriState("Action") {}
        val filtersJson = serializeRecommendationFilters(
            FilterList(object : Filter.Group<Filter<*>>("Genre", listOf(genre)) {}),
        )

        assertNotNull(filtersJson)
        assertTrue(filtersJson!!.contains("Action"))
        assertTrue(filtersJson.contains("_type"))

        val context = RecommendationSearchContext(
            textQuery = "",
            topTags = listOf("Action"),
            filtersJson = filtersJson,
        )
        assertTrue(context.filtersJson!!.contains("Action"))
    }

    @Test
    fun `profile exclusion survives the For You source-filter handoff`() {
        val serializer = FilterSerializer()
        val action = object : Filter.TriState("Action") {}
        val generated = FilterList(object : Filter.Group<Filter<*>>("Genre", listOf(action)) {})
        GenreFilterMapper.buildSearch(generated, listOf("Action"), blockedGenres = listOf("Action"))

        val carriedJson = serializeRecommendationFilters(generated, serializer)!!
        val destinationAction = object : Filter.TriState("Action") {}
        val destination = FilterList(object : Filter.Group<Filter<*>>("Genre", listOf(destinationAction)) {})

        assertTrue(restoreBrowseSourceFilters(destination, carriedJson, serializer))
        assertEquals(Filter.TriState.STATE_EXCLUDE, destinationAction.state)
    }

    @Test
    fun `For You source navigation forwards the generated filter context`() {
        val source = source("app/src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt")

        assertTrue(source.contains("filtersJson = ctx?.filtersJson"))
        assertTrue(source.contains("forYouRestrictionsJson = ctx?.restrictionsJson"))
        assertTrue(source.contains("listingQuery = ctx?.textQuery"))
    }

    @Test
    fun `For You browse restrictions survive navigation and keep unknown tags visible`() {
        val now = 1L
        val disliked = MangaTaste(1, 7, "/hidden", "Hidden", MangaRating.DISLIKE.value, now, now)
        val otherSourceDislike = disliked.copy(source = 8, url = "/other-source")
        val restrictions = ForYouBrowseRestrictions.fromProfile(
            sourceId = 7,
            blockedGroups = setOf("Action"),
            tastes = listOf(disliked, otherSourceDislike),
            aliases = mapOf("shounen" to "action"),
        )
        val restored = Json.decodeFromString<ForYouBrowseRestrictions>(Json.encodeToString(restrictions))
        val blocked = Manga.create().copy(source = 7, url = "/blocked", ogGenre = listOf("Shounen"))
        val unknown = Manga.create().copy(source = 7, url = "/unknown", ogGenre = null)
        val unrelated = Manga.create().copy(source = 7, url = "/unrelated", ogGenre = listOf("Comedy"))

        assertFalse(restored.allows(blocked))
        assertFalse(restored.allows(Manga.create().copy(source = 7, url = "/hidden")))
        assertTrue(restored.allows(unknown))
        assertTrue(restored.allows(unrelated))
        assertTrue(restored.hiddenUrls.contains("/hidden"))
        assertFalse(restored.hiddenUrls.contains("/other-source"))
    }

    @Test
    fun `missing restriction context keeps ordinary Browse unchanged`() {
        val context = ForYouBrowseRestrictionContext.decode(null)
        val manga = Manga.create().copy(url = "/ordinary-browse")

        assertFalse(context.isInvalid)
        assertTrue(context.allows(manga))
    }

    @Test
    fun `malformed For You restriction context fails closed`() {
        val context = ForYouBrowseRestrictionContext.decode("not-json")
        val manga = Manga.create().copy(url = "/blocked-until-context-is-restored")

        assertTrue(context.isInvalid)
        assertFalse(context.allows(manga))
    }

    @Test
    fun `search context records serialized filters after source search parameters are built`() {
        val source = source("app/src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt")
        val contextAssignment = source.substringAfter("val ctx = RecommendationSearchContext(")

        assertTrue(contextAssignment.contains("filtersJson = serializeRecommendationFilters"))
        assertTrue(contextAssignment.indexOf("filtersJson = serializeRecommendationFilters") < contextAssignment.indexOf("mutableState.update"))
    }

    @Test
    fun `source browse falls back to current defaults when the handoff filters cannot be decoded`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt")

        assertTrue(source.contains("restoreBrowseSourceVisitFilters("))
        assertTrue(source.contains("inheritedFiltersJson = jsonFilters"))
        assertTrue(source.contains("search(filters = if (filtersRestored) filters else safeFilterList(source))"))
    }

    @Test
    fun `For You hard restrictions filter returned Browse rows only when supplied`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt")

        assertTrue(source.contains("forYouRestrictionContext.allows(manga)"))
        assertTrue(source.contains("hasInvalidForYouRestrictionContext"))
    }

    @Test
    fun `invalid restriction context explains how to recover in Browse`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt")

        assertTrue(source.contains("screenModel.hasInvalidForYouRestrictionContext"))
        assertTrue(source.contains("KMR.strings.rec_browse_restrictions_unavailable"))
    }

    @Test
    fun `For You chapter minimum hides only known counts below the threshold`() {
        val manga = Manga.create().copy(url = "/chapter-threshold")
        val restrictions = ForYouBrowseRestrictions(minimumKnownChapterCount = 10)

        assertTrue(restrictions.allows(manga))
        assertTrue(restrictions.allows(manga, knownChapterCount = null))
        assertFalse(restrictions.allows(manga, knownChapterCount = 9))
        assertTrue(restrictions.allows(manga, knownChapterCount = 10))
    }

    @Test
    fun `For You source visit checks known chapter counts locally and keeps unknown results`() {
        val source = source("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt")

        assertTrue(source.contains("getManga.await(manga.url, manga.source)"))
        assertTrue(source.contains("getChapterCounts.await(listOf(localManga.id))"))
        assertTrue(source.contains("?: return true"))
        assertTrue(source.contains("catch (e: CancellationException)"))
    }

    private fun source(relative: String): String {
        val path = listOf(Path.of(relative), Path.of("..", relative))
            .map(Path::toAbsolutePath)
            .firstOrNull(Files::isRegularFile)
            ?: error("Unable to resolve source: $relative")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }
}
