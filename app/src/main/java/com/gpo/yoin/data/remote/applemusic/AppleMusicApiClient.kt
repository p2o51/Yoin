package com.gpo.yoin.data.remote.applemusic

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Tokens are supplied per profile. This client neither signs JWTs nor logs credentials. */
class AppleMusicApiClient(
    private val developerToken: suspend () -> String,
    private val musicUserToken: () -> String?,
    // Dedicated transport: never inherit another provider's logging/auth interceptors.
    private val transport: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = "https://api.music.apple.com/".toHttpUrl()
) {
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()

    init {
        require(baseUrl.isHttps || baseUrl.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    suspend fun storefront(): String {
        val response = get(url("v1", "me", "storefront"), personal = true)
        return response["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IOException("Apple Music returned no storefront")
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

    /** All request paths are constructed by this provider, never by a cover or external URL. */
    suspend fun resourcePage(
        segments: List<String>,
        personal: Boolean,
        query: Map<String, String> = emptyMap(),
        next: String? = null
    ): JsonObject {
        val initial = url(*segments.toTypedArray()).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val target = if (next == null) {
            initial
        } else {
            requireNotNull(baseUrl.resolve(next)).also {
                require(
                    it.scheme == baseUrl.scheme && it.host == baseUrl.host && it.port == baseUrl.port &&
                        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == initial.encodedPath
                ) {
                    "Invalid Apple Music pagination link"
                }
            }
        }
        return get(target, personal)
    }

    private fun songs(page: JsonObject): List<AppleMusicSong> =
        page["data"]?.jsonArray.orEmpty().map { AppleMusicSong.fromJson(it.jsonObject) }

    private suspend fun get(target: HttpUrl, personal: Boolean): JsonObject =
        Json.parseToJsonElement(request(target, personal, expectedStatus = 200)).jsonObject

    private suspend fun request(
        target: HttpUrl,
        personal: Boolean,
        post: Boolean = false,
        expectedStatus: Int
    ): String = withContext(Dispatchers.IO) {
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
        if (post) builder.post(ByteArray(0).toRequestBody(null))
        http.newCall(builder.build()).execute().use { response ->
            if (response.code != expectedStatus) {
                // Do not include response bodies or request URLs/tokens in user-visible errors.
                val failure = when (response.code) {
                    401 -> AppleMusicApiFailure.DeveloperTokenRejected
                    403 -> AppleMusicApiFailure.AccessDenied
                    429 -> AppleMusicApiFailure.RateLimited
                    else -> AppleMusicApiFailure.Http(response.code)
                }
                throw AppleMusicApiException(failure)
            }
            response.body.string()
        }
    }

    private fun url(vararg segments: String): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()
}

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

class AppleMusicApiException(val failure: AppleMusicApiFailure) : IOException("Apple Music: $failure")
