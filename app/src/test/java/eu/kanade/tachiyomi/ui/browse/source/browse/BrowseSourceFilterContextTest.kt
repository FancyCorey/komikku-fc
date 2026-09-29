package eu.kanade.tachiyomi.ui.browse.source.browse

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.source.SavedSearchRepositoryImpl
import tachiyomi.domain.source.interactor.GetSavedSearchById
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.repository.SavedSearchRepository
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.nio.file.Files

class BrowseSourceFilterContextTest {
    private val serializer = FilterSerializer()

    @Test
    fun `compatible source filter context restores selected values`() {
        val savedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)
        val filtersJson = serializer.serialize(savedFilters).toString()
        val currentFilters = filters()

        assertTrue(restoreBrowseSourceFilters(currentFilters, filtersJson, serializer))
        assertEquals(Filter.TriState.STATE_INCLUDE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(1, (currentFilters[1] as Filter.Select<*>).state)
    }

    @Test
    fun `saved filter snapshot restores all selected values`() {
        val savedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)
        val savedJson = serializer.serialize(savedFilters)
        val currentFilters = filters()

        assertTrue(serializer.deserializeIfCompatible(currentFilters, savedJson))
        assertEquals(Filter.TriState.STATE_INCLUDE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(1, (currentFilters[1] as Filter.Select<*>).state)
    }

