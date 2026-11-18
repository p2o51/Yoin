package com.gpo.yoin.shared.sync.drive

import com.gpo.yoin.shared.sync.SyncTokenProvider
import com.gpo.yoin.shared.sync.SyncTransportException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of Android's DriveAppDataClientTest (OkHttp MockWebServer) onto Ktor's MockEngine:
 * same paths, query parameters, multipart layout, retry and error mapping.
 */
class DriveAppDataClientTest {
    private class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val replies = ArrayDeque<Reply>()
    private val requests = mutableListOf<HttpRequestData>()
    private val tokens = FakeTokens("token-1", "token-2", "token-3")

    private val engine = MockEngine { request ->
        requests += request
        val reply = replies.removeFirstOrNull() ?: Reply(500, "no reply queued".encodeToByteArray())
        respondWith(reply)
    }

    private val client = DriveAppDataClient(
        tokens = tokens,
        apiBase = "http://drive.test/drive/v3/",
        uploadBase = "http://drive.test/upload/drive/v3/",
        engine = engine,
    )

    private fun MockRequestHandleScope.respondWith(reply: Reply): HttpResponseData = respond(
        content = reply.body,
        status = HttpStatusCode.fromValue(reply.status),
        headers = headersOf(
            *(reply.headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }
                .toTypedArray(),
        ),
    )

    private fun json(body: String, status: Int = 200) = replies.addLast(Reply(status, body.encodeToByteArray()))

    private fun error(code: Int, reason: String) =
        """{"error":{"code":$code,"message":"$reason","errors":[{"domain":"global","reason":"$reason"}]}}"""

    private fun file(id: String, version: String = "1") =
        """{"id":"$id","name":"n","version":"$version","appProperties":{}}"""

    private fun bodyBytes(request: HttpRequestData): ByteArray =
        (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)

