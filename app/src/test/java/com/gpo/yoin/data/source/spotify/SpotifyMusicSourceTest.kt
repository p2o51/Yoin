package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.MediaId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Request shape of [SpotifyMusicSource] against a fake Web API: which
 * requests an album, artist or playlist open makes, and how the library
 * lists are shared and kept across likes.
 */
class SpotifyMusicSourceTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath.orEmpty()
                val offset = request.requestUrl?.queryParameter("offset")
                return (routes["$path?offset=$offset"] ?: routes[path])?.invoke()
                    ?: MockResponse().setResponseCode(404).setBody("""{"error":"unexpected $path"}""")
            }
        }
        server.start()
        savedTracks()
        routes["/v1/me/following"] = { ok("""{"artists":{"items":[],"next":null}}""") }
        routes["/v1/me"] = { ok("""{"id":"me"}""") }
        routes["/v1/albums/a1"] = { ok(album(id = "a1", trackIds = listOf("t1", "t2"))) }
        routes["/v1/albums/a2"] = { ok(album(id = "a2", trackIds = listOf("t3"))) }
        routes["/v1/artists/r1"] = { ok("""{"id":"r1","name":"Artist"}""") }
        routes["/v1/artists/r1/albums"] = {
            ok("""{"items":[{"id":"a1","name":"Album","release_date":"2020"}],"next":null,"total":1}""")
        }
        routes["/v1/me/albums"] = { ok("""{"items":[],"next":null,"total":0}""") }
        routes["/v1/me/library"] = { MockResponse().setResponseCode(200) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_notFetchSavedAlbums_when_openingAlbumOrArtist() = runTest {
        val source = newSource()

        source.library().getAlbum(MediaId.spotify("a1"))
        source.library().getArtist(MediaId.spotify("r1"))

        assertEquals(0, requestsTo("/v1/me/albums"))
    }

    @Test
    fun should_useEmbeddedTracks_when_albumTracksHaveNoNextPage() = runTest {
        val source = newSource()

        val album = source.library().getAlbum(MediaId.spotify("a1"))

        assertEquals(listOf("t1", "t2"), album?.tracks?.map { it.id.rawId })
        assertEquals(0, requestsTo("/v1/albums/a1/tracks"))
    }

    @Test
    fun should_fetchOnlyLaterPages_when_embeddedAlbumTracksHaveNextPage() = runTest {
        routes["/v1/albums/a1"] = {
            ok(
                album(
                    id = "a1",
                    trackIds = listOf("t1", "t2"),
                    next = server.url("/v1/albums/a1/tracks?offset=2&limit=2").toString(),
                    total = 5
                )
            )
        }
        routes["/v1/albums/a1/tracks?offset=2"] = { ok(trackPage(listOf("t3", "t4", "t5"))) }
        val source = newSource()

        val album = source.library().getAlbum(MediaId.spotify("a1"))

        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), album?.tracks?.map { it.id.rawId })
        val trackRequests = requests.filter { it.requestUrl?.encodedPath == "/v1/albums/a1/tracks" }
        assertEquals(1, trackRequests.size)
        assertEquals("50", trackRequests.single().requestUrl?.queryParameter("limit"))
    }

    @Test
    fun should_shareOneRequest_when_savedTracksRequestedConcurrently() = runTest {
        routes["/v1/me/tracks"] = {
            Thread.sleep(200)
            ok(savedTrackPage(listOf("t1")))
        }
        val source = newSource()

        val albums = listOf("a1", "a2", "a1")
            .map { id -> async { source.library().getAlbum(MediaId.spotify(id)) } }
            .awaitAll()

        assertEquals(1, requestsTo("/v1/me/tracks"))
        assertTrue(albums.first()!!.tracks.single { it.id.rawId == "t1" }.isStarred)
    }

    @Test
    fun should_keepFavoriteDelta_when_cacheNotRefreshedYet() = runTest {
        val source = newSource()
        assertFalse(starredOf(source, "t1"))

        assertTrue(source.writeActions().setFavorite(MediaId.spotify("t1"), favorite = true).isSuccess)

        assertTrue(starredOf(source, "t1"))
        // The like is laid over the cached list: /me/tracks is not read again.
        assertEquals(1, requestsTo("/v1/me/tracks"))
    }

    @Test
    fun should_keepFavoriteDelta_when_rereadLagsBehindWrite() = runTest {
        val source = newSource()
        source.writeActions().setFavorite(MediaId.spotify("t1"), favorite = true)

        // Spotify has not applied the like yet when the library is re-read.
        source.invalidateLibraryCaches()
        assertTrue(starredOf(source, "t1"))

        // Past the grace period a re-read that still disagrees wins.
        now += SavedTrackDelta.DEFAULT_GRACE_MS
        source.invalidateLibraryCaches()
        assertFalse(starredOf(source, "t1"))
    }

    @Test
    fun should_followRemote_when_rereadAfterWriteAgreed() = runTest {
        val source = newSource()
        source.writeActions().setFavorite(MediaId.spotify("t1"), favorite = true)
        savedTracks("t1")
        source.invalidateLibraryCaches()
        assertTrue(starredOf(source, "t1"))

        // Unliked in another app: the delta was settled by the read above, so
        // the next read is believed straight away.
        savedTracks()
        source.invalidateLibraryCaches()
        assertFalse(starredOf(source, "t1"))
    }

    @Test
    fun should_shareRecentlyPlayed_when_activitiesAndShelfLoadTogether() = runTest {
        routes["/v1/me/player/recently-played"] = {
            Thread.sleep(100)
            ok("""{"items":[{"played_at":"2026-10-01T00:00:00Z","track":${track("t1", album = "a1")}}]}""")
        }
        val source = newSource()

        val history = async { source.getRecentlyPlayed(limit = 50) }
        val shelf = async { source.library().getRecentlyPlayedAlbums(size = 20) }

        assertEquals(1, history.await().size)
        assertEquals(listOf("a1"), shelf.await().map { it.id.rawId })
        assertEquals(1, requestsTo("/v1/me/player/recently-played"))

        now += 31_000L
        source.getRecentlyPlayed(limit = 50)
        assertEquals(2, requestsTo("/v1/me/player/recently-played"))
    }

    @Test
    fun should_continueFromEmbeddedPage_when_playlistHasMoreEntries() = runTest {
        routes["/v1/playlists/p1"] = {
            ok(
                """
                {"id":"p1","name":"Mix","owner":{"id":"me"},
                 "items":{"items":[{"item":${track("x1")}},{"item":${track("x2")}}],
                          "next":"${server.url("/v1/playlists/p1/items?offset=2&limit=2")}","total":3}}
                """.trimIndent()
            )
        }
        // The deprecated `track` name still reads.
        routes["/v1/playlists/p1/items?offset=2"] = {
            ok("""{"items":[{"track":${track("x3")}}],"next":null,"total":3}""")
        }
        val source = newSource()

        val playlist = source.library().getPlaylist(MediaId.spotify("p1"))

        assertEquals(listOf("x1", "x2", "x3"), playlist?.tracks?.map { it.id.rawId })
        assertTrue(playlist?.canWrite == true)
        assertEquals(2, source.resolvePlaylistContextOffset(MediaId.spotify("p1"), visibleStartIndex = 2))
        val itemRequests = requests.filter { it.requestUrl?.encodedPath == "/v1/playlists/p1/items" }
        assertEquals(listOf("2"), itemRequests.map { it.requestUrl?.queryParameter("offset") })
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun newSource(): SpotifyMusicSource {
        val httpClient = OkHttpClient()
        val baseUrl = server.url("/")
        return SpotifyMusicSource(
            initialCredentials = spotifyTestCredentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = Long.MAX_VALUE / 2
            ),
            clientIdProvider = { "test-client" },
            onCredentialsPersisted = {},
            httpClient = httpClient,
            authService = SpotifyAuthService(httpClient = httpClient, authBaseUrl = baseUrl, apiBaseUrl = baseUrl),
            apiBaseUrl = baseUrl,
            clock = { now }
        )
    }

    private suspend fun starredOf(source: SpotifyMusicSource, trackId: String): Boolean =
        source.library().getAlbum(MediaId.spotify("a1"))!!.tracks.single { it.id.rawId == trackId }.isStarred

    private fun savedTracks(vararg trackIds: String) {
        routes["/v1/me/tracks"] = { ok(savedTrackPage(trackIds.toList())) }
    }

    private fun requestsTo(path: String): Int = requests.count { it.requestUrl?.encodedPath == path }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun track(id: String, album: String? = null): String {
        val albumJson = album?.let { ""","album":{"id":"$it","name":"Album $it"}""" }.orEmpty()
        return """{"id":"$id","name":"Song $id","artists":[{"id":"r1","name":"Artist"}]$albumJson}"""
    }

    private fun trackPage(trackIds: List<String>, next: String? = null, total: Int = trackIds.size): String =
        """{"items":[${trackIds.joinToString(",") { track(it) }}],"next":${next?.let { "\"$it\"" }},"total":$total}"""

    private fun savedTrackPage(trackIds: List<String>): String {
        val items = trackIds.joinToString(",") { """{"added_at":"2026-01-01T00:00:00Z","track":${track(it)}}""" }
        return """{"items":[$items],"next":null,"total":${trackIds.size}}"""
    }

    private fun album(id: String, trackIds: List<String>, next: String? = null, total: Int = trackIds.size): String =
        """{"id":"$id","name":"Album $id","artists":[{"id":"r1","name":"Artist"}],"total_tracks":$total,""" +
            """"tracks":${trackPage(trackIds, next, total)}}"""
}
