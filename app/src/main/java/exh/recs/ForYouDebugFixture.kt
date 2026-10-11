package exh.recs

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.taste.model.TasteProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only deterministic inputs for the real For You refresh route. */
enum class ForYouDebugFixtureMode(val prefValue: String, val usesInstalledCorpus: Boolean = false) {
    OFF("off"),
    TOP_PICKS("top_picks"),
    TOP_PICKS_PARTIAL_FAILURE("top_picks_partial_failure"),
    INSTALLED_CORPUS("installed_corpus", usesInstalledCorpus = true),
    INSTALLED_CORPUS_SLOW_SOURCE("installed_corpus_slow_source", usesInstalledCorpus = true),
    ;

    companion object {
        fun fromPrefValue(value: String): ForYouDebugFixtureMode =
            entries.firstOrNull { it.prefValue == value } ?: OFF
    }
}

object ForYouDebugFixture {
    const val SLOW_SOURCE_DELAY_MILLIS = 8_000L
    private const val SOURCE_A_ID = 910_000_000_000_000_101L
    private const val SOURCE_B_ID = 910_000_000_000_000_102L
    private const val SOURCE_C_ID = 910_000_000_000_000_103L
    private const val INSTALLED_ALPHA_SOURCE_ID = 910_000_000_000_000_001L
    private const val INSTALLED_BETA_SOURCE_ID = 910_000_000_000_000_002L

    private val requestCounts = ConcurrentHashMap<Long, AtomicInteger>()

    fun resolveMode(fixtureBuildEnabled: Boolean, preferenceValue: String): ForYouDebugFixtureMode =
        if (fixtureBuildEnabled) ForYouDebugFixtureMode.fromPrefValue(preferenceValue) else ForYouDebugFixtureMode.OFF

    /** True when the refresh should use deterministic taste/source policy inputs. */
    fun usesFixtureInputs(mode: ForYouDebugFixtureMode): Boolean = mode != ForYouDebugFixtureMode.OFF

    suspend fun profile(mode: ForYouDebugFixtureMode, realProfile: suspend () -> TasteProfile): TasteProfile =
        if (usesFixtureInputs(mode)) {
            TasteProfile(
                learnedTagWeights = mapOf("action" to 4.0),
                explicitTagPreferences = if (mode.usesInstalledCorpus) {
                    mapOf("action" to 1, "comedy" to -1)
                } else {
                    mapOf("action" to 1)
                },
                sourceAffinity = emptyMap(),
                blockedGroups = if (mode.usesInstalledCorpus) setOf("blocked") else emptySet(),
            )
        } else {
            realProfile()
        }

    fun sources(mode: ForYouDebugFixtureMode, realSources: List<Source>): List<Source> {
        sourceIds(mode).forEach(SourceRuntimeFailureRegistry::clear)
        requestCounts.clear()
        return when (mode) {
            ForYouDebugFixtureMode.OFF -> realSources
            ForYouDebugFixtureMode.INSTALLED_CORPUS,
            ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE,
            ->
                realSources
                    .filter { it.id in sourceIds(mode) }
                    .map { source ->
                        if (
                            mode == ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE &&
                            source.id == INSTALLED_ALPHA_SOURCE_ID &&
                            source is CatalogueSource
                        ) {
                            SlowCatalogueSource(source)
                        } else {
                            source
                        }
                    }
            else -> fixtureSources(mode)
        }
    }

    fun browseSource(sourceKey: Long, fixtureBuildEnabled: Boolean, preferenceValue: String): CatalogueSource? {
        val mode = resolveMode(fixtureBuildEnabled, preferenceValue)
        if (sourceKey !in sourceIds(mode)) return null
        return fixtureSources(mode).firstOrNull { it.id == sourceKey }
    }

    private fun fixtureSources(mode: ForYouDebugFixtureMode): List<FixtureSource> = when (mode) {
        ForYouDebugFixtureMode.OFF -> emptyList()
        ForYouDebugFixtureMode.TOP_PICKS -> listOf(
            FixtureSource(SOURCE_A_ID, "Fixture Source A", page("alpha")),
            FixtureSource(SOURCE_B_ID, "Fixture Source B", page("beta")),
        )
        ForYouDebugFixtureMode.TOP_PICKS_PARTIAL_FAILURE -> listOf(
            FixtureSource(SOURCE_A_ID, "Fixture Source A", page("alpha")),
            FixtureSource(SOURCE_B_ID, "Fixture Source B") { _, _ ->
                throw IllegalStateException("fixture-source-unavailable")
            },
            FixtureSource(SOURCE_C_ID, "Fixture Source C") { _, _ ->
                throw IllegalStateException("fixture-source-unavailable")
            },
        )
        ForYouDebugFixtureMode.INSTALLED_CORPUS,
        ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE,
        -> emptyList()
    }

    internal fun requestCountSnapshot(): Map<Long, Int> =
        requestCounts.mapValues { (_, count) -> count.get() }

