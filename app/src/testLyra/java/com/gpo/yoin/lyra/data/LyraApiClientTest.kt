package com.gpo.yoin.lyra.data

import kotlinx.coroutines.test.runTest
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
    fun capabilitiesUsesSubsonicV1AuthAndParsesModules() = runTest {
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

        val resp = client.capabilities(auth())
        val req = server.takeRequest()
        assertEquals("/api/v1/capabilities", req.requestUrl?.encodedPath)
        assertEquals("51", req.requestUrl?.queryParameter("u"))
        assertNotNull(req.requestUrl?.queryParameter("t"))
        assertNotNull(req.requestUrl?.queryParameter("s"))
        assertNull(req.getHeader("Authorization"))

        assertEquals("0.4.0", resp.lyraVersion)
        assertEquals(2, resp.protocolVersion)
        assertTrue(resp.isEnabled("sources"))
        assertTrue(resp.isEnabled("native_sync"))
        assertFalse(resp.isEnabled("recommendations"))
    }

    @Test
    fun sourcesSearchAndDownloadUseAnonymizedSourceId() = runTest {
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

        val sources = client.listSources(auth())
        assertEquals(1, sources.size)
        assertEquals("src_a1b2c3d4e5", sources.first().sourceId)
        assertEquals("音源 A", sources.first().displayName)

        val search = client.searchSource(auth(), "src_a1b2c3d4e5", "晴る", type = "song")
        assertEquals(1, search.results.size)
        assertEquals("m001", search.results.first().mid)

        val queued = client.enqueueSourceDownload(
            auth(),
            "src_a1b2c3d4e5",
            LyraDownloadRequest(mid = "m001", quality = "flac", title = "晴る", artist = "ヨルシカ"),
        )
        assertEquals(42L, queued.jobId)
        assertEquals("queued", queued.status)

        server.takeRequest()
        val searchReq = server.takeRequest()
        assertEquals("/api/v1/sources/src_a1b2c3d4e5/search", searchReq.requestUrl?.encodedPath)
        assertEquals("晴る", searchReq.requestUrl?.queryParameter("q"))

        val dlReq = server.takeRequest()
        assertEquals("POST", dlReq.method)
        assertEquals("/api/v1/sources/src_a1b2c3d4e5/download", dlReq.requestUrl?.encodedPath)
        assertTrue(dlReq.body.readUtf8().contains(""""mid":"m001""""))
    }

    @Test
    fun jobsAndSseStreamParseJobUpdatesWithResumeHeader() = runTest {
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
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    "retry: 3000\n\n" +
                        "id: 19\nevent: job\n" +
                        "data: {\"id\":19,\"topic\":\"job\",\"kind\":\"download\",\"entity_id\":\"7\",\"payload\":{\"id\":7,\"status\":\"done\"}}\n\n",
                ),
        )

        val jobs = client.listJobs(auth(adminToken = "adm-1"))
        assertEquals(1, jobs.size)
        assertTrue(jobs.first().isActive)
        assertEquals("夜に駆ける — YOASOBI", jobs.first().displayTitle())

        val events = mutableListOf<LyraEvent>()
        client.streamEvents(auth(), ticket = "tkt-1", lastEventId = 18L) { events += it }
        assertEquals(1, events.size)
        assertEquals(19L, events.first().id)
        assertEquals("job", events.first().topic)

        val jobsReq = server.takeRequest()
        assertEquals("Bearer adm-1", jobsReq.getHeader("Authorization"))

        val sseReq = server.takeRequest()
        assertEquals("tkt-1", sseReq.requestUrl?.queryParameter("ticket"))
        assertEquals("18", sseReq.getHeader("Last-Event-ID"))
    }
}
