package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Spotify Web API wrapper used by [SpotifyMusicSource].
 *
 * This layer owns:
 * - in-memory mutable credentials + refresh
 * - authenticated HTTP
 * - pagination helpers
 * - JSON decoding into Spotify DTOs
 *
 * Mapping into provider-agnostic models stays in `SpotifyMappers.kt`.
 */
class SpotifyApiClient(
    private val httpClient: OkHttpClient,
    private val authService: SpotifyAuthService,
    initialCredentials: ProfileCredentials.Spotify,
    private val clientIdProvider: () -> String,
    private val onCredentialsRefreshed: suspend (ProfileCredentials.Spotify) -> Unit,
    /**
     * Invoked exactly once when the OAuth token endpoint rejects our
     * refresh attempt with `error: "invalid_grant"` — i.e. the refresh
     * token is dead (user revoked access from the Spotify dashboard,
     * scope-bump invalidated it, etc). Wiring should mark the active
     * profile as needing reconnect so UI can surface a Reconnect
     * affordance instead of silently 401-ing on every API call.
     *
     * Default is no-op for tests / call sites that don't yet care.
     */
    private val onCredentialsRevoked: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val apiBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.API_HOST}/".toHttpUrl(),
) {

    @Volatile
    private var credentials: ProfileCredentials.Spotify = initialCredentials
    private val refreshMutex = Mutex()

    @Volatile
    private var cachedUserId: String? = null

    fun currentCredentials(): ProfileCredentials.Spotify = credentials

    suspend fun getMe(): SpotifyMe = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "me"),
            deserializer = SpotifyMe.serializer(),
        ).also { cachedUserId = it.id }
    }

    /**
     * Best-effort current-user id cache. First call hits `/v1/me` and memoises
     * the id; subsequent calls are free. Callers that need to refresh (e.g.
     * after an account switch) should recreate the client — the cache has
     * the same lifetime as the credentials.
     */
    suspend fun getCurrentUserId(): String = cachedUserId ?: getMe().id

    suspend fun getSavedTracks(limit: Int = DEFAULT_COLLECTION_LIMIT): List<SpotifySavedTrackObject> =
        collectOffsetPages(
            initialUrl = apiUrl("v1", "me", "tracks")
                .newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .build(),
            maxItems = limit,
            deserializer = SpotifyPagingObject.serializer(SpotifySavedTrackObject.serializer()),
            items = { page -> page.items },
            next = { page -> page.next },
        )

    suspend fun getSavedAlbums(limit: Int = DEFAULT_COLLECTION_LIMIT): List<SpotifySavedAlbumObject> =
        collectOffsetPages(
            initialUrl = apiUrl("v1", "me", "albums")
                .newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .build(),
            maxItems = limit,
            deserializer = SpotifyPagingObject.serializer(SpotifySavedAlbumObject.serializer()),
            items = { page -> page.items },
            next = { page -> page.next },
        )

    suspend fun getCurrentUserPlaylists(limit: Int = DEFAULT_COLLECTION_LIMIT): List<SpotifyPlaylistObject> =
        collectOffsetPages(
            initialUrl = apiUrl("v1", "me", "playlists")
                .newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .build(),
            maxItems = limit,
            deserializer = SpotifyPagingObject.serializer(SpotifyPlaylistObject.serializer()),
            items = { page -> page.items },
            next = { page -> page.next },
        )

    suspend fun getFollowedArtists(limit: Int = DEFAULT_COLLECTION_LIMIT): List<SpotifyArtistObject> =
        collectCursorPages(
            initialUrl = apiUrl("v1", "me", "following")
                .newBuilder()
                .addQueryParameter("type", "artist")
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .build(),
            maxItems = limit,
            deserializer = SpotifyFollowedArtistsResponse.serializer(),
            items = { response -> response.artists.items },
            next = { response -> response.artists.next },
        )

    suspend fun getAlbum(id: String): SpotifyAlbumObject = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "albums", id),
            deserializer = SpotifyAlbumObject.serializer(),
        )
    }

    suspend fun getAlbumTracks(
        id: String,
        limit: Int = DEFAULT_TRACKS_LIMIT,
    ): List<SpotifyTrackObject> = collectOffsetPages(
        initialUrl = apiUrl("v1", "albums", id, "tracks")
            .newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .build(),
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifyTrackObject.serializer()),
        items = { page -> page.items },
        next = { page -> page.next },
    )

    suspend fun getArtist(id: String): SpotifyArtistObject = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "artists", id),
            deserializer = SpotifyArtistObject.serializer(),
        )
    }

    suspend fun getArtistAlbums(
        id: String,
        limit: Int = DEFAULT_COLLECTION_LIMIT,
    ): List<SpotifySimplifiedAlbumObject> = collectOffsetPages(
        initialUrl = apiUrl("v1", "artists", id, "albums")
            .newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("include_groups", "album,single,compilation,appears_on")
            .build(),
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifySimplifiedAlbumObject.serializer()),
        items = { page -> page.items },
        next = { page -> page.next },
    )

    suspend fun getPlaylist(id: String): SpotifyPlaylistObject = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "playlists", id),
            deserializer = SpotifyPlaylistObject.serializer(),
        )
    }

    suspend fun getPlaylistItems(
        id: String,
        limit: Int = DEFAULT_TRACKS_LIMIT,
    ): List<SpotifyPlaylistItemObject> = collectOffsetPages(
        initialUrl = apiUrl("v1", "playlists", id, "items")
            .newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("additional_types", "track")
            .build(),
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifyPlaylistItemObject.serializer()),
        items = { page -> page.items },
        next = { page -> page.next },
    )

    suspend fun search(
        query: String,
        limitPerType: Int = DEFAULT_SEARCH_LIMIT,
    ): SpotifySearchResponse = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "search")
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type", "track,album,artist,playlist")
                .addQueryParameter("limit", limitPerType.toString())
                .build(),
            deserializer = SpotifySearchResponse.serializer(),
        )
    }

    suspend fun saveToLibrary(uri: String) {
        mutateLibrary(method = "PUT", uri = uri)
    }

    suspend fun removeFromLibrary(uri: String) {
        mutateLibrary(method = "DELETE", uri = uri)
    }

    // ── Playlist mutation ───────────────────────────────────────────────

    suspend fun createPlaylist(
        name: String,
        description: String? = null,
        public: Boolean = false,
    ): SpotifyPlaylistObject = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpotifyCreatePlaylistRequest.serializer(),
            SpotifyCreatePlaylistRequest(name = name, public = public, description = description),
        )
        // POST /v1/me/playlists (2026+) — the /users/{user_id}/playlists
        // form is deprecated. /me infers the owning user from the token.
        executeWithJsonBody(
            method = "POST",
            url = apiUrl("v1", "me", "playlists"),
            jsonBody = body,
            deserializer = SpotifyPlaylistObject.serializer(),
        )
    }

    suspend fun renamePlaylist(
        id: String,
        name: String,
        description: String? = null,
    ) = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpotifyRenamePlaylistRequest.serializer(),
            SpotifyRenamePlaylistRequest(name = name, description = description),
        )
        executeWithJsonBodyIgnoringResponse(
            method = "PUT",
            url = apiUrl("v1", "playlists", id),
            jsonBody = body,
        )
    }

    suspend fun addTracksToPlaylist(id: String, uris: List<String>): String = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpotifyAddTracksRequest.serializer(),
            SpotifyAddTracksRequest(uris = uris),
        )
        // POST /v1/playlists/{id}/items (2026+). The /tracks alias still
        // works as of this writing but is on track for deprecation.
        val response = executeWithJsonBody(
            method = "POST",
            url = apiUrl("v1", "playlists", id, "items"),
            jsonBody = body,
            deserializer = SpotifySnapshotResponse.serializer(),
        )
        response.snapshotId
    }

    suspend fun removeTracksFromPlaylist(
        id: String,
        items: List<SpotifyRemoveTrackItem>,
        snapshotId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpotifyRemoveTracksRequest.serializer(),
            SpotifyRemoveTracksRequest(tracks = items, snapshotId = snapshotId),
        )
        // DELETE /v1/playlists/{id}/items (2026+) — same deprecation path
        // as the add endpoint.
        val response = executeWithJsonBody(
            method = "DELETE",
            url = apiUrl("v1", "playlists", id, "items"),
            jsonBody = body,
            deserializer = SpotifySnapshotResponse.serializer(),
        )
        response.snapshotId
    }

    /**
     * "Delete" a playlist. Spotify has no true delete; unfollowing your own
     * playlist removes it from `/me/playlists`, which is the product
     * behaviour callers want.
     */
    suspend fun unfollowPlaylist(id: String) = withContext(Dispatchers.IO) {
        executeWithJsonBodyIgnoringResponse(
            method = "DELETE",
            url = apiUrl("v1", "playlists", id, "followers"),
            jsonBody = null,
        )
    }

    private suspend fun mutateLibrary(method: String, uri: String) = withContext(Dispatchers.IO) {
        val url = apiUrl("v1", "me", "library")
            .newBuilder()
            .addQueryParameter("uris", uri)
            .build()
        executeWithAuthRetry { accessToken ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .method(method, EMPTY_BODY)
                .build()
                .let(httpClient::newCall)
                .execute()
        }.use { response ->
            if (!response.isSuccessful) {
                throw SpotifyAuthException(
                    code = response.code,
                    message = "Spotify library mutation failed: ${response.code}",
                )
            }
        }
    }

    private suspend fun <T> executeWithJsonBody(
        method: String,
        url: HttpUrl,
        jsonBody: String?,
        deserializer: KSerializer<T>,
    ): T {
        val response = executeJsonRequest(method, url, jsonBody)
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw SpotifyAuthException(
                    code = it.code,
                    message = "Spotify mutation failed: ${it.code}",
                )
            }
            return JSON.decodeFromString(deserializer, body)
        }
    }

    private suspend fun executeWithJsonBodyIgnoringResponse(
        method: String,
        url: HttpUrl,
        jsonBody: String?,
    ) {
        executeJsonRequest(method, url, jsonBody).use { response ->
            if (!response.isSuccessful) {
                throw SpotifyAuthException(
                    code = response.code,
                    message = "Spotify mutation failed: ${response.code}",
                )
            }
        }
    }

    private suspend fun executeJsonRequest(
        method: String,
        url: HttpUrl,
        jsonBody: String?,
    ): Response {
        val body: RequestBody = jsonBody?.toRequestBody(JSON_MEDIA_TYPE) ?: EMPTY_BODY
        return executeWithAuthRetry { accessToken ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .method(method, body)
                .build()
                .let(httpClient::newCall)
                .execute()
        }
    }

    private suspend fun <T> collectOffsetPages(
        initialUrl: HttpUrl,
        maxItems: Int,
        deserializer: KSerializer<SpotifyPagingObject<T>>,
        items: (SpotifyPagingObject<T>) -> List<T>,
        next: (SpotifyPagingObject<T>) -> String?,
    ): List<T> = withContext(Dispatchers.IO) {
        val results = mutableListOf<T>()
        var nextUrl: HttpUrl? = initialUrl
        while (nextUrl != null && results.size < maxItems) {
            val page = getDecoded(nextUrl, deserializer)
            results += items(page)
            nextUrl = next(page)?.toHttpUrlOrNull()
        }
        results.take(maxItems)
    }

    private suspend fun <T> collectCursorPages(
        initialUrl: HttpUrl,
        maxItems: Int,
        deserializer: KSerializer<SpotifyFollowedArtistsResponse>,
        items: (SpotifyFollowedArtistsResponse) -> List<T>,
        next: (SpotifyFollowedArtistsResponse) -> String?,
    ): List<T> = withContext(Dispatchers.IO) {
        val results = mutableListOf<T>()
        var nextUrl: HttpUrl? = initialUrl
        while (nextUrl != null && results.size < maxItems) {
            val page = getDecoded(nextUrl, deserializer)
            results += items(page)
            nextUrl = next(page)?.toHttpUrlOrNull()
        }
        results.take(maxItems)
    }

    private suspend fun <T> getDecoded(
        url: HttpUrl,
        deserializer: KSerializer<T>,
    ): T {
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
                    message = "Spotify request failed: ${it.code}",
                )
            }
            return JSON.decodeFromString(deserializer, body)
        }
    }

    private fun apiUrl(vararg pathSegments: String): HttpUrl {
        val builder = apiBaseUrl.newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        return builder.build()
    }

    /**
     * Runs [block] with the current (possibly refreshed) access token. On a
     * 401 response, force-refreshes the credentials and retries once. The
     * second response is returned verbatim whether it's another 401 or not.
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
            val response = try {
                withContext(Dispatchers.IO) {
                    authService.refreshToken(inside.refreshToken, clientId = clientId)
                }
            } catch (e: SpotifyAuthException) {
                if (e.isRefreshTokenRevoked) {
                    // Refresh token is dead — user revoked from the dashboard,
                    // password reset invalidated tokens, or a previous app
                    // version was uninstalled then reinstalled with stale
                    // creds. Notify upstream so the profile gets marked,
                    // then re-throw so the originating call (which will most
                    // likely 401 next anyway) sees a typed failure.
                    runCatching { onCredentialsRevoked() }
                }
                throw e
            }
            val refreshed = inside.copy(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: inside.refreshToken,
                expiresAtEpochMs = now() + response.expiresInSec * 1_000,
                scopes = response.scope?.split(' ')?.filter { it.isNotBlank() } ?: inside.scopes,
                // Successful refresh implicitly clears any prior revoked
                // marker — the credentials are alive again.
                revoked = false,
            )
            credentials = refreshed
            onCredentialsRefreshed(refreshed)
        }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val REFRESH_BUFFER_MS = 60_000L
        private const val PAGE_LIMIT = 50
        private const val DEFAULT_COLLECTION_LIMIT = 200
        private const val DEFAULT_TRACKS_LIMIT = 300
        private const val DEFAULT_SEARCH_LIMIT = 12
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Default `encodeDefaults = false` drops fields whose value equals the
        // declared Kotlin default — this is deliberate for request bodies,
        // e.g. `description: String? = null` omits the key rather than sending
        // `"description": null`. Fields that must always be emitted (e.g.
        // `public` on create-playlist, where Kotlin's default `false` differs
        // from Spotify's server-side default `true`) must not declare a
        // Kotlin default in the DTO — require callers to pass them.
        //
        // `coerceInputValues = true`: Spotify occasionally ships `"images":
        // null` (and similar) on playlists that have no artwork yet. Without
        // coercion kotlinx.serialization throws because
        // `SpotifyPlaylistObject.images` is a non-nullable List with a default
        // `emptyList()`. Coercion turns `null` on such fields into the
        // declared default, avoiding scattered `images: List<..>? = null`
        // declarations and unwrap-everywhere call sites.
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
