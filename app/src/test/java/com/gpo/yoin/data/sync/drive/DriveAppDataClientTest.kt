package com.gpo.yoin.data.sync.drive

import com.gpo.yoin.data.sync.SyncTokenProvider
import com.gpo.yoin.data.sync.SyncTransportException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveAppDataClientTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokens
    private lateinit var client: DriveAppDataClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokens("token-1", "token-2", "token-3")
        client = newClient(tokens)
    }

    @After
    fun cleanup() {
        runCatching { server.shutdown() }
    }

    private fun newClient(tokenProvider: SyncTokenProvider) = DriveAppDataClient(
        http = OkHttpClient(),
        tokens = tokenProvider,
        apiBase = server.url("/drive/v3/").toString(),
        uploadBase = server.url("/upload/drive/v3/").toString(),
    )

    @Test
    fun should_followNextPageToken_when_listingSpansPages() = runTest {
        server.enqueue(json("""{"nextPageToken":"page-2","files":[${file("a", version = "1")}]}"""))
        server.enqueue(json("""{"files":[${file("b", version = "2")}]}"""))

        val files = client.list()

        assertEquals(listOf("a", "b"), files.map { it.id })
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/drive/v3/files", first.requestUrl!!.encodedPath)
        assertEquals("appDataFolder", first.requestUrl!!.queryParameter("spaces"))
        assertEquals("1000", first.requestUrl!!.queryParameter("pageSize"))
        assertEquals(
            "nextPageToken,files(id,name,version,modifiedTime,size,appProperties)",
            first.requestUrl!!.queryParameter("fields"),
        )
        assertNull(first.requestUrl!!.queryParameter("pageToken"))
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        assertEquals("page-2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test
    fun should_decodeQuotedInt64s_when_listingFiles() = runTest {
        server.enqueue(
            json(
                """{"files":[{"id":"f1","name":"yoin-state-d1.json.gz","version":"123","size":"4567",
                |"modifiedTime":"2026-10-04T08:15:30.123Z","appProperties":{"yoinKind":"state","deviceId":"d1"}}]}
                """.trimMargin(),
            ),
        )

        val file = client.list().single()

        assertEquals(123L, file.version)
        assertEquals(4567L, file.size)
        assertEquals(1_791_101_730_123L, file.modifiedTime)
        assertEquals(mapOf("yoinKind" to "state", "deviceId" to "d1"), file.appProperties)
    }

    @Test
    fun should_sendMultipartRelatedWithMetadataFirst_when_creatingFile() = runTest {
        server.enqueue(json(file("pre-id", version = "1")))
        val media = "gzip-bytes".toByteArray()

        val created = client.create(
            fileId = "pre-id",
            name = "yoin-state-d1.json.gz",
            appProperties = mapOf("yoinKind" to "state", "deviceId" to "d1"),
            mimeType = "application/gzip",
            bytes = media,
        )

        assertEquals("pre-id", created.id)
        assertEquals(1L, created.version)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.requestUrl!!.encodedPath)
        assertEquals("multipart", request.requestUrl!!.queryParameter("uploadType"))
        assertEquals("id,name,version,modifiedTime,size,appProperties", request.requestUrl!!.queryParameter("fields"))
        val parts = multipartParts(request)
        assertEquals(2, parts.size)
        val (metadataHeaders, metadataBody) = parts[0]
        assertTrue(metadataHeaders.contains("Content-Type: application/json", ignoreCase = true))
        val metadata = Json.parseToJsonElement(metadataBody).jsonObject
        assertEquals("pre-id", metadata["id"]!!.jsonPrimitive.content)
        assertEquals("yoin-state-d1.json.gz", metadata["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("appDataFolder"), metadata["parents"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("application/gzip", metadata["mimeType"]!!.jsonPrimitive.content)
        assertEquals("state", metadata["appProperties"]!!.jsonObject["yoinKind"]!!.jsonPrimitive.content)
        val (mediaHeaders, mediaBody) = parts[1]
        assertTrue(mediaHeaders.contains("Content-Type: application/gzip", ignoreCase = true))
        assertEquals("gzip-bytes", mediaBody)
    }

    @Test
    fun should_throwAlreadyExists_when_createReturns409() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(error(409, "duplicate")))

        assertThrows<SyncTransportException.AlreadyExists> {
            client.create("pre-id", "n", emptyMap(), "application/gzip", byteArrayOf(1))
        }
    }

    @Test
    fun should_patchMediaOnly_when_updatingFile() = runTest {
        server.enqueue(json(file("f1", version = "8")))
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)

        val updated = client.update("f1", bytes)

        assertEquals(8L, updated!!.version)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/upload/drive/v3/files/f1", request.requestUrl!!.encodedPath)
        assertEquals("media", request.requestUrl!!.queryParameter("uploadType"))
        assertEquals("application/gzip", request.getHeader("Content-Type"))
        assertArrayEquals(bytes, request.body.readByteArray())
    }

    @Test
    fun should_returnNull_when_updateTargetIsMissing() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(error(404, "notFound")))

        assertNull(client.update("gone", byteArrayOf(1)))
    }

    @Test
    fun should_returnBytes_when_downloadingFile() = runTest {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(9, 8, 7))))

        assertArrayEquals(byteArrayOf(9, 8, 7), client.download("f1"))
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/f1", request.requestUrl!!.encodedPath)
        assertEquals("media", request.requestUrl!!.queryParameter("alt"))
    }

    @Test
    fun should_returnNull_when_downloadTargetIsMissing() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(error(404, "notFound")))

        assertNull(client.download("gone"))
    }

    @Test
    fun should_reportDeleted_when_fileIsAlreadyGone() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404).setBody(error(404, "notFound")))

        assertTrue(client.delete("f1"))
        assertTrue(client.delete("f2"))
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun should_requestAppDataIds_when_generatingIds() = runTest {
        server.enqueue(json("""{"kind":"drive#generatedIds","space":"appDataFolder","ids":["i1","i2"]}"""))

        assertEquals(listOf("i1", "i2"), client.generateIds(2))
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/generateIds", request.requestUrl!!.encodedPath)
        assertEquals("appDataFolder", request.requestUrl!!.queryParameter("space"))
        assertEquals("2", request.requestUrl!!.queryParameter("count"))
    }

    @Test
    fun should_throwServer_when_driveReturnsFewerIdsThanRequested() = runTest {
        server.enqueue(json("""{"kind":"drive#generatedIds","space":"appDataFolder","ids":[]}"""))
        server.enqueue(json("""{"ids":["i1",""]}"""))

        assertThrows<SyncTransportException.Server> { client.generateIds(1) }
        assertThrows<SyncTransportException.Server> { client.generateIds(2) }
    }

    @Test
    fun should_resendSameMultipartBody_when_createIsRetriedAfter401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(error(401, "authError")))
        server.enqueue(json(file("pre-id", version = "1")))

        val created = client.create("pre-id", "yoin-state-d1.json.gz", mapOf("yoinKind" to "state"), "application/gzip", byteArrayOf(4, 2))

        assertEquals("pre-id", created.id)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        assertEquals("Bearer token-2", second.getHeader("Authorization"))
        assertEquals(first.getHeader("Content-Type"), second.getHeader("Content-Type"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
    }

    @Test
    fun should_cancelInFlightCall_when_coroutineIsCancelled() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient()
        val hanging = DriveAppDataClient(
            http = http,
            tokens = tokens,
            apiBase = server.url("/drive/v3/").toString(),
            uploadBase = server.url("/upload/drive/v3/").toString(),
        )
        val outcome = CompletableDeferred<Throwable?>()

        val job = launch(Dispatchers.IO) {
            try {
                hanging.list()
                outcome.complete(null)
            } catch (error: Throwable) {
                outcome.complete(error)
                throw error
            }
        }
        assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        job.cancelAndJoin()

        val error = outcome.await()
        assertTrue("expected cancellation, got $error", error is kotlinx.coroutines.CancellationException)
        // Without call.cancel() the socket would stay busy until OkHttp's 10 s read timeout.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (http.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test
    fun should_retryOnceWithFreshToken_when_serverReturns401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(error(401, "authError")))
        server.enqueue(json("""{"files":[]}"""))

        assertEquals(emptyList<Any>(), client.list())

        assertEquals(listOf("token-1"), tokens.invalidated)
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun should_throwUnauthorized_when_refreshedTokenIsAlsoRejected() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(error(401, "authError")))
        server.enqueue(MockResponse().setResponseCode(401).setBody(error(401, "authError")))

        assertThrows<SyncTransportException.Unauthorized> { client.about() }

        assertEquals(listOf("token-1"), tokens.invalidated)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun should_throwStorageFull_when_quotaIsExceeded() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(error(403, "storageQuotaExceeded")))

        assertThrows<SyncTransportException.StorageFull> {
            client.create("pre-id", "n", emptyMap(), "application/gzip", byteArrayOf(1))
        }
    }

    @Test
    fun should_throwMisconfigured_when_driveApiIsNotEnabled() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(error(403, "accessNotConfigured")))
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"disabled","status":"PERMISSION_DENIED",
                |"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED"}]}}
                """.trimMargin(),
            ),
        )

        assertThrows<SyncTransportException.Misconfigured> { client.list() }
        assertThrows<SyncTransportException.Misconfigured> { client.list() }
    }

    @Test
    fun should_throwUnauthorized_when_scopeIsInsufficient() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(error(403, "insufficientPermissions")))

        assertThrows<SyncTransportException.Unauthorized> { client.list() }
        assertTrue(tokens.invalidated.isEmpty())
    }

    @Test
    fun should_throwRateLimitedWithRetryAfter_when_serverReturns429() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody(error(429, "rateLimitExceeded")),
        )
        server.enqueue(MockResponse().setResponseCode(403).setBody(error(403, "userRateLimitExceeded")))

        val limited = assertThrows<SyncTransportException.RateLimited> { client.list() }
        assertEquals(7_000L, limited.retryAfterMs)
        val userLimited = assertThrows<SyncTransportException.RateLimited> { client.list() }
        assertNull(userLimited.retryAfterMs)
    }

    @Test
    fun should_throwServer_when_serverFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>unavailable</html>"))

        val failure = assertThrows<SyncTransportException.Server> { client.list() }
        assertEquals(503, failure.code)
    }

    @Test
    fun should_throwNetwork_when_serverIsUnreachable() = runTest {
        val unreachable = newClient(tokens)
        server.shutdown()

        assertThrows<SyncTransportException.Network> { unreachable.list() }
    }

    @Test
    fun should_parsePermissionId_when_fetchingAbout() = runTest {
        server.enqueue(
            json("""{"user":{"permissionId":"0123","emailAddress":"a@example.com","displayName":"A","kind":"drive#user"}}"""),
        )

        val account = client.about()

        assertEquals("0123", account.permissionId)
        assertEquals("a@example.com", account.email)
        assertEquals("A", account.displayName)
        val request = server.takeRequest()
        assertEquals("/drive/v3/about", request.requestUrl!!.encodedPath)
        assertEquals("user(permissionId,emailAddress,displayName)", request.requestUrl!!.queryParameter("fields"))
    }

    @Test
    fun should_throwServer_when_aboutHasNoPermissionId() = runTest {
        server.enqueue(json("""{"user":{"displayName":"A"}}"""))

        assertThrows<SyncTransportException.Server> { client.about() }
    }

    @Test
    fun should_setUserAgent_when_usingDefaultHttpClient() = runTest {
        server.enqueue(json("""{"files":[]}"""))
        val withDefaults = DriveAppDataClient(
            http = DriveAppDataClient.defaultHttpClient("1.2.3"),
            tokens = tokens,
            apiBase = server.url("/drive/v3").toString(),
            uploadBase = server.url("/upload/drive/v3").toString(),
        )

        withDefaults.list()

        val request = server.takeRequest()
        assertEquals("Yoin/1.2.3 (gzip)", request.getHeader("User-Agent"))
        assertEquals("/drive/v3/files", request.requestUrl!!.encodedPath)
        assertFalse(request.getHeader("Accept-Encoding").isNullOrEmpty())
    }

    // ---- helpers

    private class FakeTokens(vararg tokens: String) : SyncTokenProvider {
        private val queue = ArrayDeque(tokens.toList())
        private var current = queue.removeFirst()
        val invalidated = mutableListOf<String>()

        override suspend fun token(): String = current

        override suspend fun invalidate(token: String) {
            invalidated += token
            if (token == current && queue.isNotEmpty()) current = queue.removeFirst()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun file(id: String, version: String) =
        """{"id":"$id","name":"$id.json.gz","version":"$version","modifiedTime":"2026-10-04T08:15:30Z","size":"10","appProperties":{}}"""

    private fun error(code: Int, reason: String) =
        """{"error":{"code":$code,"message":"$reason","errors":[{"domain":"global","reason":"$reason","message":"$reason"}]}}"""

    /** (headers, body) of each part, in order. */
    private fun multipartParts(request: RecordedRequest): List<Pair<String, String>> {
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(contentType, contentType.startsWith("multipart/related"))
        val boundary = contentType.substringAfter("boundary=").trim('"')
        val body = request.body.readUtf8()
        return body.split("--$boundary")
            .drop(1)
            .filterNot { it.startsWith("--") }
            .map { part ->
                val trimmed = part.removePrefix("\r\n").removeSuffix("\r\n")
                trimmed.substringBefore("\r\n\r\n") to trimmed.substringAfter("\r\n\r\n")
            }
    }

    private suspend inline fun <reified T : Throwable> assertThrows(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            fail("Expected ${T::class.simpleName} but got $error")
        }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }
}
