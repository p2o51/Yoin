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
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val now: () -> Long = System::currentTimeMillis,
    private val apiBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.API_HOST}/".toHttpUrl(),
) {

    @Volatile
    private var credentials: ProfileCredentials.Spotify = initialCredentials
    private val refreshMutex = Mutex()

    fun currentCredentials(): ProfileCredentials.Spotify = credentials

    suspend fun getMe(): SpotifyMe = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "me"),
            deserializer = SpotifyMe.serializer(),
        )
    }

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
        private const val REFRESH_BUFFER_MS = 60_000L
        private const val PAGE_LIMIT = 50
        private const val DEFAULT_COLLECTION_LIMIT = 200
        private const val DEFAULT_TRACKS_LIMIT = 300
        private const val DEFAULT_SEARCH_LIMIT = 12
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
