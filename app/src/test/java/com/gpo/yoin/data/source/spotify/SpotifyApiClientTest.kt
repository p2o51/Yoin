package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    private var revokeCallbacks: Int = 0
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
    fun should_throw_rate_limit_exception_with_retry_after_on_429() = runTest {
        val gate = SpotifyRateLimitGate()
        val responses = mutableMapOf(
            "/v1/me" to ArrayDeque(
                listOf(
                    MockResponse()
                        .setResponseCode(429)
                        .addHeader("Retry-After", "12"),
                ),
            ),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 10 * 60_000L,
            ),
            rateLimitGate = gate,
            profileId = "profile-a",
        )

        val thrown = runCatching { client.getMe() }.exceptionOrNull()
        assertTrue(thrown is SpotifyRateLimitException)
        assertEquals(12L, (thrown as SpotifyRateLimitException).retryAfterSeconds)
        assertTrue(gate.isBlocked("profile-a"))
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
    fun should_removePlaylistLibraryUri_when_unfollowingPlaylist() = runTest {
        val responses = mutableMapOf(
            "/v1/me/library?uris=spotify%3Aplaylist%3Apl1" to ArrayDeque(
                listOf(MockResponse().setResponseCode(200)),
            ),
        )
        server.dispatcher = queueDispatcher(responses)
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        client.unfollowPlaylist("pl1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/me/library", req.requestUrl?.encodedPath)
        assertEquals("spotify:playlist:pl1", req.requestUrl?.queryParameter("uris"))
    }

    @Test
    fun should_useSupportedSearchLimit_when_searchingCatalogWithDefaultLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        client.search("Miles Davis")

        val request = server.takeRequest()
        assertEquals("/v1/search", request.requestUrl?.encodedPath)
        assertEquals("Miles Davis", request.requestUrl?.queryParameter("q"))
        assertEquals("track,album,artist,playlist", request.requestUrl?.queryParameter("type"))
        assertEquals("10", request.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun should_clampSearchLimit_when_callerExceedsSpotifyRange() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        client.search("Doxy", limitPerType = 50)
        client.search("Doxy", limitPerType = -1)

        assertEquals("10", server.takeRequest().requestUrl?.queryParameter("limit"))
        assertEquals("0", server.takeRequest().requestUrl?.queryParameter("limit"))
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

    @Test
    fun should_fire_revoked_callback_when_refresh_returns_invalid_grant() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(
                listOf(
                    MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"error":"invalid_grant","error_description":"Refresh token revoked"}"""),
                ),
            ),
            "/v1/me" to ArrayDeque(listOf(meResponse(id = "alice"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 30_000L, // inside refresh buffer
            ),
        )

        val thrown = runCatching { client.getMe() }.exceptionOrNull()
        assertTrue(thrown is SpotifyAuthException)
        assertTrue(
            "exception should flag a revoked refresh token",
            (thrown as SpotifyAuthException).isRefreshTokenRevoked,
        )
        assertEquals(1, revokeCallbacks)
        assertEquals(0, callbacks.size) // no successful refresh → no persist
    }

    @Test
    fun should_not_fire_revoked_callback_on_unrelated_token_failure() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(
                listOf(
                    MockResponse()
                        .setResponseCode(503)
                        .setBody("""{"error":"server_error","error_description":"upstream down"}"""),
                ),
            ),
            "/v1/me" to ArrayDeque(listOf(meResponse(id = "alice"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 30_000L,
            ),
        )

        val thrown = runCatching { client.getMe() }.exceptionOrNull()
        assertTrue(thrown is SpotifyAuthException)
        assertTrue(
            "server_error must not flag as revoked — UI would wrongly send user to OAuth",
            !(thrown as SpotifyAuthException).isRefreshTokenRevoked,
        )
        assertEquals(0, revokeCallbacks)
    }

    @Test
    fun successful_refresh_clears_revoked_flag_on_new_credentials() = runTest {
        val responses = mutableMapOf(
            "/api/token" to ArrayDeque(listOf(tokenResponse(accessToken = "t2", refreshToken = "r2"))),
            "/v1/me" to ArrayDeque(listOf(meResponse(id = "alice"))),
        )
        server.dispatcher = queueDispatcher(responses)

        val client = newClient(
            initialCredentials = credentials(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = fakeNow + 30_000L,
            ).copy(revoked = true), // simulate "was previously marked revoked"
        )

        client.getMe()
        assertEquals(1, callbacks.size)
        assertTrue(
            "refresh success must clear the revoked marker",
            !callbacks[0].revoked,
        )
        assertEquals(0, revokeCallbacks)
    }

    @Test
    fun should_limitConcurrency_when_manyPagesRequested() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val running = inFlight.incrementAndGet()
                peak.accumulateAndGet(running) { a, b -> maxOf(a, b) }
                Thread.sleep(150)
                inFlight.decrementAndGet()
                val offset = request.requestUrl?.queryParameter("offset")?.toInt() ?: 0
                return MockResponse().setResponseCode(200)
                    .setBody(playlistItemsPage(offset = offset, size = 50, total = 300))
            }
        }
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        val items = client.getPlaylistItems("pl1")

        assertEquals(300, items.size)
        // First page, then the other five side by side — never more than four at once.
        assertEquals(6, server.requestCount)
        assertTrue("peak ${peak.get()}", peak.get() in 2..4)
    }

    @Test
    fun should_preserveOrder_when_artistAlbumPagesFetchedConcurrently() = runTest {
        val offsets = CopyOnWriteArrayList<Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.requestUrl?.queryParameter("offset")?.toInt() ?: 0
                offsets += offset
                // Later pages answer first.
                Thread.sleep(((30 - offset) * 10L).coerceAtLeast(0L))
                val items = (offset until minOf(offset + 10, 35))
                    .joinToString(",") { """{"id":"a$it","name":"a$it"}""" }
                val next = server.url("/v1/artists/ar1/albums?offset=${offset + 10}&limit=10")
                    .takeIf { offset + 10 < 35 }
                return MockResponse().setResponseCode(200)
                    .setBody("""{"items":[$items],"next":${next?.let { "\"$it\"" }},"total":35}""")
            }
        }
        val client = newClient(credentials("t1", "r1", fakeNow + 10 * 60_000L))

        val albums = client.getArtistAlbums("ar1")

        assertEquals((0 until 35).map { "a$it" }, albums.map { it.id })
        assertEquals(listOf(0, 10, 20, 30), offsets.sorted())
    }

    @Test
    fun should_notSendQueuedRead_when_readAheadOfItDrew429() = runTest {
        val gate = SpotifyRateLimitGate()
        val served = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (served.getAndIncrement() == 0) {
                // Headers late enough for the other read to queue on the slot,
                // the body later still: the gate closes on the headers.
                MockResponse()
                    .setResponseCode(429)
                    .addHeader("Retry-After", "30")
                    .setBody("""{"error":{"status":429}}""")
                    .setHeadersDelay(200, TimeUnit.MILLISECONDS)
                    .setBodyDelay(500, TimeUnit.MILLISECONDS)
            } else {
                meResponse(id = "alice")
            }
        }
        val client = newClient(
            initialCredentials = credentials("t1", "r1", fakeNow + 10 * 60_000L),
            rateLimitGate = gate,
            profileId = "profile-a",
            readPermits = Semaphore(1)
        )

        val results = List(2) { async { runCatching { client.getMe() } } }.awaitAll()

        assertEquals(1, server.requestCount)
        assertTrue(results.all { it.exceptionOrNull() is SpotifyRateLimitException })
        assertTrue(gate.isBlocked("profile-a"))
    }

    @Test
    fun should_notWaitForReadSlot_when_startingPlayback() = runTest {
        val readArrived = CountDownLatch(1)
        val playArrived = CountDownLatch(1)
        val playArrivedDuringRead = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v1/me" -> {
                    readArrived.countDown()
                    // Holds the only read slot until playback starts, or gives up.
                    playArrivedDuringRead.set(playArrived.await(2, TimeUnit.SECONDS))
                    meResponse(id = "alice")
                }
                "/v1/me/player/play" -> {
                    playArrived.countDown()
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = newClient(
            initialCredentials = credentials("t1", "r1", fakeNow + 10 * 60_000L),
            readPermits = Semaphore(1)
        )

        val read = async { client.getMe() }
        withContext(Dispatchers.IO) { readArrived.await(2, TimeUnit.SECONDS) }
        client.startPlayback(contextUri = "spotify:album:a1")
        read.await()

        assertTrue(playArrivedDuringRead.get())
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun playlistItemsPage(offset: Int, size: Int, total: Int): String {
        val end = minOf(offset + size, total)
        val items = (offset until end).joinToString(",") { """{"track":{"id":"t$it","name":"T$it"}}""" }
        val next = server.url("/v1/playlists/pl1/items?offset=$end&limit=$size").takeIf { end < total }
        return """{"items":[$items],"next":${next?.let { "\"$it\"" }},"total":$total}"""
    }

    private fun newClient(
        initialCredentials: ProfileCredentials.Spotify,
        rateLimitGate: SpotifyRateLimitGate? = null,
        profileId: String? = null,
        readPermits: Semaphore = Semaphore(4),
    ): SpotifyApiClient {
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
            onCredentialsRevoked = { revokeCallbacks += 1 },
            now = { fakeNow },
            apiBaseUrl = baseUrl,
            rateLimitGate = rateLimitGate,
            rateLimitProfileId = profileId,
            readPermits = readPermits,
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
