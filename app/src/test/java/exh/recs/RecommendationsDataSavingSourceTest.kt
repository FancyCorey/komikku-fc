package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK --> v0.8.24 network review: "Use less data on recommendations" also trims a manga's Recommendations screen and
// the library recommendation search; default (setting off or open connection) behavior is unchanged.
class RecommendationsDataSavingSourceTest {

    private fun read(path: String) = File(path).readText().replace("\r\n", "\n")

    private val paging = read("src/main/java/exh/recs/sources/RecommendationPagingSource.kt")
    private val cross = read("src/main/java/exh/recs/sources/CrossExtensionGenreSearchSource.kt")

    @Test
    fun `saving limits are below the normal limits`() {
        assertTrue(ForYouDataSavingPolicy.SAVING_MAX_CROSS_EXTENSION_SOURCES < 20)
        assertTrue(ForYouDataSavingPolicy.SAVING_MAX_ENRICH_PER_SOURCE < 10)
        assertTrue(ForYouDataSavingPolicy.SAVING_MAX_TITLE_FALLBACKS < 2)
        assertTrue(ForYouDataSavingPolicy.SAVING_MAX_PLANS_PER_SOURCE >= 1)
    }

    @Test
    fun `isActiveNow resolves the stored value like For You`() {
        assertEquals(ForYouDataSaving.AUTO, ForYouDataSaving.resolve("unknown"))
        assertTrue(ForYouDataSavingPolicy.isActive(ForYouDataSaving.ALWAYS, networkConstrained = false))
    }

    @Test
    fun `createSources defaults to no saving and caps cross-extension sources in priority order when saving`() {
        assertTrue(paging.contains("dataSaving: Boolean = false,"))
        assertTrue(paging.contains("crossExtensionSources.take(ForYouDataSavingPolicy.SAVING_MAX_CROSS_EXTENSION_SOURCES)"))
        assertTrue(paging.contains("dataSaving = dataSaving,"))
    }

    @Test
    fun `each cross-extension source trims query variations, title fallbacks and detail requests only when saving`() {
        assertTrue(cross.contains("private val dataSaving: Boolean = false,"))
        assertTrue(cross.contains("if (dataSaving) it.take(ForYouDataSavingPolicy.SAVING_MAX_PLANS_PER_SOURCE) else it"))
        assertTrue(cross.contains("if (dataSaving) ForYouDataSavingPolicy.SAVING_MAX_TITLE_FALLBACKS else MAX_TITLE_FALLBACKS"))
        assertTrue(cross.contains("if (dataSaving) ForYouDataSavingPolicy.SAVING_MAX_ENRICH_PER_SOURCE else MAX_ENRICH_PER_SOURCE"))
    }

    @Test
    fun `the Recommendations screen and library search decide once and fail open`() {
        val screen = read("src/main/java/exh/recs/RecommendsScreenModel.kt")
        assertEquals(2, Regex.fromLiteral("dataSaving = dataSavingActive(),").findAll(screen).count())
        assertTrue(screen.contains("private val dataSavingDecision by lazy {"))
        assertTrue(screen.substringAfter("private val dataSavingDecision by lazy {").substringBefore("}\n\n").contains("false"))

        val batch = read("src/main/java/exh/recs/batch/RecommendationSearchHelper.kt")
        assertTrue(batch.contains("ForYouDataSavingPolicy.isActiveNow(context, preferences.recommendationDataSaving().get())"))
        assertTrue(batch.contains("dataSaving = dataSaving,"))
    }
}
// KMK <--
