package com.gpo.yoin.data.source.spotify

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

/**
 * Which Spotify developer app a request went out under, for a tester's diagnostics: the Client ID this build
 * ships with, one the user entered in Settings, or none at all. Development Mode limits are per app, so the
 * same playlist can open under one and fail under another.
 */
enum class SpotifyClientIdKind(val label: String) {
    BuiltIn("built-in"),
    Own("own"),
    None("none"),
    ;

    companion object {
        fun of(clientId: String, builtInClientId: String): SpotifyClientIdKind = when {
            clientId.isBlank() -> None
            clientId == builtInClientId -> BuiltIn
            else -> Own
        }
    }
}

/**
 * A Spotify answer that came back 2xx but didn't fit Yoin's model. Carries the request as a template and the
 * decoder's failure as its cause, which names where in the JSON it stopped (never the JSON itself).
 */
class SpotifyResponseDecodeException(
    val request: String,
    val clientIdKind: SpotifyClientIdKind?,
    cause: SerializationException,
) : Exception("Spotify response could not be read: $request", cause)

/**
 * `GET /v1/playlists/{id}/items`: the method and path of [url] with every id in it replaced, and no query. A
 * path segment is an id when it follows one of the collections Spotify addresses by id.
 */
internal fun spotifyRequestTemplate(method: String, url: HttpUrl): String {
    val segments = url.pathSegments.filter(String::isNotEmpty)
    val path = segments.mapIndexed { index, segment ->
        if (index > 0 && segments[index - 1] in ID_COLLECTIONS) "{id}" else segment
    }
    return "$method /" + path.joinToString("/")
}

/**
 * Spotify's own reason from an error [body] (`{"error":{"status":403,"message":"…"}}`), on one line and cut to
 * length, with any Spotify URI or id in it replaced. Null when the body names none.
 */
internal fun spotifyErrorReason(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val error = runCatching { REASON_JSON.parseToJsonElement(body) }.getOrNull()
        ?.let { it as? JsonObject }
        ?.get("error")
    val message = when (error) {
        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }
    return message
        ?.replace(SPOTIFY_URI_REGEX, "spotify:{id}")
        ?.replace(SPOTIFY_ID_REGEX, "{id}")
        ?.replace(WHITESPACE_REGEX, " ")
        ?.trim()
        ?.take(MAX_REASON_LENGTH)
        ?.takeIf(String::isNotEmpty)
}

private val ID_COLLECTIONS = setOf(
    "albums",
    "artists",
    "audiobooks",
    "chapters",
    "episodes",
    "playlists",
    "shows",
    "tracks",
    "users",
)
private val REASON_JSON = Json { isLenient = true }
// The whole URI up to whitespace or a quote: a local track's carries artist, album and title fields.
private val SPOTIFY_URI_REGEX = Regex("spotify:[^\\s'\"]+")
private val SPOTIFY_ID_REGEX = Regex("\\b[0-9A-Za-z]{22}\\b")
private val WHITESPACE_REGEX = Regex("\\s+")
private const val MAX_REASON_LENGTH = 160
