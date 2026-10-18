package tachiyomi.domain.track.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.track.model.Track

interface TrackRepository {

    suspend fun getTrackById(id: Long): Track?

    // SY -->
    suspend fun getTracks(): List<Track>

    suspend fun getTracksByMangaIds(mangaIds: List<Long>): List<Track>
    // SY <--

    suspend fun getTracksByMangaId(mangaId: Long): List<Track>

    // KMK --> v0.8.24: tracks of every manga bound to the same remote entry. The default keeps simple fakes working.
    suspend fun getTracksByRemoteId(trackerId: Long, remoteId: Long): List<Track> =
        getTracks().filter { it.trackerId == trackerId && it.remoteId == remoteId }
    // KMK <--

    fun getTracksAsFlow(): Flow<List<Track>>

    fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>>

    suspend fun delete(mangaId: Long, trackerId: Long)

    suspend fun insert(track: Track)

    suspend fun insertAll(tracks: List<Track>)
}
