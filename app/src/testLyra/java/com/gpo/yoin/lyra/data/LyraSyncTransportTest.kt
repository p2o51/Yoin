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
            // Intentionally populated: sync transport MUST NOT send Bearer to /api/v1/sync/*.
            adminToken = "should-not-leak-to-sync",
        )
        transport = LyraSyncTransport(authProvider = { auth })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aboutListGenerateDownloadCreateUpdateDeleteRoundTripAndPassesAllAppProperties() = runTest {
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
        assertNull("sync routes must never send admin Bearer", aboutReq.getHeader("Authorization"))

        // 2. generateIds
        server.enqueue(MockResponse().setBody("""{"ids":["lyf_001","lyf_002"]}"""))
        val ids = transport.generateIds(2)
        assertEquals(listOf("lyf_001", "lyf_002"), ids)
        val genReq = server.takeRequest()
        assertEquals("POST", genReq.method)
        assertEquals("/api/v1/sync/files/generate-ids", genReq.requestUrl?.encodedPath)
        assertEquals("2", genReq.requestUrl?.queryParameter("count"))
        assertNull(genReq.getHeader("Authorization"))

        // 3. create (including purge scope + custom properties)
        val payload = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x42)
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "id": "lyf_001",
                      "name": "yoin-purge-123-dev1.json",
                      "version": 1,
                      "modifiedTime": 1700000000000,
                      "size": 5,
                      "appProperties": {"yoinKind": "purge", "deviceId": "dev1", "epoch": "123", "scope": "s-abc123"}
                    }
                    """.trimIndent(),
                ),
        )
        val created = transport.create(
            fileId = "lyf_001",
            name = "yoin-purge-123-dev1.json",
            appProperties = mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_PURGE,
                SyncFileProps.DEVICE_ID to "dev1",
                SyncFileProps.EPOCH to "123",
                SyncFileProps.SCOPE to "s-abc123",
                "targetLocation" to "icloud",
            ),
            mimeType = "application/json",
            bytes = payload,
        )
        assertEquals("lyf_001", created.id)
        assertEquals(1L, created.version)
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/api/v1/sync/files/lyf_001", createReq.requestUrl?.encodedPath)
        assertEquals("yoin-purge-123-dev1.json", createReq.requestUrl?.queryParameter("name"))
        assertEquals(SyncFileProps.KIND_PURGE, createReq.requestUrl?.queryParameter(SyncFileProps.KIND))
        assertEquals("s-abc123", createReq.requestUrl?.queryParameter(SyncFileProps.SCOPE))
        assertEquals("icloud", createReq.requestUrl?.queryParameter("targetLocation"))
        assertNull(createReq.getHeader("Authorization"))
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
        assertNull(updReq.getHeader("Authorization"))

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
    fun mapsConflictUnauthorizedPayloadTooLargeRateLimitAuthThrottleStorageFullAndNetwork() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        val conflict = runCatching {
            transport.create("lyf_dup", "yoin-state-d.json.gz", emptyMap(), "application/gzip", byteArrayOf(1))
        }.exceptionOrNull()
        assertTrue(conflict is SyncTransportException.AlreadyExists)

        server.enqueue(MockResponse().setResponseCode(401))
        val unauth = runCatching { transport.list() }.exceptionOrNull()
        assertTrue(unauth is SyncTransportException.Unauthorized)

        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":"payload too large"}"""))
        val tooLarge = runCatching { transport.list() }.exceptionOrNull() as? SyncTransportException.Server
        assertNotNull(tooLarge)
        assertEquals(413, tooLarge?.code)

        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "12"))
        val rate = runCatching { transport.list() }.exceptionOrNull() as? SyncTransportException.RateLimited
        assertNotNull(rate)
        assertEquals(12_000L, rate?.retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"subsonicCode":40,"error":"too many attempts"}"""))
        val authThrottle = runCatching { transport.list() }.exceptionOrNull()
        assertTrue(authThrottle is SyncTransportException.Unauthorized)

        server.enqueue(MockResponse().setResponseCode(507))
        val full = runCatching { transport.list() }.exceptionOrNull()
        assertTrue(full is SyncTransportException.StorageFull)

        // Network failure when server is unreachable
        val deadTransport = LyraSyncTransport(
            authProvider = {
                LyraServerAuth(serverUrl = "http://127.0.0.1:1/", username = "51", password = "secret")
            },
        )
        val networkErr = runCatching { deadTransport.about() }.exceptionOrNull()
        assertTrue(networkErr is SyncTransportException.Network)
    }

    @Test
    fun createRetriesWithLegacyAppPropertiesWhenOlderServerRejectsExtraKeysWith400() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"unknown appProperty scope"}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"id":"lyf_p1","name":"yoin-purge-1-dev1.json","version":1,"size":3,"appProperties":{"yoinKind":"purge","deviceId":"dev1","epoch":"1"}}""",
                ),
        )

        val created = transport.create(
            fileId = "lyf_p1",
            name = "yoin-purge-1-dev1.json",
            appProperties = mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_PURGE,
                SyncFileProps.DEVICE_ID to "dev1",
                SyncFileProps.EPOCH to "1",
                SyncFileProps.SCOPE to "s-abc",
            ),
            mimeType = "application/json",
            bytes = payload,
        )
        assertEquals("lyf_p1", created.id)

        val firstReq = server.takeRequest()
        assertEquals("s-abc", firstReq.requestUrl?.queryParameter(SyncFileProps.SCOPE))

        val retryReq = server.takeRequest()
        assertNull("Legacy fallback must strip unknown key", retryReq.requestUrl?.queryParameter(SyncFileProps.SCOPE))
        assertEquals(SyncFileProps.KIND_PURGE, retryReq.requestUrl?.queryParameter(SyncFileProps.KIND))
    }
}
