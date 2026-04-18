package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val writeActions = mockk<MusicWriteActions>()
    private val source = mockk<MusicSource>()
    private val database = mockk<YoinDatabase>(relaxed = true)

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(source),
        database = database,
    )

    init {
        every { source.library() } returns library
        every { source.metadata() } returns mockk<MusicMetadata>()
        every { source.writeActions() } returns writeActions
        every { source.playback() } returns mockk<MusicPlayback>()
    }

    @Test
    fun should_returnTrue_when_pingSucceeds() = runTest {
        coEvery { library.ping() } returns true

        assertTrue(repository.testConnection())
    }

    @Test
    fun should_throwSubsonicException_when_pingReturnsAuthError() = runTest {
        coEvery { library.ping() } throws SubsonicException(
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
        coEvery { writeActions.createPlaylist(name = "Road Trip", description = null) } returns
            Result.success(created)

        val result = repository.createPlaylist(name = "Road Trip")
        assertEquals(created, result.getOrThrow())
        assertTrue(created.canWrite)
    }

    @Test
    fun should_delegate_addTracks_and_propagate_snapshotId() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val trackIds = listOf(MediaId.spotify("t1"), MediaId.spotify("t2"))
        coEvery { writeActions.addTracksToPlaylist(playlistId, trackIds) } returns
            Result.success("snap-after")

        val result = repository.addTracksToPlaylist(playlistId, trackIds)
        assertEquals("snap-after", result.getOrThrow())
    }

    @Test
    fun should_thread_snapshotId_into_removeTracks() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val items = listOf(PlaylistItemRef(trackId = MediaId.spotify("t1"), position = 0))
        coEvery {
            writeActions.removeTracksFromPlaylist(playlistId, items, "snap-before")
        } returns Result.success("snap-after")

        val result = repository.removeTracksFromPlaylist(
            playlistId = playlistId,
            items = items,
            snapshotId = "snap-before",
        )
        assertEquals("snap-after", result.getOrThrow())
        coVerify {
            writeActions.removeTracksFromPlaylist(playlistId, items, "snap-before")
        }
    }

    @Test
    fun subsonic_paths_return_null_snapshotId() = runTest {
        val subsonicPlaylist = MediaId.subsonic("42")
        val subsonicTracks = listOf(MediaId.subsonic("s1"))
        coEvery { writeActions.addTracksToPlaylist(subsonicPlaylist, subsonicTracks) } returns
            Result.success(null)

        val result = repository.addTracksToPlaylist(subsonicPlaylist, subsonicTracks)
        assertNull(result.getOrThrow())
    }
}
