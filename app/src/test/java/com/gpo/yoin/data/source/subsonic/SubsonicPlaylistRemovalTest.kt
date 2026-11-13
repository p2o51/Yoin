package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.remote.Playlist
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicError
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.repository.SubsonicException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing a song from a Subsonic playlist: `updatePlaylist.view` removes by
 * index, so the index is found in the playlist as the server holds it now.
 */
class SubsonicPlaylistRemovalTest {

    /** A server holding one playlist's song ids; it keeps each removal's indexes. */
    private class FakeApi(private val songs: List<String>?) {
        val calls = mutableListOf<String>()
        val removed = mutableListOf<List<Int>>()

        val api: SubsonicApi = Proxy.newProxyInstance(
            SubsonicApi::class.java.classLoader,
            arrayOf(SubsonicApi::class.java),
            InvocationHandler { _, method, args ->
                calls += method.name
                when (method.name) {
                    "getPlaylist" -> if (songs == null) {
                        failed()
                    } else {
                        ok(Playlist(id = "pl-1", name = "Playlist", entry = songs.map { Song(id = it) }))
                    }
                    "updatePlaylist" -> {
                        // (playlistId, name, comment, songIdToAdd, songIndexToRemove, continuation)
                        @Suppress("UNCHECKED_CAST")
                        removed += args[4] as List<Int>
                        ok()
                    }
                    else -> error("unexpected call: ${method.name}")
                }
            },
        ) as SubsonicApi

        private fun ok(playlist: Playlist? = null) =
            SubsonicResponse(SubsonicResponseBody(status = "ok", playlist = playlist))

        private fun failed() = SubsonicResponse(
            SubsonicResponseBody(status = "failed", error = SubsonicError(code = 70, message = "Playlist not found")),
        )
    }

    private suspend fun remove(fake: FakeApi, vararg items: Pair<String, Int>): Result<String?> =
        SubsonicMusicSource(
            credentials = ServerCredentials("https://music.example", "51", "secret"),
            apiFactory = { fake.api },
        ).writeActions().removeTracksFromPlaylist(
            playlistId = MediaId.subsonic("pl-1"),
            items = items.map { (song, position) -> PlaylistItemRef(MediaId.subsonic(song), position) },
            snapshotId = null,
        )

    @Test
    fun should_removeAtThePagesPosition_when_serverStillHasTheSongThere() = runTest {
        val fake = FakeApi(listOf("a", "b", "c"))

        assertNull(remove(fake, "b" to 1).getOrThrow())

        assertEquals(listOf(listOf(1)), fake.removed)
        assertEquals(listOf("getPlaylist", "updatePlaylist"), fake.calls)
    }

    @Test
    fun should_removeWhereTheSongIsNow_when_playlistChangedSinceThePageReadIt() = runTest {
        // The page read [a, b]; another client has put x on top since.
        val fake = FakeApi(listOf("x", "a", "b"))

        remove(fake, "a" to 0).getOrThrow()

        // Index 0 would have taken x.
        assertEquals(listOf(listOf(1)), fake.removed)
    }

    @Test
    fun should_removeTheEntryTapped_when_songIsInThePlaylistTwice() = runTest {
        val fake = FakeApi(listOf("a", "b", "a"))

        remove(fake, "a" to 2).getOrThrow()

        assertEquals(listOf(listOf(2)), fake.removed)
    }

    @Test
    fun should_sendNothing_when_songIsAlreadyGoneFromTheServer() = runTest {
        val fake = FakeApi(listOf("b", "c"))

        assertNull(remove(fake, "a" to 0).getOrThrow())

        assertTrue(fake.removed.isEmpty())
        assertEquals(listOf("getPlaylist"), fake.calls)
    }

    @Test
    fun should_removeNothing_when_playlistCannotBeReadFirst() = runTest {
        val fake = FakeApi(songs = null)

        val failure = remove(fake, "a" to 0).exceptionOrNull()

        assertEquals(70, (failure as SubsonicException).code)
        assertTrue(fake.removed.isEmpty())
    }

    @Test
    fun should_sendIndexesHighestFirst_when_severalEntriesAreRemoved() = runTest {
        val fake = FakeApi(listOf("a", "b", "c", "d"))

        remove(fake, "b" to 1, "d" to 3).getOrThrow()

        assertEquals(listOf(listOf(3, 1)), fake.removed)
    }

    @Test
    fun should_findTheNearestEntry_when_songMovedOrIsListedTwice() {
        assertEquals(1, playlistIndexNearest(listOf("a", "b", "a"), "b", position = 1))
        assertEquals(2, playlistIndexNearest(listOf("a", "b", "a"), "a", position = 2))
        assertEquals(0, playlistIndexNearest(listOf("a", "b", "a"), "a", position = 0))
        // Moved down one by an insert above: the entry next to where it was, not the far one.
        assertEquals(3, playlistIndexNearest(listOf("a", "x", "b", "a"), "a", position = 2))
        assertNull(playlistIndexNearest(listOf("b", "c"), "a", position = 0))
    }
}
