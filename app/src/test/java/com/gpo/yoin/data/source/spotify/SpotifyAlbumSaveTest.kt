package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Saving a Spotify album to the library (Q11): `PUT` / `DELETE
 * /v1/me/library` with the album's URI, its saved state asked in the album
 * page's own contains request (first in line), and a removal laid over the
 * cached saved-albums list until Spotify's list agrees.
 */
class SpotifyAlbumSaveTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private var now = SPOTIFY_TEST_BASE_EPOCH

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path]?.invoke(request)
                    ?: MockResponse().setResponseCode(404).setBody("""{"error":"unexpected $path"}""")
            }
        }
        server.start()
        routes["/v1/me/library"] = { MockResponse().setResponseCode(200) }
        // Saved: the URIs whose id ends in an even digit.
        routes["/v1/me/library/contains"] = { request ->
            val uris = request.requestUrl?.queryParameter("uris").orEmpty().split(',')
            ok(uris.joinToString(",", "[", "]") { uri -> (uri.last().digitToIntOrNull()?.rem(2) == 0).toString() })
        }
        savedAlbums("a2")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_putTheAlbumUri_when_savingAnAlbum() = runTest {
        val result = source().writeActions().setAlbumSaved(album("a1"), saved = true)

        assertTrue(result.isSuccess)
        val write = requests.single { it.requestUrl?.encodedPath == "/v1/me/library" }
        assertEquals("PUT", write.method)
        assertEquals("spotify:album:a1", write.requestUrl?.queryParameter("uris"))
    }

    @Test
    fun should_deleteTheAlbumUri_when_removingAnAlbum() = runTest {
        val result = source().writeActions().setAlbumSaved(album("a2"), saved = false)

        assertTrue(result.isSuccess)
        val write = requests.single { it.requestUrl?.encodedPath == "/v1/me/library" }
        assertEquals("DELETE", write.method)
        assertEquals("spotify:album:a2", write.requestUrl?.queryParameter("uris"))
    }

    @Test
    fun should_sendNoWrite_when_theRateLimitGateIsClosed() = runTest {
        val gate = SpotifyRateLimitGate().apply { recordBackoff(PROFILE, retryAfterSeconds = 60) }

        val error = source(gate).writeActions().setAlbumSaved(album("a1"), saved = true).exceptionOrNull()

        assertTrue(error is SpotifyRateLimitException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_askAboutTheAlbumFirstInTheSameRequest_when_theAlbumPageChecksItsTracks() = runTest {
        val states = source().writeActions()
            .favoriteStates(listOf(track("t1"), track("t2")), albums = listOf(MediaId.spotify("a2")))
            .getOrThrow()

        val contains = requests.filter { it.requestUrl?.encodedPath == "/v1/me/library/contains" }
        assertEquals(1, contains.size)
        assertEquals(
            "spotify:album:a2,spotify:track:t1,spotify:track:t2",
            contains.single().requestUrl?.queryParameter("uris")
        )
        assertEquals(
            mapOf(MediaId.spotify("a2") to true, MediaId.spotify("t1") to false, MediaId.spotify("t2") to true),
            states
        )
    }

    @Test
    fun should_leaveARemovedAlbumOut_when_spotifysListStillHasIt() = runTest {
        val source = source()
        assertEquals(listOf("a2"), savedAlbumIds(source))

        source.writeActions().setAlbumSaved(album("a2"), saved = false)
        assertTrue(source.hasUnsettledFavoriteWrites())

        // Spotify hasn't applied the removal yet when the list is read again.
        source.invalidateLibraryCaches()
        assertEquals(emptyList<String>(), savedAlbumIds(source))

        // Once its list agrees the write is settled.
        savedAlbums()
        source.invalidateLibraryCaches()
        assertEquals(emptyList<String>(), savedAlbumIds(source))
        assertFalse(source.hasUnsettledFavoriteWrites())
    }

    private suspend fun savedAlbumIds(source: SpotifyMusicSource): List<String> =
        source.library().getAlbumList(type = "newest", size = 50).map { it.id.rawId }

    private fun savedAlbums(vararg albumIds: String) {
        val items = albumIds.joinToString(",") { id ->
            """{"added_at":"2026-01-01T00:00:00Z","album":{"id":"$id","name":"Album $id"}}"""
        }
        routes["/v1/me/albums"] = { ok("""{"items":[$items],"next":null,"total":${albumIds.size}}""") }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun source(gate: SpotifyRateLimitGate? = null): SpotifyMusicSource {
        val httpClient = OkHttpClient()
        val baseUrl = server.url("/")
        return SpotifyMusicSource(
            // The source's client reads the real clock: a token that never expires.
            initialCredentials = spotifyTestCredentials("t1", "r1", expiresAtEpochMs = Long.MAX_VALUE / 2),
            clientIdProvider = { "test-client" },
            onCredentialsPersisted = {},
            httpClient = httpClient,
            authService = SpotifyAuthService(httpClient = httpClient, authBaseUrl = baseUrl, apiBaseUrl = baseUrl),
            profileId = PROFILE,
            rateLimitGate = gate,
            apiBaseUrl = baseUrl,
            clock = { now }
        )
    }

    private fun album(rawId: String) = Album(
        id = MediaId.spotify(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 2,
        durationSec = null,
        year = 2020,
        genre = null
    )

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private companion object {
        const val PROFILE = "spotify-profile"
    }
}
