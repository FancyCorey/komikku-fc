package eu.kanade.tachiyomi.ui.reader.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Locks the boundary between alternate-source navigation and explicit library membership. */
class AlternateSourceReaderLibraryBoundarySourceTest {

    @Test
    fun `alternate-source reader flow never promotes a manga into the library`() {
        val viewModel = source("ui/reader/ReaderViewModel.kt")
        val gateway = source("ui/reader/bridge/AlternateSourceReaderCandidateGateway.kt")
        val activity = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText()
        val topBar = File("src/main/java/eu/kanade/presentation/reader/appbars/ReaderTopBar.kt").readText()
        val globalSearch = File("src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt").readText()
        val sourceBrowse = File("src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt").readText()

        assertFalse(viewModel.contains("copy(favorite = true)"))
        assertFalse(gateway.contains("copy(favorite = true)"))
        assertTrue(viewModel.contains("openAlternateSourceChooserForCurrentChapter"))
        assertTrue(viewModel.contains("requestReturnToPrimarySource"))
        assertTrue(viewModel.contains("addCurrentMangaToLibrary"))
        assertTrue(activity.contains("alternateSourceActions.addToLibrary"))
        assertTrue(topBar.contains("add_to_library"))
        assertTrue(globalSearch.contains("if (returnSelection)"))
        assertTrue(sourceBrowse.contains("if (returnSelection)"))
    }

    @Test
    fun `ordinary browse presentation keeps library decoration owned by favorite state`() {
        val badges = File("src/main/java/eu/kanade/presentation/browse/components/BrowseBadges.kt").readText()
        val searchRow = File("src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt").readText()
        val ratedScreen = File("src/main/java/exh/recs/loved/RatedMangaScreen.kt").readText()
        val matchingScreen = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreen.kt").readText()

        assertTrue(badges.contains("fun InLibraryBadge(enabled: Boolean)"))
        assertTrue(searchRow.contains("InLibraryBadge(enabled = showLibraryState && isFavorite)"))
        assertTrue(searchRow.contains("showLibraryState && isFavorite"))
        assertTrue(ratedScreen.contains("showLibraryState = false"))
        assertTrue(matchingScreen.contains("showLibraryState = false"))
        assertTrue(ratedScreen.contains("item.versionCount > 1"))
        assertFalse(searchRow.contains("alternateSource"))
    }

    @Test
    fun `explicit favorite route remains the only cross-source library mutation`() {
        val source = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()
        val favoriteBlock = source.substringAfter("CrossExtensionMatchMode.Favorite")
            .substringBefore("// KMK <--")

        assertTrue(favoriteBlock.contains("updateManga.await(manga.copy(favorite = true).toMangaUpdate())"))
        assertFalse(
            source.substringBefore("CrossExtensionMatchMode.Favorite")
                .contains("copy(favorite = true)"),
        )
    }

    private fun source(path: String) = File("src/main/java/eu/kanade/tachiyomi/$path").readText()
}
