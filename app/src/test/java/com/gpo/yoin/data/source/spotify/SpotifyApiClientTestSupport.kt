package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

internal const val SPOTIFY_TEST_BASE_EPOCH = 1_700_000_000_000L

internal fun spotifyTestCredentials(
    accessToken: String,
    refreshToken: String,
    expiresAtEpochMs: Long,
): ProfileCredentials.Spotify = ProfileCredentials.Spotify(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAtEpochMs = expiresAtEpochMs,
    scopes = listOf("user-read-private"),
)

internal fun newSpotifyTestClient(
    server: MockWebServer,
    initialCredentials: ProfileCredentials.Spotify,
    callbacks: MutableList<ProfileCredentials.Spotify>,
    revokeCallbacks: () -> Unit,
    now: () -> Long = { SPOTIFY_TEST_BASE_EPOCH },
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
        onCredentialsRevoked = revokeCallbacks,
        now = now,
        apiBaseUrl = baseUrl,
    )
}

internal fun spotifyTokenResponse(
    accessToken: String,
    refreshToken: String?,
): MockResponse {
    val refreshField = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
    return MockResponse()
        .setResponseCode(200)
        .setBody(
            """
            {"access_token":"$accessToken","token_type":"Bearer","expires_in":3600$refreshField,"scope":"user-read-private"}
            """.trimIndent(),
        )
}

internal fun spotifyQueueDispatcher(
    responses: MutableMap<String, ArrayDeque<MockResponse>>,
): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        return responses[path]?.removeFirstOrNull()
            ?: MockResponse().setResponseCode(404).setBody("""{"error":"unexpected path $path"}""")
    }
}
