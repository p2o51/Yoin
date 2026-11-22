package com.gpo.yoin.lyra.data

import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.data.sync.SyncTransportException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LyraSyncTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: LyraSyncTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val auth = LyraServerAuth(
            serverUrl = server.url("/").toString(),
            username = "51",
            password = "secret",
        )
        transport = LyraSyncTransport(authProvider = { auth })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aboutListGenerateDownloadCreateUpdateDeleteRoundTrip() = runTest {
        // 1. about
        server.enqueue(
            MockResponse().setBody(
                """{"permissionId":"lyra:vault-01","email":"51@lyra.local","displayName":"Lyra"}""",
            ),
        )
        val about = transport.about()
        assertEquals("lyra:vault-01", about.permissionId)
        assertEquals("51@lyra.local", about.email)
        assertEquals("Lyra", about.displayName)
        val aboutReq = server.takeRequest()
        assertEquals("/api/v1/sync/about", aboutReq.requestUrl?.encodedPath)
        assertEquals("51", aboutReq.requestUrl?.queryParameter("u"))
        assertNotNull(aboutReq.requestUrl?.queryParameter("t"))

        // 2. generateIds
        server.enqueue(MockResponse().setBody("""{"ids":["lyf_001","lyf_002"]}"""))
        val ids = transport.generateIds(2)
        assertEquals(listOf("lyf_001", "lyf_002"), ids)
        val genReq = server.takeRequest()
        assertEquals("POST", genReq.method)
        assertEquals("/api/v1/sync/files/generate-ids", genReq.requestUrl?.encodedPath)
        assertEquals("2", genReq.requestUrl?.queryParameter("count"))

        // 3. create
        val payload = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x42)
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "id": "lyf_001",
                      "name": "yoin-state-dev1.json.gz",
                      "version": 1,
                      "modifiedTime": 1700000000000,
                      "size": 5,
                      "appProperties": {"yoinKind": "state", "deviceId": "dev1", "schema": "1", "epoch": "0"}
                    }
                    """.trimIndent(),
                ),
        )
        val created = transport.create(
            fileId = "lyf_001",
            name = "yoin-state-dev1.json.gz",
            appProperties = mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_STATE,
                SyncFileProps.DEVICE_ID to "dev1",
                SyncFileProps.SCHEMA to "1",
                SyncFileProps.EPOCH to "0",
            ),
            mimeType = "application/gzip",
            bytes = payload,
        )
        assertEquals("lyf_001", created.id)
        assertEquals(1L, created.version)
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/api/v1/sync/files/lyf_001", createReq.requestUrl?.encodedPath)
        assertEquals("yoin-state-dev1.json.gz", createReq.requestUrl?.queryParameter("name"))
        assertEquals("state", createReq.requestUrl?.queryParameter(SyncFileProps.KIND))
        assertArrayEquals(payload, createReq.body.readByteArray())

        // 4. list
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "files": [
                    {
                      "id": "lyf_001",
                      "name": "yoin-state-dev1.json.gz",
                      "version": 1,
                      "modifiedTime": 1700000000000,
                      "size": 5,
                      "appProperties": {"yoinKind": "state", "deviceId": "dev1"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val listed = transport.list()
        assertEquals(1, listed.size)
        assertEquals("lyf_001", listed.first().id)
        server.takeRequest()

        // 5. download (200 + 404)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        assertArrayEquals(payload, transport.download("lyf_001"))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(transport.download("lyf_missing"))
        server.takeRequest()

        // 6. update (200 + 404)
        server.enqueue(
            MockResponse().setBody(
                """{"id":"lyf_001","name":"yoin-state-dev1.json.gz","version":2,"size":5,"appProperties":{}}""",
            ),
        )
        val updated = transport.update("lyf_001", payload)
        assertEquals(2L, updated?.version)
        val updReq = server.takeRequest()
        assertEquals("PUT", updReq.method)
        assertEquals("application/gzip", updReq.getHeader("Content-Type")?.substringBefore(';'))

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(transport.update("lyf_missing", payload))
        server.takeRequest()

        // 7. delete (204 + 404 both true)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(transport.delete("lyf_001"))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(transport.delete("lyf_missing"))
        server.takeRequest()
    }

    @Test
    fun mapsConflictUnauthorizedRateLimitAndStorageFull() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        val conflict = runCatching {
            transport.create("lyf_dup", "yoin-state-d.json.gz", emptyMap(), "application/gzip", byteArrayOf(1))
        }.exceptionOrNull()
        assertTrue(conflict is SyncTransportException.AlreadyExists)

        server.enqueue(MockResponse().setResponseCode(401))
        val unauth = runCatching { transport.list() }.exceptionOrNull()
        assertTrue(unauth is SyncTransportException.Unauthorized)

        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "12"))
        val rate = runCatching { transport.list() }.exceptionOrNull() as? SyncTransportException.RateLimited
        assertNotNull(rate)
        assertEquals(12_000L, rate?.retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(507))
        val full = runCatching { transport.list() }.exceptionOrNull()
        assertTrue(full is SyncTransportException.StorageFull)
    }
}
