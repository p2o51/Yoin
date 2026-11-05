package com.gpo.yoin.shared.data.lyrics

import com.gpo.yoin.shared.data.subsonic.platformHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Shared by every provider: lenient JSON, same as Android's `Json { ignoreUnknownKeys; isLenient }`. */
internal val LyricJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Android's OkHttp defaults for these providers: connect 10 s, read 10 s, whole call 15 s. */
internal fun newLyricHttpClient(engine: HttpClientEngine?): HttpClient =
    HttpClient(engine ?: platformHttpEngine()) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

internal val JSON_UTF8: ContentType = ContentType.parse("application/json; charset=utf-8")

internal class TextReply(val status: Int, val text: String) {
    val isSuccessful: Boolean get() = status in 200..299
}

internal suspend fun HttpClient.getText(url: Url, headers: List<Pair<String, String>> = emptyList()): TextReply {
    val response = get(url) { headers.forEach { (k, v) -> header(k, v) } }
    return TextReply(response.status.value, if (response.status.isSuccess()) response.bodyAsText() else "")
}

internal suspend fun HttpClient.postJsonText(
    url: Url,
    json: String,
    headers: List<Pair<String, String>> = emptyList(),
): TextReply {
    val response = post(url) {
        headers.forEach { (k, v) -> header(k, v) }
        setBody(TextContent(json, JSON_UTF8))
    }
    return TextReply(response.status.value, if (response.status.isSuccess()) response.bodyAsText() else "")
}

internal class BytesReply(val status: Int, val bytes: ByteArray)

internal suspend fun HttpClient.getBytes(url: Url, headers: List<Pair<String, String>> = emptyList()): BytesReply {
    val response = get(url) { headers.forEach { (k, v) -> header(k, v) } }
    return BytesReply(response.status.value, if (response.status.isSuccess()) response.bodyAsBytes() else ByteArray(0))
}

/**
 * Runs [block]; any failure other than the caller's own cancellation goes to
 * [onError]. (Ktor's timeouts surface as ordinary exceptions, but if this
 * coroutine was cancelled the cancellation must keep propagating.)
 */
internal suspend inline fun <T> attempt(onError: (Exception) -> T, block: () -> T): T = try {
    block()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    onError(e)
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

internal fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull
