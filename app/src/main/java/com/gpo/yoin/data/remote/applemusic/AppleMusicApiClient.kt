package com.gpo.yoin.data.remote.applemusic

import android.util.Log
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.perf.YoinPerfHttp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Tokens are supplied per profile. This client neither signs JWTs nor logs credentials. */
class AppleMusicApiClient(
    private val developerToken: suspend () -> String,
    private val musicUserToken: () -> String?,
    // Dedicated transport: never inherit another provider's logging/auth interceptors.
    private val transport: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = "https://api.music.apple.com/".toHttpUrl()
) {
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        // Reads are enqueued (cancellable): keep the parallelism blocking calls on Dispatchers.IO had, not the
        // dispatcher's 5 per host. An artist start keeps three album opens of two parallel reads in flight.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = maxRequests })
        .apply { YoinPerfHttp.interceptor()?.let(::addInterceptor) } // debug-only timing; never the query
        .build()

    init {
        require(baseUrl.isHttps || baseUrl.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    suspend fun storefront(): String = storefrontDetails().id

    /** The account's storefront with the languages it serves names in (Japan: `ja` by default, also `en-US`). */
    suspend fun storefrontDetails(): AppleMusicStorefront {
        val response = get(url("v1", "me", "storefront"), personal = true)
        val data = response["data"]?.jsonArray?.firstOrNull()?.jsonObject
        val id = data?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IOException("Apple Music returned no storefront")
        val attributes = data["attributes"] as? JsonObject
        return AppleMusicStorefront(
            id = id,
            defaultLanguage = (attributes?.get("defaultLanguageTag") as? JsonPrimitive)?.contentOrNull,
            languages = (attributes?.get("supportedLanguageTags") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    suspend fun searchSongs(storefront: String, term: String): List<AppleMusicSong> {
        require(storefront.matches(Regex("[a-z]{2}")))
        if (term.isBlank()) return emptyList()
        val response = get(
            url("v1", "catalog", storefront, "search").newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("types", "songs")
                .addQueryParameter("limit", "25").build(),
            personal = false
        )
        val page = response["results"]?.jsonObject?.get("songs")?.jsonObject
            ?: return emptyList()
        return songs(page)
    }

    /** A single page preserves Apple's next link; absence from a page is not library absence. */
    suspend fun librarySongs(next: String? = null): AppleMusicSongPage {
        val target = if (next == null) {
            url("v1", "me", "library", "songs").newBuilder()
                .addQueryParameter("include", "catalog").build()
        } else {
            baseUrl.resolve(next)?.also { candidate ->
                require(
                    candidate.scheme == baseUrl.scheme && candidate.host == baseUrl.host &&
                        candidate.port == baseUrl.port && candidate.username.isEmpty() &&
                        candidate.password.isEmpty() && candidate.encodedPath == "/v1/me/library/songs"
                ) {
                    "Invalid Apple Music pagination link"
                }
            } ?: throw IllegalArgumentException("Invalid Apple Music pagination link")
        }
        val page = get(target, personal = true)
        return AppleMusicSongPage(songs(page), page["next"]?.jsonPrimitive?.content)
    }

    /** 202 only means accepted. The caller must confirm membership before showing a check. */
    suspend fun addSongToLibrary(catalogId: String): AppleMusicLibraryMutation {
        require(catalogId.matches(Regex("[0-9]+"))) { "A catalog song ID is required" }
        request(
            url("v1", "me", "library").newBuilder()
                .addQueryParameter("ids[songs]", catalogId).build(),
            personal = true,
            post = true,
            expectedStatus = 202
        )
        return AppleMusicLibraryMutation.AcceptedPendingConfirmation
    }

    /**
     * Catalog songs for each of [isrcs] (`GET /v1/catalog/{storefront}/songs?filter[isrc]=…`), at most
     * [ISRC_BATCH] codes per request. One ISRC can name several songs (an album's and a compilation's), all
     * kept, keyed by the song's own `isrc`. Codes the storefront doesn't know are absent from the map.
     */
    suspend fun catalogSongsByIsrc(storefront: String, isrcs: List<String>): Map<String, List<AppleMusicSong>> {
        require(storefront.matches(Regex("[a-z]{2}")))
        val codes = isrcs.map { it.trim().uppercase() }.filter { it.matches(ISRC_PATTERN) }.distinct()
        val found = linkedMapOf<String, MutableList<AppleMusicSong>>()
        for (batch in codes.chunked(ISRC_BATCH)) {
            val response = get(
                url("v1", "catalog", storefront, "songs").newBuilder()
                    .addQueryParameter("filter[isrc]", batch.joinToString(",")).build(),
                personal = false
            )
            for (song in songs(response)) {
                val isrc = song.isrc ?: continue
                if (isrc in batch) found.getOrPut(isrc) { mutableListOf() } += song
            }
        }
        return found
    }

    /**
     * A new playlist in the user's library (`POST /v1/me/library/playlists`, 201) holding [tracks] in order,
     * sent as the create's `tracks` relationship. Returns the new library playlist's resource.
     */
    suspend fun createLibraryPlaylist(
        name: String,
        description: String?,
        tracks: List<AppleMusicTrackRef>
    ): JsonObject {
        require(name.isNotBlank())
        val body = buildJsonObject {
            putJsonObject("attributes") {
                put("name", name)
                description?.takeIf(String::isNotBlank)?.let { put("description", it) }
            }
            if (tracks.isNotEmpty()) {
                putJsonObject("relationships") {
                    putJsonObject("tracks") { put("data", trackData(tracks)) }
                }
            }
        }
        val response = request(
            url("v1", "me", "library", "playlists"),
            personal = true,
            jsonBody = body.toString(),
            expectedStatus = 201
        )
        return try {
            Json.parseToJsonElement(response).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw IOException("Apple Music returned no created playlist")
    }

    /** Appends [tracks] to library playlist [playlistId] (`p.…`), in order (`POST …/tracks`, 204). */
    suspend fun addTracksToLibraryPlaylist(playlistId: String, tracks: List<AppleMusicTrackRef>) {
        require(playlistId.matches(Regex("p\\.[A-Za-z0-9]+"))) { "A library playlist ID is required" }
        if (tracks.isEmpty()) return
        request(
            url("v1", "me", "library", "playlists", playlistId, "tracks"),
            personal = true,
            jsonBody = buildJsonObject { put("data", trackData(tracks)) }.toString(),
            expectedStatus = 204
        )
    }

    private fun trackData(tracks: List<AppleMusicTrackRef>) = buildJsonArray {
        tracks.forEach { ref ->
            add(
                buildJsonObject {
                    put("id", ref.id)
                    put("type", ref.type)
                }
            )
        }
    }

    /**
     * The authenticated catalog → library relationship is the authoritative membership check.
     * A missing/malformed collection is an error, never evidence that the song is absent.
     */
    suspend fun librarySongId(storefront: String, catalogId: String): String? {
        require(storefront.matches(Regex("[a-z]{2}")))
        require(catalogId.matches(Regex("[0-9]+"))) { "A catalog song ID is required" }
        val response = try {
            get(url("v1", "catalog", storefront, "songs", catalogId, "library"), personal = true)
        } catch (error: AppleMusicApiException) {
            if (error.failure != AppleMusicApiFailure.Http(404)) throw error
            // Observed on the subscribed Pixel Tablet account (2026-10-01): an unsaved
            // song's library relationship returns 404. Interpret only that status as absence,
            // and only after a public 200 confirms the exact catalog song still exists.
            val catalog = get(url("v1", "catalog", storefront, "songs", catalogId), personal = false)
            val catalogResources = catalog["data"] as? JsonArray
                ?: throw IOException("Apple Music returned no matching catalog song")
            val exactSongExists = catalogResources.any { item ->
                val song = item as? JsonObject ?: return@any false
                (song["type"] as? JsonPrimitive)?.contentOrNull == "songs" &&
                    (song["id"] as? JsonPrimitive)?.contentOrNull == catalogId
            }
            if (!exactSongExists) throw IOException("Apple Music returned no matching catalog song")
            return null
        }
        val resources = response["data"] as? JsonArray
            ?: throw IOException("Apple Music returned no library relationship")
        if (resources.isEmpty()) return null
        val resource = resources.first() as? JsonObject
            ?: throw IOException("Apple Music returned an invalid library relationship")
        val libraryId = (resource["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        if ((resource["type"] as? JsonPrimitive)?.contentOrNull != "library-songs" || libraryId == null) {
            throw IOException("Apple Music returned an invalid library relationship")
        }
        return libraryId
    }

    /**
     * All request paths are constructed by this provider, never by a cover or external URL.
     * A [next] link keeps [query]'s parameters it leaves out: Apple's links carry the offset
     * but not always `limit` or `include`, and without them later pages shrink to the default
     * size or lose their relationships. [excludedQueryParameters] removes rejected options even
     * when the next link explicitly carries them, without changing its pagination position.
     */
    suspend fun resourcePage(
        segments: List<String>,
        personal: Boolean,
        query: Map<String, String> = emptyMap(),
        next: String? = null,
        excludedQueryParameters: Set<String> = emptySet()
    ): JsonObject {
        val initial = url(*segments.toTypedArray()).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val target = if (next == null) {
            initial
        } else {
            val link = requireNotNull(baseUrl.resolve(next)).also {
                require(
                    it.scheme == baseUrl.scheme && it.host == baseUrl.host && it.port == baseUrl.port &&
                        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == initial.encodedPath
                ) {
                    "Invalid Apple Music pagination link"
                }
            }
            link.newBuilder().apply {
                query.forEach { (key, value) ->
                    if (link.queryParameter(key) == null) addQueryParameter(key, value)
                }
            }.build()
        }
        val filtered = if (excludedQueryParameters.isEmpty()) {
            target
        } else {
            // Match decoded names: removeAllQueryParameters("include") misses a key like "%69nclude".
            target.newBuilder().query(null).apply {
                for (index in 0 until target.querySize) {
                    val name = target.queryParameterName(index)
                    if (name !in excludedQueryParameters) {
                        addQueryParameter(name, target.queryParameterValue(index))
                    }
                }
            }.build()
        }
        return get(filtered, personal)
    }

    private fun songs(page: JsonObject): List<AppleMusicSong> =
        page["data"]?.jsonArray.orEmpty().map { AppleMusicSong.fromJson(it.jsonObject) }

    private suspend fun get(target: HttpUrl, personal: Boolean): JsonObject {
        val body = request(target, personal, expectedStatus = 200)
        return try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: IllegalArgumentException) {
            // Parser exceptions can quote response content; keep it out of user-visible errors.
            throw IOException("Apple Music returned an invalid JSON response")
        }
    }

    private suspend fun request(
        target: HttpUrl,
        personal: Boolean,
        post: Boolean = false,
        expectedStatus: Int,
        // A JSON body makes the request a POST with it.
        jsonBody: String? = null
    ): String = withContext(Dispatchers.IO) {
        val write = post || jsonBody != null
        val userToken = if (personal) {
            musicUserToken()?.takeIf { it.isNotBlank() }
                ?: throw AppleMusicApiException(AppleMusicApiFailure.UserAuthorizationRequired)
        } else {
            null
        }
        val token = developerToken().takeIf { it.isNotBlank() }
            ?: throw AppleMusicApiException(AppleMusicApiFailure.DeveloperTokenRequired)
        val builder = Request.Builder().url(target)
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
        userToken?.let { builder.header("Music-User-Token", it) }
        when {
            jsonBody != null -> builder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            post -> builder.post(ByteArray(0).toRequestBody(null))
        }
        val call = http.newCall(builder.build())
        val read = { response: Response -> response.use { body(it, target, expectedStatus) } }
        // A read is cancelled with its caller, so a scope that has already failed (or returned) doesn't wait
        // out a parallel read, up to the call timeout. The library add runs to its end: leaving the screen
        // right after tapping Add must not lose the write.
        if (write) read(call.execute()) else call.executeCancellable(read)
    }

    private fun body(response: Response, target: HttpUrl, expectedStatus: Int): String {
        if (response.code != expectedStatus) {
            // Do not include response bodies or request URLs/tokens in user-visible errors.
            val failure = when (response.code) {
                401 -> AppleMusicApiFailure.DeveloperTokenRejected
                403 -> AppleMusicApiFailure.AccessDenied
                429 -> AppleMusicApiFailure.RateLimited
                else -> AppleMusicApiFailure.Http(response.code)
            }
            val request = diagnosticRequest(target)
            if (BuildConfig.DEBUG) Log.w(TAG, "${response.code} $request")
            throw AppleMusicApiException(failure, response.code, request, appleErrorCode(response))
        }
        // 204 and some 201s carry no body.
        return response.body.string()
    }

    private fun url(vararg segments: String): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()
}

/**
 * Which request failed, for a diagnostic the user copies: the path and its includes. The user's own library ids
 * are masked; catalog ids and the storefront are Apple's public names and say which song or storefront it was.
 * Never a token, never a body.
 */
internal fun diagnosticRequest(target: HttpUrl): String {
    val segments = target.pathSegments
    val personal = segments.getOrNull(1) == "me"
    val path = segments.mapIndexed { index, segment ->
        if (personal && segments.getOrNull(index - 1) in DiagnosticResourceTypes) "{id}" else segment
    }.joinToString("/", prefix = "/")
    val includes = target.queryParameterNames.filter { it.startsWith("include") }.sorted()
        .joinToString(" ") { "$it=${target.queryParameter(it)}" }
    return if (includes.isEmpty()) path else "$path $includes"
}

/** Apple's own code for the refusal (`errors[0].code`, as "40300"): digits only, none of the error's prose. */
private fun appleErrorCode(response: Response): String? = runCatching {
    val errors = Json.parseToJsonElement(response.peekBody(APPLE_ERROR_PEEK_BYTES).string())
        .jsonObject["errors"]?.jsonArray
    errors?.firstOrNull()?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
}.getOrNull()?.takeIf { code -> code.isNotEmpty() && code.length <= 8 && code.all(Char::isDigit) }

private val DiagnosticResourceTypes =
    setOf("songs", "albums", "artists", "playlists", "music-videos", "stations", "library-songs")
private const val APPLE_ERROR_PEEK_BYTES = 4_096L

/**
 * This call's response, through [read] before it is closed, cancelled with the caller: cancelling the caller
 * cancels the call, which also aborts a body still streaming. A blocking `execute()` would hold a cancelled
 * caller until the response arrived.
 */
private suspend fun <T> Call.executeCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { read(response) })
                }
            }
        )
    }

/** A song as a playlist's track: a catalog song (`songs`) or one in the user's library (`library-songs`). */
data class AppleMusicTrackRef(val id: String, val type: String) {
    init {
        require(type == "songs" || type == "library-songs")
        require(id.isNotBlank())
    }
}

/** Codes per `filter[isrc]` request. */
internal const val ISRC_BATCH = 25
private val ISRC_PATTERN = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

data class AppleMusicSongPage(val songs: List<AppleMusicSong>, val next: String?)

enum class AppleMusicLibraryMutation { AcceptedPendingConfirmation }

sealed interface AppleMusicApiFailure {
    data object DeveloperTokenRequired : AppleMusicApiFailure
    data object UserAuthorizationRequired : AppleMusicApiFailure
    data object DeveloperTokenRejected : AppleMusicApiFailure

    // A 403 does not prove revocation: subscription/access restrictions can also cause it.
    data object AccessDenied : AppleMusicApiFailure
    data object RateLimited : AppleMusicApiFailure
    data class Http(val status: Int) : AppleMusicApiFailure
}

/**
 * [status], [request] ([diagnosticRequest]) and [appleCode] are what a copied diagnostic says of an HTTP refusal;
 * a failure raised before any request (no token) has none of them.
 */
class AppleMusicApiException(
    val failure: AppleMusicApiFailure,
    val status: Int? = null,
    val request: String? = null,
    val appleCode: String? = null,
) : IOException("Apple Music: $failure")

private const val TAG = "AppleMusicApi"

/** See [AppleMusicApiClient.storefrontDetails]. */
data class AppleMusicStorefront(val id: String, val defaultLanguage: String?, val languages: List<String>)
