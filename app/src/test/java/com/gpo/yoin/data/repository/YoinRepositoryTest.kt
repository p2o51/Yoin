package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.SongInfoDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinRepositoryTest {
    private val library = mockk<MusicLibrary>()
    private val writeActions = FakeWriteActions()
    private val source = mockk<MusicSource>()
    private val database = mockk<YoinDatabase>(relaxed = true)
    private val geminiService = mockk<GeminiService>(relaxed = true)
    private val songInfoDao = mockk<SongInfoDao>(relaxed = true)
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(source),
        activeProfileId = MutableStateFlow("spotify-profile"),
        database = database,
        geminiService = geminiService,
        songInfoDao = songInfoDao,
        geminiConfigDao = geminiConfigDao,
    )

    init {
        every { source.library() } returns library
        every { source.metadata() } returns mockk<MusicMetadata>()
        every { source.writeActions() } returns writeActions
        every { source.playback() } returns mockk<MusicPlayback>()
    }

    @Test
    fun should_returnTrue_when_pingSucceeds() = runTest {
        io.mockk.coEvery { library.ping() } returns true

        assertTrue(repository.testConnection())
    }

    @Test
    fun should_throwSubsonicException_when_pingReturnsAuthError() = runTest {
        io.mockk.coEvery { library.ping() } throws SubsonicException(
            code = 40,
            message = "Wrong username or password",
        )

        val error = try {
            repository.testConnection()
            null
        } catch (e: SubsonicException) {
            e
        }

        val subsonicError = requireNotNull(error)
        assertEquals(40, subsonicError.code)
        assertEquals("Wrong username or password", subsonicError.message)
    }

    @Test
    fun should_delegate_createPlaylist_to_writeActions_returning_new_playlist() = runTest {
        val created = Playlist(
            id = MediaId.spotify("pl1"),
            name = "Road Trip",
            owner = "alice",
            coverArt = null,
            songCount = 0,
            durationSec = null,
            canWrite = true,
        )
        writeActions.createPlaylistResult = Result.success(created)

        val result = repository.createPlaylist(name = "Road Trip")
        assertTrue(result.isSuccess)
        assertEquals(created, result.getOrNull())
        assertTrue(created.canWrite)
    }

    @Test
    fun should_delegate_addTracks_and_propagate_snapshotId() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val trackIds = listOf(MediaId.spotify("t1"), MediaId.spotify("t2"))
        writeActions.addTracksResult = Result.success("snap-after")

        val result = repository.addTracksToPlaylist(playlistId, trackIds)
        assertTrue(result.isSuccess)
        assertEquals("snap-after", result.getOrNull())
        assertEquals(playlistId, writeActions.lastAddPlaylistId)
        assertEquals(trackIds, writeActions.lastAddedTracks)
    }

    @Test
    fun should_thread_snapshotId_into_removeTracks() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val items = listOf(PlaylistItemRef(trackId = MediaId.spotify("t1"), position = 0))
        writeActions.removeTracksResult = Result.success("snap-after")

        val result = repository.removeTracksFromPlaylist(
            playlistId = playlistId,
            items = items,
            snapshotId = "snap-before",
        )
        assertTrue(result.isSuccess)
        assertEquals("snap-after", result.getOrNull())
        assertEquals(playlistId, writeActions.lastRemovePlaylistId)
        assertEquals(items, writeActions.lastRemovedItems)
        assertEquals("snap-before", writeActions.lastSnapshotId)
    }

    @Test
    fun subsonic_paths_return_null_snapshotId() = runTest {
        val subsonicPlaylist = MediaId.subsonic("42")
        val subsonicTracks = listOf(MediaId.subsonic("s1"))
        writeActions.addTracksResult = Result.success(null)

        val result = repository.addTracksToPlaylist(subsonicPlaylist, subsonicTracks)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(subsonicPlaylist, writeActions.lastAddPlaylistId)
        assertEquals(subsonicTracks, writeActions.lastAddedTracks)
    }

    private class FakeWriteActions : MusicWriteActions {
        var createPlaylistResult: Result<Playlist> =
            Result.failure(UnsupportedOperationException("createPlaylist not configured"))
        var addTracksResult: Result<String?> =
            Result.failure(UnsupportedOperationException("addTracksToPlaylist not configured"))
        var removeTracksResult: Result<String?> =
            Result.failure(UnsupportedOperationException("removeTracksFromPlaylist not configured"))

        var lastAddPlaylistId: MediaId? = null
        var lastAddedTracks: List<MediaId>? = null
        var lastRemovePlaylistId: MediaId? = null
        var lastRemovedItems: List<PlaylistItemRef>? = null
        var lastSnapshotId: String? = null

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> =
            createPlaylistResult

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = Result.failure(UnsupportedOperationException())

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> {
            lastAddPlaylistId = playlistId
            lastAddedTracks = tracks
            return addTracksResult
        }

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?,
        ): Result<String?> {
            lastRemovePlaylistId = playlistId
            lastRemovedItems = items
            lastSnapshotId = snapshotId
            return removeTracksResult
        }
    }
}
