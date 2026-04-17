package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Owns the in-memory mutable copy of a Spotify profile's
 * [ProfileCredentials.Spotify] and handles the Bearer auth + auto-refresh
 * behaviour. On refresh, fires [onCredentialsRefreshed] upward so the hosting
 * [SpotifyMusicSource] can persist the new tokens (silently, via
 * `ProfileManager.persistCredentialsSilently` — we never rebuild the active
 * source for a token rotation).
 *
 * Design notes:
 * - Proactive refresh: if the access token expires within [REFRESH_BUFFER_MS],
 *   refresh before firing the request.
 * - Reactive retry: on a 401, force-refresh once and re-issue. Second 401
 *   bubbles up.
 * - Refresh is serialised by [refreshMutex]; concurrent callers dog-pile a
 *   single `/api/token` call rather than five.
 * - All HTTP is moved to [Dispatchers.IO] so callers can stay on the main
 *   dispatcher without blocking.
 */
class SpotifyApiClient(
    private val httpClient: OkHttpClient,
    private val authService: SpotifyAuthService,
    initialCredentials: ProfileCredentials.Spotify,
    private val clientIdProvider: () -> String,
    private val onCredentialsRefreshed: suspend (ProfileCredentials.Spotify) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val apiBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.API_HOST}/".toHttpUrl(),
) {

    @Volatile
    private var credentials: ProfileCredentials.Spotify = initialCredentials
    private val refreshMutex = Mutex()

    fun currentCredentials(): ProfileCredentials.Spotify = credentials

    suspend fun getMe(): SpotifyMe = withContext(Dispatchers.IO) {
        val url = apiBaseUrl.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("me")
            .build()
        val response = executeWithAuthRetry { accessToken ->
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute()
        }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw SpotifyAuthException(
                    code = it.code,
                    message = "Spotify /me failed: ${it.code}",
                )
            }
            JSON.decodeFromString(SpotifyMe.serializer(), body)
        }
    }

    /**
     * Runs [block] with the current (possibly refreshed) access token. On a
     * 401 response, force-refreshes the credentials and retries once. The
     * second response is returned verbatim whether it's another 401 or not —
     * callers handle non-2xx shapes themselves.
     */
    private suspend fun executeWithAuthRetry(block: (accessToken: String) -> Response): Response {
        ensureFreshCredentials()
        val first = block(credentials.accessToken)
        if (first.code != HTTP_UNAUTHORIZED) return first
        first.close()
        forceRefresh()
        return block(credentials.accessToken)
    }

    private suspend fun ensureFreshCredentials() = coalescedRefresh(force = false)

    private suspend fun forceRefresh() = coalescedRefresh(force = true)

    /**
     * Serialises refresh calls through [refreshMutex] and coalesces dog-piled
     * callers by snapshotting the access token BEFORE waiting on the lock —
     * if another coroutine refreshed while we queued, their new token is
     * visible and this caller can skip.
     *
     * When [force] is false, also skip when the token is still comfortably
     * fresh (proactive-refresh path). When [force] is true (401 retry path),
     * we refresh unless someone else just did.
     */
    private suspend fun coalescedRefresh(force: Boolean) {
        val before = credentials
        if (!force && before.expiresAtEpochMs - now() > REFRESH_BUFFER_MS) return
        refreshMutex.withLock {
            val inside = credentials
            if (inside.accessToken != before.accessToken) return@withLock
            val clientId = clientIdProvider()
            if (clientId.isBlank()) {
                throw SpotifyAuthException(
                    code = 0,
                    message = "Spotify client id is not configured. Open Settings → Spotify.",
                )
            }
            val response = withContext(Dispatchers.IO) {
                authService.refreshToken(inside.refreshToken, clientId = clientId)
            }
            val refreshed = inside.copy(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: inside.refreshToken,
                expiresAtEpochMs = now() + response.expiresInSec * 1_000,
                scopes = response.scope?.split(' ')?.filter { it.isNotBlank() } ?: inside.scopes,
            )
            credentials = refreshed
            onCredentialsRefreshed(refreshed)
        }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        /** Start proactively refreshing when less than this much lifetime remains. */
        private const val REFRESH_BUFFER_MS = 60_000L
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
