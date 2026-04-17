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
