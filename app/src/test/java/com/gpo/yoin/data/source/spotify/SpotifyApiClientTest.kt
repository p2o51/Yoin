package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyApiClientTest {

    private lateinit var server: MockWebServer
    private val callbacks = mutableListOf<ProfileCredentials.Spotify>()
    private var fakeNow: Long = BASE_EPOCH

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
    fun should_proactively_refresh_when_token_expiring_soon() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(listOf(tokenResponse(accessToken = "t2", refreshToken = "r2"))),
            "/v1/me" to ArrayDeque(listOf(meResponse(id = "alice"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 30_000L, // inside 60s buffer
            ),
        )

        val me = client.getMe()
        assertEquals("alice", me.id)
        // Sequence: refresh first, then /me with NEW token.
        val refreshReq = server.takeRequest()
        assertEquals("/api/token", refreshReq.path)
        val meReq = server.takeRequest()
        assertEquals("/v1/me", meReq.path)
        assertEquals("Bearer t2", meReq.getHeader("Authorization"))
        assertEquals(0, server.requestCount - 2) // exactly 2 total

        assertEquals(1, callbacks.size)
        assertEquals("t2", callbacks[0].accessToken)
        assertEquals("r2", callbacks[0].refreshToken)
    }

    @Test
    fun should_retry_once_on_401_after_force_refresh() = runTest {
        val responses = mutableMapOf(
            "/v1/me" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""),
                    meResponse(id = "bob"),
                ),
            ),
            "/api/token" to ArrayDeque(listOf(tokenResponse(accessToken = "new-token", refreshToken = "r2"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "stale",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 10 * 60_000L, // far in future — no proactive refresh
            ),
        )

        val me = client.getMe()
        assertEquals("bob", me.id)
        assertEquals(3, server.requestCount)

        val first = server.takeRequest()
        assertEquals("/v1/me", first.path)
        assertEquals("Bearer stale", first.getHeader("Authorization"))

        val refresh = server.takeRequest()
        assertEquals("/api/token", refresh.path)

        val retry = server.takeRequest()
        assertEquals("/v1/me", retry.path)
        assertEquals("Bearer new-token", retry.getHeader("Authorization"))

        assertEquals(1, callbacks.size)
    }

    @Test
    fun should_surface_second_401_without_retrying_again() = runTest {
        val responses = mutableMapOf(
            "/v1/me" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(401),
                    MockResponse().setResponseCode(401),
                ),
            ),
            "/api/token" to ArrayDeque(listOf(tokenResponse(accessToken = "new", refreshToken = "r2"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "stale",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 10 * 60_000L,
            ),
        )

        var thrown: Throwable? = null
        try {
            client.getMe()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is SpotifyAuthException)
        assertEquals(401, (thrown as SpotifyAuthException).code)
        // No further calls after the second 401.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun createPlaylist_posts_to_me_playlists_with_name_and_public() = runTest {
        val responses = mutableMapOf(
            "/v1/me/playlists" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id":"pl1","name":"Road Trip","owner":{"id":"alice"},"snapshot_id":"snap0"}""",
                    ),
                ),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        val playlist = client.createPlaylist(name = "Road Trip", public = false)
        assertEquals("pl1", playlist.id)
        assertEquals("snap0", playlist.snapshotId)

        // Endpoint is /me/playlists (2026+); no /me resolution roundtrip
        // required.
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/v1/me/playlists", create.path)
        val body = create.body.readUtf8()
        assertTrue("body should contain name", body.contains("\"name\":\"Road Trip\""))
        // encodeDefaults = false, but `public` has no Kotlin default → must be emitted
        assertTrue("body should emit public=false", body.contains("\"public\":false"))
    }

    @Test
    fun addTracksToPlaylist_posts_uris_and_returns_snapshot() = runTest {
        val responses = mutableMapOf(
            "/v1/playlists/pl1/items" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(200).setBody(
                        """{"snapshot_id":"snap-after-add"}""",
                    ),
                ),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        val snapshot = client.addTracksToPlaylist(
            id = "pl1",
            uris = listOf("spotify:track:aaa", "spotify:track:bbb"),
        )
        assertEquals("snap-after-add", snapshot)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/playlists/pl1/items", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"spotify:track:aaa\""))
        assertTrue(body.contains("\"spotify:track:bbb\""))
    }

    @Test
    fun removeTracksFromPlaylist_includes_snapshot_id_for_concurrency() = runTest {
        val responses = mutableMapOf(
            "/v1/playlists/pl1/items" to ArrayDeque(
                listOf(
                    MockResponse().setResponseCode(200).setBody(
                        """{"snapshot_id":"snap-after-remove"}""",
                    ),
                ),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        val snapshot = client.removeTracksFromPlaylist(
            id = "pl1",
            items = listOf(
                SpotifyRemoveTrackItem(uri = "spotify:track:aaa", positions = listOf(0, 3)),
            ),
            snapshotId = "snap-before",
        )
        assertEquals("snap-after-remove", snapshot)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/playlists/pl1/items", req.path)
        val body = req.body.readUtf8()
        assertTrue("body carries snapshot_id for optimistic concurrency",
            body.contains("\"snapshot_id\":\"snap-before\""))
        assertTrue(body.contains("\"positions\":[0,3]"))
    }

    @Test
    fun unfollowPlaylist_hits_followers_with_DELETE() = runTest {
        val responses = mutableMapOf(
            "/v1/playlists/pl1/followers" to ArrayDeque(
                listOf(MockResponse().setResponseCode(200)),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        client.unfollowPlaylist("pl1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/playlists/pl1/followers", req.path)
    }

    @Test
    fun renamePlaylist_puts_name_to_playlist_root() = runTest {
        val responses = mutableMapOf(
            "/v1/playlists/pl1" to ArrayDeque(
                listOf(MockResponse().setResponseCode(200).setBody("")),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        client.renamePlaylist(id = "pl1", name = "New Name", description = null)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/playlists/pl1", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"name\":\"New Name\""))
        // description is null + encodeDefaults=false → key should be absent.
        assertTrue("null description omitted", !body.contains("\"description\""))
    }

    @Test
    fun should_preserve_prior_refresh_token_when_refresh_response_omits_it() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(
                listOf(
                    // Spotify sometimes returns only a new access_token.
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {"access_token":"t2","token_type":"Bearer","expires_in":3600,"scope":"user-read-private"}
                        """.trimIndent(),
                    ),
                ),
            ),
            "/v1/me" to ArrayDeque(listOf(meResponse(id = "alice"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "keep-me",
                expiresAtEpochMs = fakeNow + 30_000L,
            ),
        )

        client.getMe()
        assertEquals(1, callbacks.size)
        assertEquals("t2", callbacks[0].accessToken)
        assertEquals("keep-me", callbacks[0].refreshToken)
        assertNotEquals(
            "expiresAtEpochMs should update to now + expires_in",
            fakeNow + 30_000L,
            callbacks[0].expiresAtEpochMs,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun newClient(initialCredentials: ProfileCredentials.Spotify): SpotifyApiClient {
        val httpClient = OkHttpClient.Builder().build()
        val baseUrl = server.url("/")
        val authService = SpotifyAuthService(
            httpClient = httpClient,
            authBaseUrl = baseUrl,
            apiBaseUrl = baseUrl,
        )
        return SpotifyApiClient(
            httpClient = httpClient,
            authService = authService,
            initialCredentials = initialCredentials,
            clientIdProvider = { "test-client" },
            onCredentialsRefreshed = { callbacks += it },
            now = { fakeNow },
            apiBaseUrl = baseUrl,
        )
    }

    private fun credentials(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
    ) = ProfileCredentials.Spotify(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMs = expiresAtEpochMs,
        scopes = listOf("user-read-private"),
    )

    private fun tokenResponse(accessToken: String, refreshToken: String?): MockResponse {
        val body = buildString {
            append("{")
            append("\"access_token\":\"$accessToken\",")
            append("\"token_type\":\"Bearer\",")
            append("\"expires_in\":3600,")
            if (refreshToken != null) append("\"refresh_token\":\"$refreshToken\",")
            append("\"scope\":\"user-read-private\"")
            append("}")
        }
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun meResponse(id: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody("""{"id":"$id","display_name":"Display $id"}""")

    private fun queueDispatcher(
        responses: MutableMap<String, ArrayDeque<MockResponse>>,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val queue = responses[request.path]
                ?: error("No mock queue for ${request.path}")
            return queue.removeFirstOrNull()
                ?: error("Queue for ${request.path} exhausted")
        }
    }

    companion object {
        private const val BASE_EPOCH: Long = 1_700_000_000_000L
    }
}
