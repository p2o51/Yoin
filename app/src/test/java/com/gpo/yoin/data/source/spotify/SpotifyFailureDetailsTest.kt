package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyFailureDetailsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_replaceIdsAndDropQuery_when_templatingARequest() {
        val url = "https://api.spotify.com/v1/playlists/6ufG1wUMBYX8FQ0Kx82VoE/items?limit=50&offset=100".toHttpUrl()
        assertEquals("GET /v1/playlists/{id}/items", spotifyRequestTemplate("GET", url))
        assertEquals(
            "PUT /v1/me/library",
            spotifyRequestTemplate("PUT", "https://api.spotify.com/v1/me/library?uris=spotify:track:x".toHttpUrl()),
        )
        assertEquals(
            "GET /v1/users/{id}/playlists",
            spotifyRequestTemplate("GET", "https://api.spotify.com/v1/users/some.name/playlists".toHttpUrl()),
        )
    }

    @Test
    fun should_takeIdsOutOfSpotifysReason_when_itQuotesThem() {
        val body = """
            {"error":{"status":400,"message":"Non existing id: 'spotify:track:6ufG1wUMBYX8FQ0Kx82VoE' or 7ih4QcYC2XWAj4QSAAK9NU"}}
        """.trimIndent()
        assertEquals("Non existing id: 'spotify:{id}' or {id}", spotifyErrorReason(body))
    }

    @Test
    fun should_takeTheWholeLocalTrackUriOut_when_spotifyEchoesOne() {
        val body = """
            {"error":{"status":400,"message":"Unsupported uri spotify:local:Some+Artist:My+Album:Secret+Song:215, try again"}}
        """.trimIndent()
        assertEquals("Unsupported uri spotify:{id} try again", spotifyErrorReason(body))
    }

    @Test
    fun should_returnNull_when_theBodyNamesNoReason() {
        assertNull(spotifyErrorReason(null))
        assertNull(spotifyErrorReason("<html>Bad gateway</html>"))
        assertNull(spotifyErrorReason("""{"error":{"status":500}}"""))
    }

    @Test
    fun should_tellWhoseAppItIs_when_comparedWithTheBuiltInClientId() {
        assertEquals(SpotifyClientIdKind.BuiltIn, SpotifyClientIdKind.of("abc", builtInClientId = "abc"))
        assertEquals(SpotifyClientIdKind.Own, SpotifyClientIdKind.of("mine", builtInClientId = "abc"))
        assertEquals(SpotifyClientIdKind.Own, SpotifyClientIdKind.of("mine", builtInClientId = ""))
        assertEquals(SpotifyClientIdKind.None, SpotifyClientIdKind.of(" ", builtInClientId = "abc"))
    }

    @Test
    fun should_carryRequestReasonAndClientId_when_spotifyRefusesARead() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"status":403,"message":"Forbidden"}}""",
            ),
        )
        val error = runCatching { newClient(issuedUnder = "own-client").getPlaylist("6ufG1wUMBYX8FQ0Kx82VoE") }
            .exceptionOrNull()

        assertTrue(error is SpotifyAuthException)
        error as SpotifyAuthException
        assertEquals(403, error.code)
        assertEquals("GET /v1/playlists/{id}", error.request)
        assertEquals("Forbidden", error.reason)
        assertEquals(SpotifyClientIdKind.Own, error.clientIdKind)
    }

    @Test
    fun should_nameTheRequest_when_aResponseDoesNotDecode() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"pl","name":"Mine","images":[{"url":null,"width":null,"height":null}]}""",
            ),
        )
        val error = runCatching { newClient(issuedUnder = "built-in-client").getPlaylist("6ufG1wUMBYX8FQ0Kx82VoE") }
            .exceptionOrNull()

        assertTrue(error is SpotifyResponseDecodeException)
        error as SpotifyResponseDecodeException
        assertEquals("GET /v1/playlists/{id}", error.request)
        assertEquals(SpotifyClientIdKind.BuiltIn, error.clientIdKind)
        assertTrue(error.cause is SerializationException)
    }

    @Test
    fun should_notGuessWhoseAppItIs_when_theCredentialsPredateTheRecord() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"status":403,"message":"Forbidden"}}"""),
        )
        val error = runCatching { newClient(issuedUnder = null).getPlaylist("pl") }.exceptionOrNull()

        assertNull((error as SpotifyAuthException).clientIdKind)
    }

    @Test
    fun should_recordTheClientId_when_tokensAreRefreshed() = runTest {
        server.enqueue(spotifyTokenResponse(accessToken = "t2", refreshToken = null))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"alice"}"""))
        val refreshed = mutableListOf<ProfileCredentials.Spotify>()
        val client = newSpotifyTestClient(
            server = server,
            initialCredentials = spotifyTestCredentials("t1", "r1", expiresAtEpochMs = SPOTIFY_TEST_BASE_EPOCH),
            callbacks = refreshed,
            revokeCallbacks = {},
        )

        client.getMe()

        assertEquals("test-client", refreshed.single().clientId)
        assertEquals("test-client", client.currentCredentials().clientId)
    }

    private fun newClient(issuedUnder: String?): SpotifyApiClient = newSpotifyTestClient(
        server = server,
        initialCredentials = spotifyTestCredentials(
            accessToken = "t1",
            refreshToken = "r1",
            expiresAtEpochMs = SPOTIFY_TEST_BASE_EPOCH + 3_600_000L,
        ).copy(clientId = issuedUnder),
        callbacks = mutableListOf(),
        revokeCallbacks = {},
        builtInClientId = "built-in-client",
    )
}
