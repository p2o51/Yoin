package com.gpo.yoin.data.source.spotify

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyPlaylistSnapshotConcurrencyTest {

    private lateinit var server: MockWebServer
    private val refreshCallbacks = mutableListOf<com.gpo.yoin.data.profile.ProfileCredentials.Spotify>()

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
    fun removeTracks_threads_snapshot_id_and_returns_new_snapshot() = runTest {
        val responses = mutableMapOf(
            "/v1/playlists/pl1/items" to ArrayDeque(
                listOf(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"snapshot_id":"snap-after-remove"}"""),
                ),
            ),
        )
        server.dispatcher = spotifyQueueDispatcher(responses)
        val client = newSpotifyTestClient(
            server = server,
            initialCredentials = spotifyTestCredentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = SPOTIFY_TEST_BASE_EPOCH + 10 * 60_000L,
            ),
            callbacks = refreshCallbacks,
            revokeCallbacks = {},
        )

        val snapshot = client.removeTracksFromPlaylist(
            id = "pl1",
            items = listOf(
                SpotifyRemoveTrackItem(uri = "spotify:track:aaa", positions = listOf(0, 3)),
            ),
            snapshotId = "snap-before",
        )

        assertEquals("snap-after-remove", snapshot)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/playlists/pl1/items", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"snapshot_id\":\"snap-before\""))
        assertTrue(body.contains("\"positions\":[0,3]"))
    }
}