    @Test
    fun `explicit saved search survives reopening sqlite database`() = runTest {
        val databaseFile = Files.createTempFile("kmk-saved-search-", ".db")
        val jdbcUrl = "jdbc:sqlite:${databaseFile.toAbsolutePath()}"
        try {
            val chosenFilters = filters(actionState = Filter.TriState.STATE_EXCLUDE, orderIndex = 1)
            val insertedId = JdbcSqliteDriver(jdbcUrl).let { writer ->
                try {
                    Database.Schema.create(writer)
                    InsertSavedSearch(savedSearchRepository(writer)).await(
                        SavedSearch(
                            id = -1,
                            source = 42,
                            name = "Adventure",
                            query = "swords",
                            filtersJson = Json.encodeToString(serializer.serialize(chosenFilters)),
                        ),
                    ) ?: error("Saved search was not inserted")
                } finally {
                    writer.close()
                }
            }

            JdbcSqliteDriver(jdbcUrl).use { reopenedDriver ->
                val repository = savedSearchRepository(reopenedDriver)
                val loader = GetExhSavedSearch(
                    GetSavedSearchById(repository),
                    GetSavedSearchBySourceId(repository),
                    serializer,
                )
                val loadedSearch = loader.awaitOne(insertedId) { filters() } ?: error("Saved search was not found")
                val restoredFilters = loadedSearch.filterList ?: error("Saved filters were not restored")

                assertEquals("Adventure", loadedSearch.name)
                assertEquals("swords", loadedSearch.query)
                assertEquals(Filter.TriState.STATE_EXCLUDE, (restoredFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
                assertEquals(1, (restoredFilters[1] as Filter.Select<*>).state)

                val changedSchemaFilters = filters(extraGenre = true)
                val staleSearch = loader.awaitOne(insertedId) { changedSchemaFilters } ?: error("Saved search was not found")

                assertEquals(null, staleSearch.filterList)
                assertEquals(Filter.TriState.STATE_IGNORE, (changedSchemaFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
                assertEquals(0, (changedSchemaFilters[1] as Filter.Select<*>).state)
            }
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    private fun savedSearchRepository(driver: JdbcSqliteDriver): SavedSearchRepositoryImpl {
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        return SavedSearchRepositoryImpl(AndroidDatabaseHandler(database, driver))
    }

    @Test
    fun `saved filter snapshot with changed schema is rejected without partial values`() {
        val savedJson = serializer.serialize(filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1))
        val currentFilters = filters(extraGenre = true)

        assertFalse(serializer.deserializeIfCompatible(currentFilters, savedJson))
        assertEquals(Filter.TriState.STATE_IGNORE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (currentFilters[1] as Filter.Select<*>).state)
    }

    @Test
    fun `For You reset restores inherited filters for the active visit`() {
        val inheritedFilters = filters(actionState = Filter.TriState.STATE_EXCLUDE, orderIndex = 1)
        val inheritedJson = serializer.serialize(inheritedFilters).toString()
        val editedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 0)

        val reset = resetBrowseSourceFilters(editedFilters, inheritedJson, serializer)

        assertEquals(Filter.TriState.STATE_EXCLUDE, (reset[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(1, (reset[1] as Filter.Select<*>).state)
    }

    @Test
    fun `ordinary Browse reset keeps current source defaults`() {
        val defaults = filters()
        val reset = resetBrowseSourceFilters(defaults, inheritedFiltersJson = null, serializer)

        assertEquals(Filter.TriState.STATE_IGNORE, (reset[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (reset[1] as Filter.Select<*>).state)
    }

    @Test
    fun `screen model reset uses inherited filters only for a valid For You visit`() {
        val path = "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt"
        val sourcePath = listOf(java.nio.file.Path.of(path), java.nio.file.Path.of("..", path))
            .map(java.nio.file.Path::toAbsolutePath)
            .firstOrNull(java.nio.file.Files::isRegularFile)
            ?: error("Unable to resolve source: $path")
        val source = java.nio.file.Files.readAllBytes(sourcePath).toString(Charsets.UTF_8)

        assertTrue(source.contains("inheritedFiltersJson = filtersJson.takeIf { forYouRestrictionContext.restrictions != null }"))
    }

    @Test
    fun `For You reset falls back atomically when inherited schema changed`() {
        val defaults = filters()
        val incompatibleSnapshot = serializer.serialize(filters(actionState = Filter.TriState.STATE_EXCLUDE, orderIndex = 1, extraGenre = true)).toString()

        val reset = resetBrowseSourceFilters(defaults, incompatibleSnapshot, serializer)

        assertEquals(Filter.TriState.STATE_IGNORE, (reset[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (reset[1] as Filter.Select<*>).state)
    }

    @Test
    fun `restored active visit filters take precedence over inherited filters`() {
        val activeVisit = serializer.serialize(filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)).toString()
        val inherited = serializer.serialize(filters(actionState = Filter.TriState.STATE_EXCLUDE)).toString()
        val current = filters()

        assertTrue(restoreBrowseSourceVisitFilters(current, activeVisit, inherited, serializer))
        assertEquals(Filter.TriState.STATE_INCLUDE, (current[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(1, (current[1] as Filter.Select<*>).state)
    }

    @Test
    fun `incompatible active visit snapshot falls back to compatible inherited filters`() {
        val incompatibleVisit = serializer.serialize(filters(extraGenre = true)).toString()
        val inherited = serializer.serialize(filters(actionState = Filter.TriState.STATE_EXCLUDE, orderIndex = 1)).toString()
        val current = filters()

        assertTrue(restoreBrowseSourceVisitFilters(current, incompatibleVisit, inherited, serializer))
        assertEquals(Filter.TriState.STATE_EXCLUDE, (current[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(1, (current[1] as Filter.Select<*>).state)
    }

    @Test
    fun `restored active visit does not partially apply either incompatible snapshot`() {
        val current = filters()
        val incompatibleVisit = serializer.serialize(filters(extraGenre = true)).toString()
        val incompatibleInherited = serializer.serialize(filters(actionState = Filter.TriState.STATE_EXCLUDE, orderIndex = 1, extraGenre = true)).toString()

        assertFalse(restoreBrowseSourceVisitFilters(current, incompatibleVisit, incompatibleInherited, serializer))
        assertEquals(Filter.TriState.STATE_IGNORE, (current[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (current[1] as Filter.Select<*>).state)
    }

    @Test
    fun `browse screen saveable visit snapshot is passed to model and updated from filter edits`() {
        val screenPath = "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt"
        val sourcePath = listOf(java.nio.file.Path.of(screenPath), java.nio.file.Path.of("..", screenPath))
            .map(java.nio.file.Path::toAbsolutePath)
            .firstOrNull(java.nio.file.Files::isRegularFile)
            ?: error("Unable to resolve source: $screenPath")
        val source = java.nio.file.Files.readAllBytes(sourcePath).toString(Charsets.UTF_8)

        assertTrue(source.contains("rememberSaveable(sourceId, filtersJson, forYouRestrictionsJson, savedSearch)"))
        assertTrue(source.contains("val isForYouBrowseVisit = forYouRestrictionsJson != null"))
        assertTrue(source.contains("visitFiltersJson = visitFiltersJson.takeIf { isForYouBrowseVisit }"))
        assertTrue(source.contains("visitFiltersJson = visitFiltersJson"))
        assertTrue(source.contains("visitFiltersJson = screenModel.serializeVisitFilters(filters)"))
    }

    @Test
    fun `durable saved searches reject incompatible source filter snapshots`() {
        val sourcePath = listOf(
            java.nio.file.Path.of("app/src/main/java/eu/kanade/domain/source/interactor/GetExhSavedSearch.kt"),
            java.nio.file.Path.of("..", "app/src/main/java/eu/kanade/domain/source/interactor/GetExhSavedSearch.kt"),
        ).map(java.nio.file.Path::toAbsolutePath)
            .firstOrNull(java.nio.file.Files::isRegularFile)
            ?: error("Unable to resolve saved-search interactor source")
        val source = java.nio.file.Files.readAllBytes(sourcePath).toString(Charsets.UTF_8)

        assertTrue(source.contains("filterSerializer.deserializeIfCompatible(originalFilters, filters)"))
    }

    @Test
    fun `changed source filter schema falls back before applying any old values`() {
        val savedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)
        val filtersJson = serializer.serialize(savedFilters).toString()
        val currentFilters = filters(extraGenre = true)

        assertFalse(restoreBrowseSourceFilters(currentFilters, filtersJson, serializer))
        assertEquals(Filter.TriState.STATE_IGNORE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (currentFilters[1] as Filter.Select<*>).state)
    }

    @Test
    fun `later incompatible filter prevents earlier compatible values from being restored`() {
        val savedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)
        val filtersJson = serializer.serialize(savedFilters).toString()
        val action = object : Filter.TriState("Action") {}
        val genreGroup = object : Filter.Group<Filter<*>>("Genre", listOf(action)) {}
        val changedOrder = object : Filter.Select<String>("Order", arrayOf("Popular", "Oldest"), 0) {}
        val currentFilters = FilterList(genreGroup, changedOrder)

        assertFalse(restoreBrowseSourceFilters(currentFilters, filtersJson, serializer))
        assertEquals(Filter.TriState.STATE_IGNORE, action.state)
        assertEquals(0, changedOrder.state)
    }

    @Test
    fun `invalid saved selector state is rejected without partial mutation`() {
        val savedFilters = filters(actionState = Filter.TriState.STATE_INCLUDE, orderIndex = 1)
        val filtersJson = serializer.serialize(savedFilters).toString().replace("\"state\":\"1\"", "\"state\":\"99\"")
        val currentFilters = filters()

        assertFalse(restoreBrowseSourceFilters(currentFilters, filtersJson, serializer))
        assertEquals(Filter.TriState.STATE_IGNORE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
        assertEquals(0, (currentFilters[1] as Filter.Select<*>).state)
    }

    @Test
    fun `malformed group state is rejected without crashing or mutation`() {
        val currentFilters = filters()
        val filtersJson = """[{"_type":"GROUP","state":"invalid","name":"Genre","_cmaps":{"name":"java.lang.String"}}]"""

        assertFalse(restoreBrowseSourceFilters(currentFilters, filtersJson, serializer))
        assertEquals(Filter.TriState.STATE_IGNORE, (currentFilters[0] as Filter.Group<*>).state.let { (it[0] as Filter.TriState).state })
    }

    private fun filters(
        actionState: Int = Filter.TriState.STATE_IGNORE,
        orderIndex: Int = 0,
        extraGenre: Boolean = false,
    ): FilterList {
        val genres = buildList<Filter<*>> {
            add(object : Filter.TriState("Action", actionState) {})
            if (extraGenre) add(object : Filter.TriState("Romance") {})
        }
        val genreGroup = object : Filter.Group<Filter<*>>("Genre", genres) {}
        val order = object : Filter.Select<String>("Order", arrayOf("Popular", "Latest"), orderIndex) {}
        return FilterList(genreGroup, order)
    }
}