    private fun recordRequest(sourceId: Long, sourceName: String) {
        val count = requestCounts.getOrPut(sourceId) { AtomicInteger() }.incrementAndGet()
        logcat(LogPriority.INFO, tag = "ForYouFixture") {
            "request source=$sourceName id=$sourceId count=$count"
        }
    }

    fun sourceIds(mode: ForYouDebugFixtureMode): Set<Long> = when (mode) {
        ForYouDebugFixtureMode.OFF -> emptySet()
        ForYouDebugFixtureMode.TOP_PICKS -> setOf(SOURCE_A_ID, SOURCE_B_ID)
        ForYouDebugFixtureMode.TOP_PICKS_PARTIAL_FAILURE -> setOf(SOURCE_A_ID, SOURCE_B_ID, SOURCE_C_ID)
        ForYouDebugFixtureMode.INSTALLED_CORPUS,
        ForYouDebugFixtureMode.INSTALLED_CORPUS_SLOW_SOURCE,
        -> setOf(INSTALLED_ALPHA_SOURCE_ID, INSTALLED_BETA_SOURCE_ID)
    }

    private fun page(prefix: String): suspend (String, FilterList) -> MangasPage = { query, filters ->
        val selections = filters
            .filterIsInstance<Filter.Group<*>>()
            .flatMap { it.state }
            .filterIsInstance<Filter.TriState>()
            .filterNot { it.isIgnored() }
            .associate { it.name to it.state }
        val includes = selections.filterValues { it == Filter.TriState.STATE_INCLUDE }.keys
        val excludes = selections.filterValues { it == Filter.TriState.STATE_EXCLUDE }.keys
        MangasPage(
            mangas = (1..5).map { index ->
                val genre = FIXTURE_GENRES[(index - 1) % FIXTURE_GENRES.size]
                SManga(
                    url = "/fixture/$prefix/$index",
                    title = "Fixture $prefix manga $index",
                    description = "Deterministic fixture candidate $index",
                    genre = genre,
                    status = SManga.ONGOING,
                    initialized = true,
                )
            }.filter { manga ->
                val genre = manga.genre.orEmpty()
                val queryMatches = query.isBlank() || manga.title.contains(query, ignoreCase = true) ||
                    genre.contains(query, ignoreCase = true)
                queryMatches && genre !in excludes && (includes.isEmpty() || genre in includes)
            },
            hasNextPage = false,
        )
    }

    private class FixtureSource(
        override val id: Long,
        override val name: String,
        private val search: suspend (String, FilterList) -> MangasPage,
    ) : CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false

        override fun getFilterList() = FilterList(FixtureGenresFilter())
        override suspend fun getPopularManga(page: Int): MangasPage {
            recordRequest(id, name)
            return search("", getFilterList())
        }
        override suspend fun getLatestUpdates(page: Int): MangasPage =
            throw UnsupportedOperationException("fixture-latest-not-supported")
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            recordRequest(id, name)
            return search(query, filters)
        }
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private class SlowCatalogueSource(
        private val delegate: CatalogueSource,
    ) : CatalogueSource by delegate {
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            logcat(LogPriority.INFO, tag = "ForYouFixture") {
                "slow_source_wait_started source_id=${delegate.id} delay_ms=$SLOW_SOURCE_DELAY_MILLIS"
            }
            try {
                delay(SLOW_SOURCE_DELAY_MILLIS)
            } catch (e: CancellationException) {
                logcat(LogPriority.INFO, tag = "ForYouFixture") {
                    "slow_source_request_cancelled source_id=${delegate.id} phase=wait delegated=false"
                }
                throw e
            }
            logcat(LogPriority.INFO, tag = "ForYouFixture") {
                "slow_source_request_delegated source_id=${delegate.id}"
            }
            val result = delegate.getSearchManga(page, query, filters)
            logcat(LogPriority.INFO, tag = "ForYouFixture") {
                "slow_source_request_completed source_id=${delegate.id} candidates=${result.mangas.size} " +
                    "fixture_payload_bytes=${fixturePayloadBytes(result)}"
            }
            return result
        }
    }

    /**
     * UTF-8 size of the url, title and thumbnail strings in a fixture response. The corpus sources answer
     * in memory, so this is a payload-size figure for the fixture, not network traffic.
     */
    internal fun fixturePayloadBytes(page: MangasPage): Int = page.mangas.sumOf {
        it.url.toByteArray().size + it.title.toByteArray().size + (it.thumbnail_url?.toByteArray()?.size ?: 0)
    }

    private class FixtureGenre(name: String) : Filter.TriState(name)

    private class FixtureGenresFilter : Filter.Group<FixtureGenre>("Genres", FIXTURE_GENRES.map(::FixtureGenre))

    private val FIXTURE_GENRES = listOf("Action", "Comedy", "Fantasy", "Romance")
}
