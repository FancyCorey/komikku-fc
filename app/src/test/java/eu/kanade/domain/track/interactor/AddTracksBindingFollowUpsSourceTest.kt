package eu.kanade.domain.track.interactor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AddTracksBindingFollowUpsSourceTest {
    private val bindSource = File("src/main/java/eu/kanade/domain/track/interactor/AddTracks.kt")
        .readText()
        .substringAfter("suspend fun bind(")
        .substringBefore("suspend fun bindEnhancedTrackers(")

    @Test
    fun `remote accepted binding persists before follow-up sync with distinct failure callbacks`() {
        val providerBind = bindSource.indexOf("val boundItem = tracker.bind(item, hasReadChapters)")
        val followUps = bindSource.indexOf("runTrackerBindingFollowUps(")
        val persist = bindSource.indexOf("persistLocalTrack = {")
        val share = bindSource.indexOf("ShareTrackerBindingWithLinkedVersions().await(track)")
        val synchronize = bindSource.indexOf("synchronize = {")
        val externalSync = bindSource.indexOf("syncLocalTrackingFromExternal?.sync(track, tracker)")
        val chapterSync = bindSource.indexOf("syncChapterProgressWithTrack.await(mangaId, track, tracker)")
        val localFailure = bindSource.indexOf("onLocalPersistenceFailure = { failure ->")
        val syncFailure = bindSource.indexOf("onSynchronizationFailure = { failure ->")

        assertTrue(bindSource.contains("= withNonCancellableContext"))
        assertTrue(providerBind >= 0 && providerBind < followUps)
        assertTrue(followUps < persist && persist < synchronize)
        // KMK v0.8.24: the entry is shared with linked versions only after it is saved locally, before follow-up sync
        assertTrue(persist < share && share < synchronize)
        assertTrue(synchronize < externalSync && externalSync < chapterSync)
        assertTrue(chapterSync < localFailure && localFailure < syncFailure)
        assertTrue(bindSource.contains("RemoteAcceptedLocalSaveFailed"))
        assertTrue(bindSource.contains("KMR.strings.tracking_added_sync_failed"))
    }
}