    @Test
    fun should_followNextPageToken_when_listingSpansPages() = runTest {
        json("""{"nextPageToken":"page-2","files":[${file("a")}]}""")
        json("""{"files":[${file("b")}]}""")

        val files = client.list()

        assertEquals(listOf("a", "b"), files.map { it.id })
        val first = requests[0].url
        assertEquals("/drive/v3/files", first.encodedPath)
        assertEquals("appDataFolder", first.parameters["spaces"])
        assertEquals("1000", first.parameters["pageSize"])
        assertEquals("nextPageToken,files(id,name,version,modifiedTime,size,appProperties)", first.parameters["fields"])
        assertNull(first.parameters["pageToken"])
        assertEquals("page-2", requests[1].url.parameters["pageToken"])
        assertEquals("Bearer token-1", requests[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun should_failInsteadOfLooping_when_pageTokenRepeats() = runTest {
        json("""{"nextPageToken":"same","files":[]}""")
        json("""{"nextPageToken":"same","files":[]}""")

        assertFailsWith<SyncTransportException.Server> { client.list() }
    }

    @Test
    fun should_decodeQuotedInt64s_when_listingFiles() = runTest {
        json(
            """{"files":[{"id":"f1","name":"yoin-state-d1.json.gz","version":"123","size":"4567",
            |"modifiedTime":"2026-10-04T08:15:30.123Z","appProperties":{"yoinKind":"state","deviceId":"d1"}}]}
            """.trimMargin(),
        )

        val file = client.list().single()

        assertEquals(123L, file.version)
        assertEquals(4567L, file.size)
        assertEquals(1_791_101_730_123L, file.modifiedTime)
        assertEquals(mapOf("yoinKind" to "state", "deviceId" to "d1"), file.appProperties)
    }

    @Test
    fun should_sendMultipartRelatedWithMetadataFirst_when_creatingFile() = runTest {
        json(file("pre-id", version = "1"))

        val created = client.create(
            fileId = "pre-id",
            name = "yoin-state-d1.json.gz",
            appProperties = mapOf("yoinKind" to "state", "deviceId" to "d1"),
            mimeType = "application/gzip",
            bytes = "gzip-bytes".encodeToByteArray(),
        )

        assertEquals("pre-id", created.id)
        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("/upload/drive/v3/files", request.url.encodedPath)
        assertEquals("multipart", request.url.parameters["uploadType"])
        assertEquals("id,name,version,modifiedTime,size,appProperties", request.url.parameters["fields"])
        val contentType = request.body.contentType.toString()
        assertTrue(contentType.startsWith("multipart/related"), contentType)
        val boundary = contentType.substringAfter("boundary=")
        val parts = bodyBytes(request).decodeToString().split("--$boundary").drop(1).dropLast(1)
        assertEquals(2, parts.size)
        val (metaHeaders, metaBody) = parts[0].trim('\r', '\n').split("\r\n\r\n", limit = 2)
        assertTrue(metaHeaders.contains("Content-Type: application/json", ignoreCase = true))
        val metadata = Json.parseToJsonElement(metaBody.trim()).jsonObject
        assertEquals("pre-id", metadata["id"]!!.jsonPrimitive.content)
        assertEquals("yoin-state-d1.json.gz", metadata["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("appDataFolder"), metadata["parents"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("application/gzip", metadata["mimeType"]!!.jsonPrimitive.content)
        assertEquals("state", metadata["appProperties"]!!.jsonObject["yoinKind"]!!.jsonPrimitive.content)
        val (mediaHeaders, mediaBody) = parts[1].trimStart('\r', '\n').split("\r\n\r\n", limit = 2)
        assertTrue(mediaHeaders.contains("Content-Type: application/gzip", ignoreCase = true))
        assertEquals("gzip-bytes", mediaBody.removeSuffix("\r\n"))
    }

    @Test
    fun should_throwAlreadyExists_when_createReturns409() = runTest {
        json(error(409, "duplicate"), status = 409)

        assertFailsWith<SyncTransportException.AlreadyExists> {
            client.create("pre-id", "n", emptyMap(), "application/gzip", byteArrayOf(1))
        }
    }

    @Test
    fun should_patchMediaOnly_when_updatingFile() = runTest {
        json(file("f1", version = "9"))
        val gzip = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2)

        val updated = client.update("f1", gzip)

        assertEquals(9L, updated!!.version)
        val request = requests.single()
        assertEquals("PATCH", request.method.value)
        assertEquals("/upload/drive/v3/files/f1", request.url.encodedPath)
        assertEquals("media", request.url.parameters["uploadType"])
        assertEquals("application/gzip", request.body.contentType.toString())
        assertContentEquals(gzip, bodyBytes(request))
    }

    @Test
    fun should_returnNull_when_updateOrDownloadTargetIsMissing() = runTest {
        json(error(404, "notFound"), status = 404)
        json(error(404, "notFound"), status = 404)

        assertNull(client.update("gone", byteArrayOf(1)))
        assertNull(client.download("gone"))
    }

    @Test
    fun should_returnBytes_when_downloadingFile() = runTest {
        replies.addLast(Reply(200, byteArrayOf(1, 2, 3)))

        assertContentEquals(byteArrayOf(1, 2, 3), client.download("f1"))
        assertEquals("/drive/v3/files/f1", requests.single().url.encodedPath)
        assertEquals("media", requests.single().url.parameters["alt"])
    }

    @Test
    fun should_reportDeleted_when_fileIsAlreadyGone() = runTest {
        replies.addLast(Reply(204, ByteArray(0)))
        json(error(404, "notFound"), status = 404)

        assertTrue(client.delete("f1"))
        assertTrue(client.delete("f2"))
        assertEquals("DELETE", requests[0].method.value)
        assertEquals("/drive/v3/files/f1", requests[0].url.encodedPath)
    }

    @Test
    fun should_requestAppDataIds_when_generatingIds() = runTest {
        json("""{"ids":["a","b"]}""")

        assertEquals(listOf("a", "b"), client.generateIds(2))
        val url = requests.single().url
        assertEquals("/drive/v3/files/generateIds", url.encodedPath)
        assertEquals("appDataFolder", url.parameters["space"])
        assertEquals("2", url.parameters["count"])
    }

    @Test
    fun should_throwServer_when_driveReturnsFewerIdsThanRequested() = runTest {
        json("""{"ids":["a"]}""")

        assertFailsWith<SyncTransportException.Server> { client.generateIds(2) }
    }

    @Test
    fun should_retryOnceWithFreshToken_when_serverReturns401() = runTest {
        json(error(401, "authError"), status = 401)
        json("""{"files":[]}""")

        client.list()

        assertEquals(listOf("token-1"), tokens.invalidated)
        assertEquals("Bearer token-2", requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun should_throwUnauthorized_when_refreshedTokenIsAlsoRejected() = runTest {
        json(error(401, "authError"), status = 401)
        json(error(401, "authError"), status = 401)

        assertFailsWith<SyncTransportException.Unauthorized> { client.list() }
        assertEquals(2, requests.size)
    }

    @Test
    fun should_mapDriveErrors_when_requestsFail() = runTest {
        json(error(403, "storageQuotaExceeded"), status = 403)
        assertFailsWith<SyncTransportException.StorageFull> {
            client.create("pre-id", "n", emptyMap(), "application/gzip", byteArrayOf(1))
        }

        json(error(403, "accessNotConfigured"), status = 403)
        json(
            """{"error":{"code":403,"message":"disabled","status":"PERMISSION_DENIED",
            |"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED"}]}}
            """.trimMargin(),
            status = 403,
        )
        assertFailsWith<SyncTransportException.Misconfigured> { client.list() }
        assertFailsWith<SyncTransportException.Misconfigured> { client.list() }

        json(error(403, "insufficientPermissions"), status = 403)
        assertFailsWith<SyncTransportException.Unauthorized> { client.list() }
        assertTrue(tokens.invalidated.isEmpty())

        replies.addLast(Reply(429, error(429, "rateLimitExceeded").encodeToByteArray(), mapOf("Retry-After" to "7")))
        json(error(403, "userRateLimitExceeded"), status = 403)
        assertEquals(7_000L, assertFailsWith<SyncTransportException.RateLimited> { client.list() }.retryAfterMs)
        assertNull(assertFailsWith<SyncTransportException.RateLimited> { client.list() }.retryAfterMs)

        replies.addLast(Reply(503, "<html>unavailable</html>".encodeToByteArray()))
        assertEquals(503, assertFailsWith<SyncTransportException.Server> { client.list() }.code)
    }

    @Test
    fun should_throwNetwork_when_engineFails() = runTest {
        val failing = DriveAppDataClient(
            tokens = tokens,
            apiBase = "http://drive.test/drive/v3/",
            engine = MockEngine { throw IllegalStateException("connection refused") },
        )

        assertFailsWith<SyncTransportException.Network> { failing.list() }
    }

    @Test
    fun should_parsePermissionId_when_fetchingAbout() = runTest {
        json("""{"user":{"permissionId":"perm-1","emailAddress":"a@example.com","displayName":"A"}}""")

        val about = client.about()

        assertEquals("perm-1", about.permissionId)
        assertEquals("a@example.com", about.email)
        assertEquals("A", about.displayName)
        assertEquals("/drive/v3/about", requests.single().url.encodedPath)
        assertEquals("user(permissionId,emailAddress,displayName)", requests.single().url.parameters["fields"])
    }

    @Test
    fun should_throwServer_when_aboutHasNoPermissionId() = runTest {
        json("""{"user":{"emailAddress":"a@example.com"}}""")

        assertFailsWith<SyncTransportException.Server> { client.about() }
    }

    private class FakeTokens(vararg tokens: String) : SyncTokenProvider {
        private val queue = ArrayDeque(tokens.toList())
        private var current: String? = null
        val invalidated = mutableListOf<String>()

        override suspend fun token(): String = current ?: queue.removeFirst().also { current = it }

        override suspend fun invalidate(token: String) {
            invalidated += token
            if (current == token) current = null
        }
    }
}
