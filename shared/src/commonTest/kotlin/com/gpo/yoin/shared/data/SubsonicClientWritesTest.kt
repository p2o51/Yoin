package com.gpo.yoin.shared.data

import com.gpo.yoin.shared.data.subsonic.AlbumListType
import com.gpo.yoin.shared.data.subsonic.SubsonicClient
import com.gpo.yoin.shared.data.subsonic.SubsonicErrorKind
import com.gpo.yoin.shared.data.subsonic.SubsonicException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Playlist writes, getSong, OpenSubsonic `played`, and concurrent calls. */
class SubsonicClientWritesTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(routes: Map<String, String>): SubsonicClient {
        val engine = MockEngine { request ->
            requests += request
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            respond(
                content = routes[endpoint] ?: SubsonicFixtures.failed(0, "Unknown endpoint $endpoint"),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SubsonicClient("https://music.example.com", "alice", "sesame", "c19b2d", engine)
    }

    private val urls: List<Url> get() = requests.map { it.url }
    private fun Url.endpoint() = encodedPath.substringAfterLast('/').removeSuffix(".view")

    private val createdPlaylist = SubsonicFixtures.ok(
        """
        "playlist": {"id": "pl9", "name": "Night Drive", "owner": "alice", "public": false,
          "songCount": 2, "created": "2026-11-04T00:00:00Z",
          "entry": [{"id": "s-1", "title": "One"}, {"id": "s-2", "title": "Two"}]}
        """.trimIndent(),
    )

    // ------------------------------------------------------- createPlaylist

    @Test
    fun should_sendNameAndRepeatedSongIds_when_createPlaylist() = runTest {
        val detail = assertNotNull(
            client(mapOf("createPlaylist" to createdPlaylist)).createPlaylist("Night Drive", null, listOf("s-1", "s-2")),
        )
        assertEquals(1, requests.size, "no comment → no follow-up updatePlaylist")
        val url = urls.single()
        assertEquals("createPlaylist", url.endpoint())
        assertEquals("Night Drive", url.parameters["name"])
        assertEquals(listOf("s-1", "s-2"), url.parameters.getAll("songId"))
        assertEquals("pl9", detail.summary.id)
        assertTrue(detail.summary.canWrite)
        assertEquals(true, detail.summary.ownedByMe)
        assertEquals(listOf("One", "Two"), detail.entries.map { it.title })
    }

    @Test
    fun should_setCommentWithFollowUpUpdate_when_createPlaylistHasComment() = runTest {
        val c = client(mapOf("createPlaylist" to createdPlaylist, "updatePlaylist" to SubsonicFixtures.ok()))
        c.createPlaylist("Night Drive", "for the road", emptyList())
        assertEquals(listOf("createPlaylist", "updatePlaylist"), urls.map { it.endpoint() })
        assertNull(urls[0].parameters["songId"])
        assertNull(urls[0].parameters["comment"], "createPlaylist has no comment parameter")
        assertEquals("pl9", urls[1].parameters["playlistId"])
        assertEquals("for the road", urls[1].parameters["comment"])
        assertNull(urls[1].parameters["name"])
    }

    @Test
    fun should_skipFollowUp_when_commentBlank() = runTest {
        client(mapOf("createPlaylist" to createdPlaylist)).createPlaylist("x", "  ", emptyList())
        assertEquals(listOf("createPlaylist"), urls.map { it.endpoint() })
    }

    @Test
    fun should_returnNull_when_oldServerCreatesWithoutPlaylistBody() = runTest {
        val c = client(mapOf("createPlaylist" to SubsonicFixtures.ok(), "updatePlaylist" to SubsonicFixtures.ok()))
        assertNull(c.createPlaylist("x", "comment", listOf("s-1")))
        assertEquals(listOf("createPlaylist"), urls.map { it.endpoint() }, "no id to set the comment on")
    }

    // ------------------------------------------------------- updatePlaylist

    @Test
    fun should_sendOnlyGivenParts_when_updatePlaylist() = runTest {
        val c = client(mapOf("updatePlaylist" to SubsonicFixtures.ok()))
        c.updatePlaylist("pl1", "Renamed", null, null, emptyList(), emptyList())
        val rename = urls.last()
        assertEquals("pl1", rename.parameters["playlistId"])
        assertEquals("Renamed", rename.parameters["name"])
        assertNull(rename.parameters["comment"])
        assertNull(rename.parameters["public"])
        assertNull(rename.parameters["songIdToAdd"])
        assertNull(rename.parameters["songIndexToRemove"])

        c.updatePlaylist("pl1", null, "", true, listOf("s-7", "s-8", "s-7"), emptyList())
        val edit = urls.last()
        assertNull(edit.parameters["name"])
        assertEquals("", edit.parameters["comment"], "an empty comment clears it")
        assertEquals("true", edit.parameters["public"])
        assertEquals(listOf("s-7", "s-8", "s-7"), edit.parameters.getAll("songIdToAdd"), "adds keep order and duplicates")
    }

    @Test
    fun should_sendRemovalIndexesDescending_when_updatePlaylistRemoves() = runTest {
        client(mapOf("updatePlaylist" to SubsonicFixtures.ok()))
            .updatePlaylist("pl1", null, null, false, emptyList(), listOf(1, 4, 0, 4))
        val url = urls.single()
        assertEquals(listOf("4", "1", "0"), url.parameters.getAll("songIndexToRemove"))
        assertEquals("false", url.parameters["public"])
    }

    @Test
    fun should_throwNotAuthorized_when_updatingSomeoneElsesPlaylist() = runTest {
        val c = client(mapOf("updatePlaylist" to SubsonicFixtures.failed(50, "not owner")))
        val e = assertFailsWith<SubsonicException> { c.updatePlaylist("pl2", "x", null, null, emptyList(), emptyList()) }
        assertEquals(SubsonicErrorKind.NOT_AUTHORIZED, e.kind)
    }

    // ------------------------------------------------------- deletePlaylist

    @Test
    fun should_sendId_when_deletePlaylist() = runTest {
        client(mapOf("deletePlaylist" to SubsonicFixtures.ok())).deletePlaylist("pl1")
        val url = urls.single()
        assertEquals("deletePlaylist", url.endpoint())
        assertEquals("pl1", url.parameters["id"])
    }

    @Test
    fun should_throwNotFound_when_deletingMissingPlaylist() = runTest {
        val c = client(mapOf("deletePlaylist" to SubsonicFixtures.failed(70, "Playlist not found")))
        assertEquals(SubsonicErrorKind.NOT_FOUND, assertFailsWith<SubsonicException> { c.deletePlaylist("nope") }.kind)
    }

    // -------------------------------------------------------------- getSong

    @Test
    fun should_mapSong_when_getSong() = runTest {
        val body = SubsonicFixtures.ok(
            """
            "song": {"id": "s-5", "title": "Five", "album": "Album", "albumId": "al-1", "artist": "A",
              "artistId": "ar-1", "duration": 200, "starred": "2026-01-01T00:00:00Z", "userRating": 3,
              "playCount": 12, "played": "2026-11-03T21:15:00Z"}
            """.trimIndent(),
        )
        val song = assertNotNull(client(mapOf("getSong" to body)).getSong("s-5"))
        assertEquals("s-5", urls.single().parameters["id"])
        assertEquals("Five", song.title)
        assertEquals("al-1", song.albumId)
        assertTrue(song.starred)
        assertEquals(3, song.userRating)
        assertEquals(12, song.playCount)
        assertEquals("2026-11-03T21:15:00Z", song.played)
    }

    @Test
    fun should_returnNullOrThrow_when_getSongMissing() = runTest {
        assertNull(client(mapOf("getSong" to SubsonicFixtures.ok())).getSong("s-1"))
        val c = client(mapOf("getSong" to SubsonicFixtures.failed(70, "Song not found")))
        assertEquals(SubsonicErrorKind.NOT_FOUND, assertFailsWith<SubsonicException> { c.getSong("s-x") }.kind)
    }

    // --------------------------------------------------------------- played

    @Test
    fun should_exposePlayed_when_serverSendsIt() = runTest {
        val body = SubsonicFixtures.ok(
            """
            "albumList2": {"album": [
              {"id": "al-1", "name": "Recent", "played": "2026-11-04T08:00:00.123Z",
               "song": [{"id": "s-1", "played": "2026-11-04T08:00:00Z"}, {"id": "s-2"}]},
              {"id": "al-2", "name": "Never", "played": ""},
              {"id": "al-3", "name": "Plain"}
            ]}
            """.trimIndent(),
        )
        val albums = client(mapOf("getAlbumList2" to body)).getAlbumList2(AlbumListType.RECENT, 10, 0)
        assertEquals("2026-11-04T08:00:00.123Z", albums[0].played)
        assertEquals("2026-11-04T08:00:00Z", albums[0].songs[0].played)
        assertNull(albums[0].songs[1].played)
        assertNull(albums[1].played, "empty string = never played")
        assertNull(albums[2].played)
    }

    // ----------------------------------------------------------- concurrency

    @Test
    fun should_answerEachCaller_when_calledConcurrentlyFromManyThreads() = runTest {
        // 40 overlapping calls on Dispatchers.Default (real threads; on iOS,
        // Kotlin/Native workers) through one client: each gets its own answer.
        val engine = MockEngine { request ->
            delay(50)
            val id = request.url.parameters["id"]!!
            respond(
                content = SubsonicFixtures.ok(""""song": {"id": "$id", "title": "t-$id"}"""),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val c = SubsonicClient("https://h", "alice", "sesame", "c19b2d", engine)
        val titles = withContext(Dispatchers.Default) {
            (1..40).map { i -> async { c.getSong("s-$i")?.title } }.awaitAll()
        }
        assertEquals((1..40).map { "t-s-$it" }, titles)
        c.close()
    }
}
