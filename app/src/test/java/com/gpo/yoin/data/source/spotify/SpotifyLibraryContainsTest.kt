package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileCredentials
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `GET /v1/me/library/contains` (P4): the Liked Songs check for tracks the
 * 200-track saved list can't show. At most 40 URIs a request, one request
 * after another, answers in the order asked; a closed rate-limit gate sends
 * nothing and a 429 ends the read.
 */
class SpotifyLibraryContainsTest {

    private lateinit var server: MockWebServer
    private val asked = CopyOnWriteArrayList<List<String>>()
    private val rawQueries = CopyOnWriteArrayList<String>()

    /** Liked: every URI whose id ends in an even digit. */
    private var answer: (List<String>) -> MockResponse = { uris ->
        MockResponse().setResponseCode(200).setBody(uris.joinToString(",", "[", "]") { liked(it).toString() })
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (url.encodedPath != "/v1/me/library/contains") return MockResponse().setResponseCode(404)
                rawQueries += url.encodedQuery.orEmpty()
                val uris = url.queryParameter("uris").orEmpty().split(',')
                asked += uris
                return answer(uris)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_askFortyAtATimeInOrder_when_checkingEightyFiveUris() = runTest {
        val uris = (0 until 85).map { "spotify:track:id$it" }

        val saved = client().libraryContains(uris)

        assertEquals(listOf(40, 40, 5), asked.map { it.size })
        assertEquals(uris, asked.flatten())
        assertEquals(uris.map(::liked), saved)
    }

    @Test
    fun should_encodeColonsAndKeepCommas_when_buildingTheQuery() = runTest {
        client().libraryContains(listOf("spotify:track:a1", "spotify:track:b2"))

        assertEquals("uris=spotify%3Atrack%3Aa1,spotify%3Atrack%3Ab2", rawQueries.single())
    }

    @Test
    fun should_sendNoRequest_when_rateLimitGateIsClosed() = runTest {
        val gate = SpotifyRateLimitGate().apply { recordBackoff(PROFILE, retryAfterSeconds = 60) }

        val error = runCatching { client(gate).libraryContains(listOf("spotify:track:a1")) }.exceptionOrNull()

        assertTrue(error is SpotifyRateLimitException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_stopWithoutRetrying_when_aBatchIsRateLimited() = runTest {
        val gate = SpotifyRateLimitGate()
        var calls = 0
        val ok = answer
        answer = { uris ->
            calls++
            if (calls == 2) MockResponse().setResponseCode(429).addHeader("Retry-After", "30") else ok(uris)
        }

        val error = runCatching {
            client(gate).libraryContains((0 until 120).map { "spotify:track:id$it" })
        }.exceptionOrNull()

        assertTrue(error is SpotifyRateLimitException)
        // The first batch, the 429; never the third, never the second again.
        assertEquals(2, server.requestCount)
        assertTrue(gate.isBlocked(PROFILE))
    }

    @Test
    fun should_askOnlyAboutTracks_when_sourceChecksFavorites() = runTest {
        val httpClient = OkHttpClient()
        val baseUrl = server.url("/")
        val source = SpotifyMusicSource(
            // The source's client reads the real clock: a token that never expires.
            initialCredentials = spotifyTestCredentials("t1", "r1", expiresAtEpochMs = Long.MAX_VALUE / 2),
            clientIdProvider = { "test-client" },
            onCredentialsPersisted = {},
            httpClient = httpClient,
            authService = SpotifyAuthService(httpClient = httpClient, authBaseUrl = baseUrl, apiBaseUrl = baseUrl),
            profileId = PROFILE,
            apiBaseUrl = baseUrl,
            clock = { SPOTIFY_TEST_BASE_EPOCH }
        )
        val tracks = listOf(
            track("id2"),
            track("id3"),
            // Spotify playing a podcast episode or a local file: no track id to ask about.
            track("ep4", uriType = "episode"),
            track("215", uriType = "local"),
            track("spotify-remote-unknown"),
            track("id2")
        )

        val states = source.writeActions().favoriteStates(tracks).getOrThrow()

        assertEquals(listOf(listOf("spotify:track:id2", "spotify:track:id3")), asked)
        assertEquals(mapOf(MediaId.spotify("id2") to true, MediaId.spotify("id3") to false), states)
    }

    private fun liked(uri: String): Boolean = uri.last().digitToIntOrNull()?.let { it % 2 == 0 } ?: false

    private fun client(gate: SpotifyRateLimitGate? = null): SpotifyApiClient {
        val httpClient = OkHttpClient()
        val baseUrl = server.url("/")
        return SpotifyApiClient(
            httpClient = httpClient,
            authService = SpotifyAuthService(httpClient = httpClient, authBaseUrl = baseUrl, apiBaseUrl = baseUrl),
            initialCredentials = credentials(),
            clientIdProvider = { "test-client" },
            onCredentialsRefreshed = {},
            now = { SPOTIFY_TEST_BASE_EPOCH },
            apiBaseUrl = baseUrl,
            rateLimitGate = gate,
            rateLimitProfileId = PROFILE
        )
    }

    private fun credentials(): ProfileCredentials.Spotify = spotifyTestCredentials(
        accessToken = "t1",
        refreshToken = "r1",
        expiresAtEpochMs = SPOTIFY_TEST_BASE_EPOCH + 10 * 60_000L
    )

    private fun track(rawId: String, uriType: String? = null) = Track(
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
        userRating = null,
        extras = uriType?.let { mapOf(EXTRA_SPOTIFY_URI_TYPE to it) }.orEmpty()
    )

    private companion object {
        const val PROFILE = "spotify-profile"
    }
}
