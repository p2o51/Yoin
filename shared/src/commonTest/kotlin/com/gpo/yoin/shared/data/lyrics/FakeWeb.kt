package com.gpo.yoin.shared.data.lyrics

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/** What [FakeWeb] answers with. [Disconnect] drops the connection (OkHttp MockWebServer's DISCONNECT_AFTER_REQUEST). */
internal sealed interface FakeReply {
    class Response(val status: Int, val body: String = "", val contentType: String? = null) : FakeReply
    data object Disconnect : FakeReply
}

internal fun ok(body: String) = FakeReply.Response(200, body)

internal fun status(code: Int, body: String = "") = FakeReply.Response(code, body)

internal class RecordedRequest(private val data: HttpRequestData) {
    val method: String = data.method.value
    val url: Url = data.url
    val host: String get() = url.host

    /** `/path?query`, like OkHttp's RecordedRequest.path. */
    val path: String get() = url.encodedPath + (if (url.encodedQuery.isNotEmpty()) "?${url.encodedQuery}" else "")
    val encodedPath: String get() = url.encodedPath

    /** `/<host><path>`, how [FakeWeb.onExact] routes are keyed. */
    val hostAndPath: String get() = "/${url.host}${url.encodedPath}"
    fun queryParameter(name: String): String? = url.parameters[name]
    fun header(name: String): String? = data.headers[name]
    val headerNames: Set<String> get() = data.headers.names()
    val contentType: String? get() = data.body.contentType?.toString()

    val body: String
        get() = when (val b = data.body) {
            is TextContent -> b.text
            is ByteArrayContent -> b.bytes().decodeToString()
            else -> ""
        }
}

/**
 * A scripted web server for the provider tests (stands in for OkHttp's MockWebServer): answers from a FIFO
 * [enqueue] queue, or from routes keyed by path prefix ([on]) / by `/<host><path>` ([onExact]); anything else is 404.
 * Every request is recorded in [seen].
 */
internal class FakeWeb {
    val seen = mutableListOf<RecordedRequest>()
    private val queue = ArrayDeque<FakeReply>()
    private val prefixRoutes = LinkedHashMap<String, (RecordedRequest) -> FakeReply>()
    private val exactRoutes = LinkedHashMap<String, (RecordedRequest) -> FakeReply>()

    val requestCount: Int get() = seen.size

    fun enqueue(reply: FakeReply) {
        queue.addLast(reply)
    }

    fun enqueue(status: Int, body: String) = enqueue(FakeReply.Response(status, body))

    fun on(pathPrefix: String, reply: (RecordedRequest) -> FakeReply) {
        prefixRoutes[pathPrefix] = reply
    }

    /** Matches `/<host><encodedPath>` exactly (providers that call absolute https URLs). */
    fun onExact(hostAndPath: String, reply: (RecordedRequest) -> FakeReply) {
        exactRoutes[hostAndPath] = reply
    }

    fun reset() {
        seen.clear()
        queue.clear()
        prefixRoutes.clear()
        exactRoutes.clear()
    }

    val engine = MockEngine { data ->
        val request = RecordedRequest(data)
        seen += request
        val reply = exactRoutes["/${request.host}${request.encodedPath}"]?.invoke(request)
            ?: prefixRoutes.entries.firstOrNull { request.path.startsWith(it.key) }?.value?.invoke(request)
            ?: queue.removeFirstOrNull()
            ?: FakeReply.Response(404)
        when (reply) {
            FakeReply.Disconnect -> throw IllegalStateException("connection dropped")
            is FakeReply.Response -> respond(
                content = reply.body,
                status = HttpStatusCode.fromValue(reply.status),
                headers = headersOf(HttpHeaders.ContentType, reply.contentType ?: "application/json; charset=utf-8"),
            )
        }
    }
}
