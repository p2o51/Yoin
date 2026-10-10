package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.profile.ProfileCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val rateLimitGate: SpotifyRateLimitGate? = null,
    private val rateLimitProfileId: String? = null,
    /**
     * Slots for reads (library lists, detail pages, search). Calls go out
     * through the blocking `execute()`, which OkHttp's Dispatcher (5 per
     * host) does not limit, and pages are now fetched concurrently — this is
     * the only cap. One set serves the whole process: Spotify counts requests
     * per app, and a profile edit rebuilds the source while the old client's
     * reads may still be out. Playback and library writes take no slot
     * ([Lane.Control]).
     */
    private val readPermits: Semaphore = SHARED_READ_PERMITS,
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

    /**
     * The album's tracks. Pass the page `GET /albums/{id}` embeds as
     * [firstPage]: when it has no `next` (most albums) this makes no request
     * at all, otherwise only the pages after it are fetched.
     */
    suspend fun getAlbumTracks(
        id: String,
        firstPage: SpotifyPagingObject<SpotifyTrackObject>? = null,
        limit: Int = DEFAULT_TRACKS_LIMIT,
    ): List<SpotifyTrackObject> = collectOffsetPagesConcurrently(
        firstPage = firstPage,
        pageUrl = { offset ->
            apiUrl("v1", "albums", id, "tracks")
                .newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .addOffset(offset)
                .build()
        },
        pageSize = PAGE_LIMIT,
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifyTrackObject.serializer()),
    )

    suspend fun getArtist(id: String): SpotifyArtistObject = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "artists", id),
            deserializer = SpotifyArtistObject.serializer(),
        )
    }

    // (GET /artists/{id}/top-tracks was removed for Development Mode apps in
    // the February 2026 Web API migration — there is no replacement.)

    /**
     * The artist's own releases. Since the February 2026 migration this
     * endpoint pages at most 10 items per request (a larger `limit` is
     * rejected), so the total is capped to keep an artist visit to a handful
     * of requests. The first page's `total` lets the rest go out together
     * rather than one after another. `appears_on` is left out on purpose:
     * other artists' records inflated the count and aren't this artist's
     * discography.
     */
    suspend fun getArtistAlbums(
        id: String,
        limit: Int = ARTIST_ALBUMS_LIMIT,
    ): List<SpotifySimplifiedAlbumObject> = collectOffsetPagesConcurrently(
        firstPage = null,
        pageUrl = { offset ->
            apiUrl("v1", "artists", id, "albums")
                .newBuilder()
                .addQueryParameter("limit", ARTIST_ALBUMS_PAGE_LIMIT.toString())
                .addQueryParameter("include_groups", "album,single,compilation")
                .addOffset(offset)
                .build()
        },
        pageSize = ARTIST_ALBUMS_PAGE_LIMIT,
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifySimplifiedAlbumObject.serializer()),
    )

    suspend fun getPlaylist(id: String): SpotifyPlaylistObject = withContext(Dispatchers.IO) {
        getDecoded(
            // Same entry types as getPlaylistItems, so the embedded first page matches its pages.
            url = apiUrl("v1", "playlists", id)
                .newBuilder()
                .addQueryParameter("additional_types", "track")
                .build(),
            deserializer = SpotifyPlaylistObject.serializer(),
        )
    }

    /**
     * The playlist's entries in playlist order (callers map positions from
     * the list index). Pass the page `GET /playlists/{id}` embeds as
     * [firstPage] so only the pages after it are fetched.
     */
    suspend fun getPlaylistItems(
        id: String,
        firstPage: SpotifyPagingObject<SpotifyPlaylistItemObject>? = null,
        limit: Int = DEFAULT_TRACKS_LIMIT,
    ): List<SpotifyPlaylistItemObject> = collectOffsetPagesConcurrently(
        firstPage = firstPage,
        pageUrl = { offset ->
            apiUrl("v1", "playlists", id, "items")
                .newBuilder()
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .addQueryParameter("additional_types", "track")
                .addOffset(offset)
                .build()
        },
        pageSize = PAGE_LIMIT,
        maxItems = limit,
        deserializer = SpotifyPagingObject.serializer(SpotifyPlaylistItemObject.serializer()),
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
                .addQueryParameter("limit", limitPerType.coerceIn(0, MAX_SEARCH_LIMIT).toString())
                .build(),
            deserializer = SpotifySearchResponse.serializer(),
        )
    }

    // The first step of starting playback, so it doesn't queue behind reads.
    suspend fun listDevices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "me", "player", "devices"),
            deserializer = SpotifyDevicesResponse.serializer(),
            lane = Lane.Control,
        ).devices
    }

    /**
     * Most recently played tracks (newest first), with the "playing from"
     * context where Spotify supplies it. Single cursor page — 50 plays is far
     * more than the home feed shows, so we don't follow the `next` cursor.
     *
     * Requires the `user-read-recently-played` scope; profiles authorised
     * before that scope was added will 403 here (callers fall back to the
     * locally recorded activity feed until the user reconnects).
     */
    suspend fun getRecentlyPlayed(
        limit: Int = RECENTLY_PLAYED_LIMIT,
    ): List<SpotifyPlayHistoryObject> = withContext(Dispatchers.IO) {
        getDecoded(
            url = apiUrl("v1", "me", "player", "recently-played")
                .newBuilder()
                .addQueryParameter("limit", limit.coerceIn(1, RECENTLY_PLAYED_LIMIT).toString())
                .build(),
            deserializer = SpotifyCursorPagingObject.serializer(SpotifyPlayHistoryObject.serializer()),
        ).items
    }

    suspend fun transferPlayback(deviceId: String, play: Boolean = true) = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpotifyTransferPlaybackRequest.serializer(),
            SpotifyTransferPlaybackRequest(
                deviceIds = listOf(deviceId),
                play = play,
            ),
        )
        executeWithJsonBodyIgnoringResponse(
            method = "PUT",
            url = apiUrl("v1", "me", "player"),
            jsonBody = body,
        )
    }

    suspend fun saveToLibrary(uri: String) {
        mutateLibrary(method = "PUT", uri = uri)
    }

    suspend fun removeFromLibrary(uri: String) {
        mutateLibrary(method = "DELETE", uri = uri)
    }

    /**
     * Follow (PUT) / unfollow (DELETE) an artist. `PUT/DELETE /me/following`
     * was removed in the February 2026 migration; following is now a library
     * write with the artist's URI (`/v1/me/library?uris=spotify:artist:…`).
     */
    suspend fun setArtistFollowed(artistId: String, followed: Boolean) {
        mutateLibrary(method = if (followed) "PUT" else "DELETE", uri = "spotify:artist:$artistId")
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
    suspend fun unfollowPlaylist(id: String) {
        // The legacy /playlists/{id}/followers endpoint was removed for
        // Development Mode apps in the February 2026 migration.
        removeFromLibrary("spotify:playlist:$id")
    }

    /**
     * Start context-aware playback on the user's current active Spotify
     * device — the Web API counterpart to App Remote's `PlayerApi.play(uri)`.
     * Unlike App Remote (which only accepts a bare URI and always starts at
     * the first track), this preserves the *context* (album / playlist /
     * artist radio) with a specific starting offset, so Spotify's own UI
     * shows "playing from <context>" and its recommendation engine gets the
     * right signal.
     *
     * @param contextUri spotify URI of the context: `spotify:album:...`,
     *   `spotify:playlist:...`, or `spotify:artist:...`.
     * @param offsetPosition zero-based index into the context; null means
     *   start from the first item.
     * @param deviceId optional; when null Spotify uses the current active
     *   device. Callers should ensure App Remote is connected first (that
     *   makes the Spotify app the active device).
     *
     * Throws [SpotifyAuthException] on 4xx/5xx. The typical failure mode
     * worth recovering from is 404 NO_ACTIVE_DEVICE — caller should fall
     * back to the App Remote `play(uri) + queue(uri)` path.
     */
    suspend fun startPlayback(
        contextUri: String? = null,
        uris: List<String>? = null,
        offsetPosition: Int? = null,
        offsetUri: String? = null,
        deviceId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val url = apiUrl("v1", "me", "player", "play")
            .newBuilder()
            .apply { if (deviceId != null) addQueryParameter("device_id", deviceId) }
            .build()
        executeWithJsonBodyIgnoringResponse(
            method = "PUT",
            url = url,
            jsonBody = startPlaybackBody(contextUri, uris, offsetPosition, offsetUri),
        )
    }

    private suspend fun mutateLibrary(method: String, uri: String) = withContext(Dispatchers.IO) {
        val url = apiUrl("v1", "me", "library")
            .newBuilder()
            .addQueryParameter("uris", uri)
            .build()
        ensureNotRateLimited(url.toString())
        executeWithAuthRetry(url.toString(), Lane.Control) { accessToken ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .method(method, EMPTY_BODY)
                .build()
                .let(httpClient::newCall)
                .execute()
        }.use { response ->
            if (!response.isSuccessful) {
                throw response.toSpotifyFailure(url.toString())
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
                throw it.toSpotifyFailure(url.toString())
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
                throw response.toSpotifyFailure(url.toString())
            }
        }
    }

    // Playback control and playlist edits: all answer a tap (Lane.Control).
    private suspend fun executeJsonRequest(
        method: String,
        url: HttpUrl,
        jsonBody: String?,
    ): Response {
        ensureNotRateLimited(url.toString())
        val body: RequestBody = jsonBody?.toRequestBody(JSON_MEDIA_TYPE) ?: EMPTY_BODY
        return executeWithAuthRetry(url.toString(), Lane.Control) { accessToken ->
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

    /**
     * Every item of an offset-paged list, up to [maxItems], in list order.
     * The first page is [firstPage] when the caller already has it (Spotify
     * embeds one in album and playlist objects), else it is fetched. A bare
     * `{href, total}` without `limit` (what a playlist the user doesn't own
     * carries) is not a page and is fetched too. The first page's `total`
     * names every remaining page, so those are requested together by offset
     * instead of one `next` link at a time; each request still waits for a
     * [readPermits] slot. Without a `total` the `next` links are followed
     * in turn.
     */
    private suspend fun <T> collectOffsetPagesConcurrently(
        firstPage: SpotifyPagingObject<T>?,
        pageUrl: (offset: Int) -> HttpUrl,
        pageSize: Int,
        maxItems: Int,
        deserializer: KSerializer<SpotifyPagingObject<T>>
    ): List<T> = withContext(Dispatchers.IO) {
        val first = firstPage?.takeIf { page -> page.limit != null || page.items.isNotEmpty() }
            ?: getDecoded(pageUrl(0), deserializer)
        val next = first.next?.toHttpUrlOrNull()
        if (next == null || first.items.size >= maxItems) return@withContext first.items.take(maxItems)
        val total = first.total
            ?: return@withContext first.items + collectOffsetPages(
                initialUrl = next,
                maxItems = maxItems - first.items.size,
                deserializer = deserializer,
                items = { page -> page.items },
                next = { page -> page.next }
            )
        // Continue where `next` points (the embedded page may be sized
        // differently from [pageSize]), so positions stay contiguous.
        val start = next.queryParameter("offset")?.toIntOrNull() ?: first.items.size
        val rest = coroutineScope {
            (start until minOf(total, maxItems) step pageSize)
                .map { offset -> async { getDecoded(pageUrl(offset), deserializer).items } }
                .awaitAll()
        }
        (first.items + rest.flatten()).take(maxItems)
    }

    private fun HttpUrl.Builder.addOffset(offset: Int): HttpUrl.Builder =
        if (offset > 0) addQueryParameter("offset", offset.toString()) else this

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

    private suspend fun <T> getDecoded(url: HttpUrl, deserializer: KSerializer<T>, lane: Lane = Lane.Read): T {
        ensureNotRateLimited(url.toString())
        val response = executeWithAuthRetry(url.toString(), lane) { accessToken ->
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute()
        }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw it.toSpotifyFailure(url.toString())
            }
            return JSON.decodeFromString(deserializer, body)
        }
    }

    private fun Response.toSpotifyFailure(endpoint: String): Throwable {
        if (code == HTTP_TOO_MANY_REQUESTS) {
            // The gate was already closed when the 429 came back (executeGated).
            return SpotifyRateLimitException(
                retryAfterSeconds = retryAfterSeconds(),
                endpoint = endpoint,
            )
        }
        return SpotifyAuthException(
            code = code,
            message = "Spotify request failed: $code",
        )
    }

    private fun Response.retryAfterSeconds(): Long = header(HEADER_RETRY_AFTER)
        ?.toLongOrNull()
        ?.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        ?: DEFAULT_RETRY_AFTER_SECONDS

    private fun ensureNotRateLimited(endpoint: String) {
        val profileId = rateLimitProfileId ?: return
        val gate = rateLimitGate ?: return
        if (!gate.isBlocked(profileId)) return
        throw SpotifyRateLimitException(
            retryAfterSeconds = (gate.backoffRemainingMs(profileId) / 1_000L).coerceAtLeast(1L),
            endpoint = endpoint,
        )
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
     *
     * Each [Lane.Read] call holds one [readPermits] slot while it executes —
     * the call alone, never a token refresh or a multi-page read, so nothing
     * waits for a slot while holding one.
     */
    private suspend fun executeWithAuthRetry(
        endpoint: String,
        lane: Lane,
        block: (accessToken: String) -> Response
    ): Response {
        ensureFreshCredentials()
        val first = executeWithPermit(endpoint, lane) { block(credentials.accessToken) }
        if (first.code != HTTP_UNAUTHORIZED) return first
        first.close()
        forceRefresh()
        return executeWithPermit(endpoint, lane) { block(credentials.accessToken) }
    }

    private suspend fun executeWithPermit(endpoint: String, lane: Lane, call: () -> Response): Response = when (lane) {
        Lane.Read -> readPermits.withPermit { executeGated(endpoint, call) }
        Lane.Control -> executeGated(endpoint, call)
    }

    /**
     * Checks the rate-limit gate right before the call, and closes it as soon
     * as a 429's headers are in — before a read slot is given back, so no
     * call queued behind this one goes out after Spotify said stop.
     */
    private fun executeGated(endpoint: String, call: () -> Response): Response {
        ensureNotRateLimited(endpoint)
        return call().also { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                rateLimitProfileId?.let { profileId ->
                    rateLimitGate?.recordBackoff(profileId, response.retryAfterSeconds())
                }
            }
        }
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

    /**
     * Whether a call waits for a [readPermits] slot. [Control] calls answer a
     * tap — finding this phone's device, starting playback, a like, a follow,
     * a playlist edit. They went out at once before reads were capped, and
     * queued behind a detail prefetch or a library sync they would start
     * playback late; they still respect the rate-limit gate.
     */
    private enum class Lane { Read, Control }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HEADER_RETRY_AFTER = "Retry-After"
        private const val DEFAULT_RETRY_AFTER_SECONDS = 5L
        private const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
        private const val REFRESH_BUFFER_MS = 60_000L
        private const val MAX_CONCURRENT_READS = 4
        private const val PAGE_LIMIT = 50
        private const val ARTIST_ALBUMS_PAGE_LIMIT = 10
        private const val ARTIST_ALBUMS_LIMIT = 60
        private const val RECENTLY_PLAYED_LIMIT = 50
        private const val DEFAULT_COLLECTION_LIMIT = 200
        private const val DEFAULT_TRACKS_LIMIT = 300
        private const val MAX_SEARCH_LIMIT = 10
        private const val DEFAULT_SEARCH_LIMIT = MAX_SEARCH_LIMIT
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** The read slots every client shares (see the `readPermits` parameter). */
        private val SHARED_READ_PERMITS = Semaphore(MAX_CONCURRENT_READS)

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

/**
 * Body of `PUT /me/player/play`. Either a [contextUri] (album / playlist /
 * the user's Liked Songs collection) or a plain [uris] list (Spotify plays
 * it as a temporary context — nothing is added to the user's queue). The
 * start track is named by [offsetUri] when known: a position can drift from
 * Yoin's list (relinked or unavailable tracks, a shuffled list), a URI
 * cannot. [offsetPosition] is used only when there is no URI.
 *
 * Hand-rolled JSON keeps us off a dedicated serializer — the shape is tiny
 * and every value is a Spotify URI (base62) or an int.
 */
internal fun startPlaybackBody(
    contextUri: String?,
    uris: List<String>?,
    offsetPosition: Int?,
    offsetUri: String?,
): String {
    require((contextUri == null) != (uris == null)) { "Pass exactly one of contextUri / uris" }
    return buildString {
        append('{')
        if (contextUri != null) {
            append("\"context_uri\":\"").append(contextUri).append('"')
        } else {
            append("\"uris\":[")
            uris.orEmpty().forEachIndexed { i, uri ->
                if (i > 0) append(',')
                append('"').append(uri).append('"')
            }
            append(']')
        }
        when {
            offsetUri != null -> append(",\"offset\":{\"uri\":\"").append(offsetUri).append("\"}")
            offsetPosition != null -> append(",\"offset\":{\"position\":").append(offsetPosition).append('}')
        }
        append('}')
    }
}
