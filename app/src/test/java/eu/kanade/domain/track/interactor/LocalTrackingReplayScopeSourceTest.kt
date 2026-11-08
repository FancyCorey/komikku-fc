package eu.kanade.domain.track.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: replaying saved local progress for every tracked work took long enough to be felt on every manga page and while
 * tracking other versions. Only a library update and a restore replay the whole library now; everything a person does to one
 * manga replays that manga's own record.
 */
class LocalTrackingReplayScopeSourceTest {

    private fun source(path: String) = File(path).readText()

    @Test
    fun `opening a manga, the tracking dialog and a tracker refresh replay only that manga's record`() {
        val manga = source("src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt")
        val dialog = source("src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt")
        val refresh = source("src/main/java/eu/kanade/domain/track/interactor/RefreshTracks.kt")
        listOf(manga, dialog, refresh).forEach { text ->
            assertFalse(Regex("\\.synchronize\\(\\)").containsMatchIn(text), "a whole-library replay is back")
        }
        assertTrue(manga.contains("synchronizeFor("))
        assertTrue(dialog.contains("synchronizeFor("))
        assertTrue(refresh.contains("synchronizeForMangaId(mangaId)"))
    }

    @Test
    fun `a tracker sync replays the work it just wrote, not every work`() {
        val sync = source("src/main/java/eu/kanade/domain/track/interactor/SyncLocalTrackingFromExternal.kt")
        assertEquals(2, Regex("synchronize\\(setOf\\(workId\\)\\)").findAll(sync).count())
    }

    @Test
    fun `joining a group replays the group's own record`() {
        val join = source("src/main/java/exh/recs/matching/ConfirmedGroupJoinSynchronizer.kt")
        assertTrue(join.contains("synchronizeFor(it)"))
        assertFalse(Regex("\\.synchronize\\(\\)").containsMatchIn(join))
    }

    @Test
    fun `the whole library is still replayed after a library update and after a restore`() {
        assertTrue(source("src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt").contains("recordLocalTrackedChapterProgress.synchronize()"))
        assertTrue(source("src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/LocalTrackerBackupRestorer.kt").contains("interactor.synchronize()"))
    }
}
