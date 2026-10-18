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

    // KMK --> v0.8.24: adding a tracker waits for these uploads; progress and start date share one request
    @Test
    fun `start date is resolved first and sent with the progress upload, separately only when no progress is due`() {
        val startDate = bindSource.indexOf("val startDate = if (track.startDate <= 0) {")
        val withStartDate = bindSource.indexOf("val withStartDate = startDate?.let { track.copy(startDate = it) } ?: track")
        val progress = bindSource.indexOf(
            "tracker.setRemoteLastChapterRead(withStartDate.toDbTrack(), latestLocalReadChapterNumber.toInt())",
        )
        val onlyDate = bindSource.indexOf("} else if (startDate != null) {")
        val dateUpload = bindSource.indexOf("tracker.setRemoteStartDate(track.toDbTrack(), startDate)")

        assertTrue(startDate in 0 until withStartDate && withStartDate < progress)
        assertTrue(progress < onlyDate && onlyDate < dateUpload)
        assertTrue(Regex.fromLiteral("tracker.setRemoteStartDate(").findAll(bindSource).count() == 1)
        assertTrue(Regex.fromLiteral("tracker.setRemoteLastChapterRead(").findAll(bindSource).count() == 1)
    }
    // KMK <--
}
