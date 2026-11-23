package com.gpo.yoin.lyra.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LyraApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LyraApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LyraApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun auth(adminToken: String? = null) = LyraServerAuth(
        serverUrl = server.url("/").toString(),
        username = "51",
        password = "secret",
        adminToken = adminToken,
    )

    @Test
    fun capabilitiesUsesSubsonicV1AuthAndNeverSendsAdminBearer() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "lyra_version": "0.4.0",
                  "protocol_version": 2,
                  "capabilities": {
                    "sources": {"enabled": true, "version": 1, "count": 2},
                    "inbox": {"enabled": true, "version": 1},
                    "native_sync": {"enabled": true, "version": 1},
                    "recommendations": {"enabled": false, "version": 1}
                  }
                }
                """.trimIndent(),
            ),
        )

        val resp = client.capabilities(auth(adminToken = "adm-secret"))
        val req = server.takeRequest()
        assertEquals("/api/v1/capabilities", req.requestUrl?.encodedPath)
        assertEquals("51", req.requestUrl?.queryParameter("u"))
        assertNotNull(req.requestUrl?.queryParameter("t"))
        assertNotNull(req.requestUrl?.queryParameter("s"))
        assertNull("v1Auth routes must never send admin Bearer token", req.getHeader("Authorization"))

        assertEquals("0.4.0", resp.lyraVersion)
        assertEquals(2, resp.protocolVersion)
        assertTrue(resp.isEnabled("sources"))
        assertTrue(resp.isEnabled("native_sync"))
        assertFalse(resp.isEnabled("recommendations"))
    }

    @Test
    fun sourcesSearchAndDownloadUseAnonymizedSourceIdWithoutBearer() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "sources": [
                    {
                      "source_id": "src_a1b2c3d4e5",
                      "display_name": "音源 A",
                      "default_target": "library",
                      "qualities": ["flac", "mp3"],
                      "default_quality": "flac"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "type": "song",
                  "results": [
                    {
                      "type": "song",
                      "mid": "m001",
                      "title": "晴る",
                      "artist": "ヨルシカ",
                      "album": "晴る",
                      "duration_s": 270.0
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("""{"job_id": 42, "status": "queued", "target": "library"}"""),
        )

        val withAdmin = auth(adminToken = "adm-secret")
        val sources = client.listSources(withAdmin)
        assertEquals(1, sources.size)
        assertEquals("src_a1b2c3d4e5", sources.first().sourceId)
        assertEquals("音源 A", sources.first().displayName)

        val search = client.searchSource(withAdmin, "src_a1b2c3d4e5", "晴る", type = "song")
        assertEquals(1, search.results.size)
        assertEquals("m001", search.results.first().mid)

        val queued = client.enqueueSourceDownload(
            withAdmin,
            "src_a1b2c3d4e5",
            LyraDownloadRequest(mid = "m001", quality = "flac", title = "晴る", artist = "ヨルシカ"),
        )
        assertEquals(42L, queued.jobId)
        assertEquals("queued", queued.status)

        val srcReq = server.takeRequest()
        assertNull(srcReq.getHeader("Authorization"))
        val searchReq = server.takeRequest()
        assertEquals("/api/v1/sources/src_a1b2c3d4e5/search", searchReq.requestUrl?.encodedPath)
        assertEquals("晴る", searchReq.requestUrl?.queryParameter("q"))
        assertNull(searchReq.getHeader("Authorization"))

        val dlReq = server.takeRequest()
        assertEquals("POST", dlReq.method)
        assertEquals("/api/v1/sources/src_a1b2c3d4e5/download", dlReq.requestUrl?.encodedPath)
        assertNull(dlReq.getHeader("Authorization"))
        assertTrue(dlReq.body.readUtf8().contains(""""mid":"m001""""))
    }

    @Test
    fun jobsAndEventsTicketUseSubsonicAuthOnlyWhileAdminRoutesSendBearer() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "jobs": [
                    {
                      "id": 7,
                      "kind": "download",
                      "status": "running",
                      "payload": {"title": "夜に駆ける", "artist": "YOASOBI"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"ticket":"tkt-1","scope":"yoin","expires_in":60}"""))
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    "retry: 3000\n\n" +
                        "id: 19\nevent: job\n" +
                        "data: {\"id\":19,\"topic\":\"job\",\"kind\":\"download\",\"entity_id\":\"7\",\"payload\":{\"id\":7,\"status\":\"done\"}}\n\n",
                ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"pulls":[{"id":"p1","manifest_url":"https://peer/s/abc/manifest.json","target":"library","state":"running"}]}""",
            ),
        )

        val withAdmin = auth(adminToken = "adm-1")
        val jobs = client.listJobs(withAdmin, kind = "download")
        assertEquals(1, jobs.size)
        assertTrue(jobs.first().isActive)
        assertEquals("夜に駆ける — YOASOBI", jobs.first().displayTitle())

        val ticket = client.issueEventsTicket(withAdmin)
        assertEquals("tkt-1", ticket.ticket)

        val events = mutableListOf<LyraEvent>()
        client.streamEvents(withAdmin, ticket = "tkt-1", lastEventId = 18L) { events += it }
        assertEquals(1, events.size)
        assertEquals(19L, events.first().id)
        assertEquals("job", events.first().topic)

        val pulls = client.listPulls(withAdmin)
        assertEquals(1, pulls.size)
        assertEquals("running", pulls.first().state)

        val jobsReq = server.takeRequest()
        assertNull("listJobs must not send admin Bearer", jobsReq.getHeader("Authorization"))
        assertEquals("download", jobsReq.requestUrl?.queryParameter("kind"))
        assertEquals("51", jobsReq.requestUrl?.queryParameter("u"))

        val ticketReq = server.takeRequest()
        assertNull("events/ticket must not send admin Bearer", ticketReq.getHeader("Authorization"))
        assertEquals("51", ticketReq.requestUrl?.queryParameter("u"))

        val sseReq = server.takeRequest()
        assertEquals("tkt-1", sseReq.requestUrl?.queryParameter("ticket"))
        assertEquals("18", sseReq.getHeader("Last-Event-ID"))
        assertNull(sseReq.getHeader("Authorization"))

        val pullsReq = server.takeRequest()
        assertEquals("Bearer adm-1", pullsReq.getHeader("Authorization"))
        assertNull("admin route must not append Subsonic u", pullsReq.requestUrl?.queryParameter("u"))
    }

    @Test
    fun uploadInboxPartsStreamsMultipartAndParsesPartialFailures() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "items": [{"id": "ib_1", "origin_kind": "upload", "file_path": "ok.flac", "state": "ready"}],
                      "failed": [{"filename": "bad.bin", "error": "unsupported audio format"}]
                    }
                    """.trimIndent(),
                ),
        )

        val payload = "FLAC_DATA".toByteArray()
        val resp = client.uploadInboxParts(
            auth = auth(adminToken = "adm-9"),
            parts = listOf(
                LyraUploadPart(
                    filename = "ok.flac",
                    contentLength = payload.size.toLong(),
                    openStream = { ByteArrayInputStream(payload) },
                ),
            ),
        )

        assertEquals(1, resp.items.size)
        assertEquals(1, resp.failed.size)
        assertEquals("bad.bin", resp.failed.first().displayName)
        assertEquals("unsupported audio format", resp.failed.first().error)

        val req = server.takeRequest()
        assertEquals("Bearer adm-9", req.getHeader("Authorization"))
        val bodyText = req.body.readUtf8()
        assertTrue(bodyText.contains("filename=\"ok.flac\""))
        assertTrue(bodyText.contains("FLAC_DATA"))
    }

    @Test
    fun streamEventsCancelsPromptlyWhenStreamStalls() = runBlocking {
        // Server holds headers/body open for 15 seconds (simulating an idle SSE heartbeat gap).
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody("retry: 3000\n\n: keepalive\n\n")
                .throttleBody(1, 15, TimeUnit.SECONDS),
        )

        val job = launch(Dispatchers.IO) {
            client.streamEvents(auth(), ticket = "tkt-slow") {}
        }
        delay(200L)
        val startedCancelMs = System.currentTimeMillis()
        job.cancel()
        withTimeout(3_000L) { job.join() }
        val elapsedMs = System.currentTimeMillis() - startedCancelMs
        assertTrue("Expected SSE cancellation under 2s, took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun uploadInboxPartsWrapsSecurityExceptionAsIoExceptionWithoutCrashing() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"items":[]}"""))
        val err = runCatching {
            client.uploadInboxParts(
                auth = auth(adminToken = "adm-9"),
                parts = listOf(
                    LyraUploadPart(
                        filename = "revoked.flac",
                        contentLength = 1234L,
                        openStream = { throw SecurityException("URI permission expired") },
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue("Expected IOException wrapper, got $err", err is IOException)
    }

    @Test
    fun validateAdminTokenFallsBackAcrossAdminEndpointsWhenInboxIs404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"inbox disabled"}"""))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))

        client.validateAdminToken(auth(adminToken = "adm-ok"))
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("/api/v1/inbox", req1.requestUrl?.encodedPath)
        assertEquals("/api/v1/later", req2.requestUrl?.encodedPath)
        assertEquals("Bearer adm-ok", req2.getHeader("Authorization"))
    }
}
