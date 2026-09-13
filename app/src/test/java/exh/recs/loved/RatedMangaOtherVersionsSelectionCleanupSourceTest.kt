package exh.recs.loved

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RatedMangaOtherVersionsSelectionCleanupSourceTest {

    @Test
    fun `alternate version actions clear rated selection before leaving selection mode`() {
        val source = File("src/main/java/exh/recs/loved/RatedMangaScreen.kt").readText()

        assertTrue(
            source.contains(
                "RatedSelectionActionKind.FindOtherVersions -> selectedItem?.let {\n" +
                    "                                        onClearSelection()",
            ),
        )
        assertTrue(
            source.contains(
                "RatedSelectionActionKind.FavoriteOtherVersions -> selectedItem?.let {\n" +
                    "                                        onClearSelection()",
            ),
        )
        assertTrue(
            source.contains(
                "RatedSelectionActionKind.FindOtherVersions -> selectedItem?.let {\n" +
                    "                                onClearSelection()",
            ),
        )
        assertTrue(
            source.contains(
                "RatedSelectionActionKind.FavoriteOtherVersions -> selectedItem?.let {\n" +
                    "                                onClearSelection()",
            ),
        )
    }

    @Test
    fun `matching cards keep active selection local to the matching route`() {
        val matchScreen = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreen.kt").readText()

        assertTrue(matchScreen.contains("selection = selectedMangaList"))
        assertTrue(
            matchScreen.contains(
                "MangaIdentityKey(manga.source, manga.url) in state.selectedKeys",
            ),
        )
        assertTrue(matchScreen.contains("isSelected = { manga ->"))
    }
}
