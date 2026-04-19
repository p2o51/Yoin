package com.gpo.yoin.data.source.spotify

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyInvalidGrantHandlingTest {

    private lateinit var server: MockWebServer
    private val refreshCallbacks = mutableListOf<com.gpo.yoin.data.profile.ProfileCredentials.Spotify>()
    private var revokeCallbacks: Int = 0

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
    fun invalidGrant_refresh_marks_profile_for_reconnect() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(
                listOf(
                    MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"error":"invalid_grant","error_description":"Refresh token revoked"}"""),
                ),
            ),
            "/v1/me" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(200).setBody("""{"id":"alice"}"""),
                ),
            ),
        )
        server.dispatcher = spotifyQueueDispatcher(responses)
        val client = newSpotifyTestClient(
            server = server,
            initialCredentials = spotifyTestCredentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = SPOTIFY_TEST_BASE_EPOCH + 30_000L,
            ),
            callbacks = refreshCallbacks,
            revokeCallbacks = { revokeCallbacks += 1 },
        )

        val thrown = runCatching { client.getMe() }.exceptionOrNull()
        assertTrue(thrown is SpotifyAuthException)
        assertTrue((thrown as SpotifyAuthException).isRefreshTokenRevoked)
        assertEquals(1, revokeCallbacks)
        assertTrue(refreshCallbacks.isEmpty())
    }
}
