package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Guards direct local-progress editing through the same source chapter rows used for persistence. */
class LocalTrackingChapterSelectionSourceTest {

    @Test
    fun `local chapter action opens the editor and resolves an exact source chapter when saving`() {
        val source = File(
            "src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt",
        ).readText()
        val openDialog = source.indexOf("fun openLocalChapterDialog()")
        val saveProgress = source.indexOf("fun saveLocalChapterProgress(chapterNumber: Double?)")
        val sourceChapterLookup = source.indexOf("getChaptersByMangaId.await(manga.id)", saveProgress)
        val exactChapterMatch = source.indexOf("it.chapterNumber == number", sourceChapterLookup)
        val progressWrite = source.indexOf("localTrackerRepository.upsertSourceProgress", exactChapterMatch)

        assertTrue(openDialog >= 0, "local chapter action must open the progress editor")
        assertTrue(saveProgress > openDialog, "the editor must expose a save boundary")
        assertTrue(sourceChapterLookup > saveProgress, "saving must inspect this manga's source chapters")
        assertTrue(exactChapterMatch > sourceChapterLookup, "saving must resolve the exact selected source chapter")
        assertTrue(progressWrite > exactChapterMatch, "resolved source identity must be persisted")
    }
}
