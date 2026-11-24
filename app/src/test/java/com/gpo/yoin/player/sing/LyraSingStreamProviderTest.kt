package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicAuth
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyraSingStreamProviderTest {
    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun credentials(pathPrefix: String = ""): ServerCredentials = ServerCredentials(
        serverUrl = server.url(pathPrefix).toString().trimEnd('/'),
        username = "demo",
        password = "secret",
    )

    private fun provider(
        creds: ServerCredentials? = credentials(),
        fallback: SingStreamProvider? = null,
        tier: String? = null,
        capabilityCache: LyraSingCapabilityCache? = null,
    ) = LyraSingStreamProvider(
        credentialsProvider = { creds },
        httpClient = httpClient,
        fallback = fallback,
        tier = tier,
        capabilityCache = capabilityCache,
        pollIntervalMs = 10L,
        maxPollAttempts = 5,
    )

    private fun singRequest(
        songId: String = "lt_abc123",
        streamUri: String? = "https://example.invalid/rest/stream.view?id=$songId",
    ) = SingRequest(
        songId = MediaId.subsonic(songId),
        profileId = "profile-1",
        streamUri = streamUri,
    )

    @Test
    fun should_returnReadyImmediately_when_prepareReturns200Ready() = runBlocking {
        val creds = credentials("/lyra")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"ready","stream_url":"/api/v1/sing/lt_abc123/quad.m4a","tier":"quality","song_id":"lt_abc123"}""",
                ),
        )

        val statuses = provider(creds = creds).stream(singRequest()).toList()
        assertEquals(1, statuses.size)
        val ready = statuses.single() as SingStatus.Ready
        val remote = ready.source as SingSource.Remote

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/lyra/api/v1/sing/lt_abc123/prepare", recorded.requestUrl?.encodedPath)
        assertEquals("demo", recorded.requestUrl?.queryParameter("u"))
        val (expectedToken, expectedSalt) = SubsonicAuth.stableToken(creds)
        assertEquals(expectedToken, recorded.requestUrl?.queryParameter("t"))
        assertEquals(expectedSalt, recorded.requestUrl?.queryParameter("s"))
        assertEquals("1.16.1", recorded.requestUrl?.queryParameter("v"))
        assertEquals("Yoin", recorded.requestUrl?.queryParameter("c"))
        assertEquals("json", recorded.requestUrl?.queryParameter("f"))

        assertEquals("/lyra/api/v1/sing/lt_abc123/quad.m4a", remote.uri.encodedPath)
        assertEquals("demo", remote.uri.getQueryParameter("u"))
        assertEquals(expectedToken, remote.uri.getQueryParameter("t"))
        assertEquals(expectedSalt, remote.uri.getQueryParameter("s"))
        assertEquals("quality", remote.uri.getQueryParameter("tier"))
    }

    @Test
    fun should_streamSseStagesUntilReady_when_prepareReturns202() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"processing","state":"queued","eta_s":25,"tier":"quality","song_id":"lt_abc123"}"""),
        )
        val sseBody = buildString {
            append(": ping\n\n")
            append("event: sing\n")
            append("""data: {"status":"separating","stage":"decoding","tier":"quality"}""" + "\n\n")
            append("event: sing\n")
            append("""data: {"status":"separating","stage":"separating","tier":"quality"}""" + "\n\n")
            append("event: sing\n")
            append("""data: {"status":"encoding","stage":"encoding","tier":"quality"}""" + "\n\n")
            append("event: sing\n")
            append("""data: {"status":"ready","stream_url":"/api/v1/sing/lt_abc123/quad.m4a","tier":"quality"}""" + "\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(sseBody),
        )

        val statuses = provider().stream(singRequest()).toList()
        assertEquals(
            listOf(
                SingStatus.Preparing(null, SingStatus.Preparing.Stage.Reading),
                SingStatus.Preparing(null, SingStatus.Preparing.Stage.Reading),
                SingStatus.Preparing(null, SingStatus.Preparing.Stage.Working),
                SingStatus.Preparing(null, SingStatus.Preparing.Stage.Saving),
            ),
            statuses.dropLast(1),
        )
        assertTrue(statuses.last() is SingStatus.Ready)

        val prepareReq = server.takeRequest()
        assertEquals("POST", prepareReq.method)
        val sseReq = server.takeRequest()
        assertEquals("GET", sseReq.method)
        assertEquals("/api/v1/sing/lt_abc123/status", sseReq.requestUrl?.encodedPath)
        assertEquals("1", sseReq.requestUrl?.queryParameter("stream"))
        assertEquals("text/event-stream", sseReq.getHeader("Accept"))
        assertNotNull(sseReq.requestUrl?.queryParameter("t"))
    }

    @Test
    fun should_useAvailableFastTierImmediately_when_prepare202IncludesAvailable() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"processing","state":"queued","tier":"quality","available":{"status":"ready","tier":"fast","stream_url":"/api/v1/sing/lt_abc123/quad.m4a"}}""",
                ),
        )

        val statuses = provider().stream(singRequest()).toList()
        assertEquals(1, statuses.size)
        val remote = (statuses.single() as SingStatus.Ready).source as SingSource.Remote
        assertEquals("fast", remote.uri.getQueryParameter("tier"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_fallbackToJsonPolling_when_sseClosesEarly() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"processing","state":"queued"}"""),
        )
        // SSE ends prematurely before reaching ready.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"status":"separating","stage":"separating"}""" + "\n\n"),
        )
        // JSON poll 1: still encoding.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"encoding","stage":"encoding"}"""),
        )
        // JSON poll 2: ready.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ready","stream_url":"/api/v1/sing/lt_abc123/quad.m4a"}"""),
        )

        val statuses = provider().stream(singRequest()).toList()
        assertTrue(statuses.last() is SingStatus.Ready)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun should_cancelActiveSseCall_when_providerCancelInvoked() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"processing","state":"queued"}"""),
        )
        // Hang the SSE response body for 10 seconds to prove cancel interrupts promptly.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setBody("""data: {"status":"ready","stream_url":"/api/v1/sing/lt_abc123/quad.m4a"}""" + "\n\n"),
        )

        val p = provider()
        val req = singRequest()
        val gotPreparing = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            p.stream(req).collect { status ->
                if (status is SingStatus.Preparing) gotPreparing.complete(Unit)
            }
        }

        withTimeout(2_000L) {
            gotPreparing.await()
            p.cancel(req)
            job.cancel()
            job.join()
        }
    }

    @Test
    fun should_reportUnavailableOrFailed_when_404OrServerErrorWithoutFallback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val notFound = provider().stream(singRequest()).toList()
        assertEquals(listOf(SingStatus.Unavailable(SingUnavailableReason.NoSource)), notFound)

        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"processing","state":"queued"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"status":"failed","error":"missing_dependency"}""" + "\n\n"),
        )
        val failed = provider().stream(singRequest()).toList()
        assertEquals(SingStatus.Failed(SingFailure.Remote, "missing_dependency"), failed.last())
    }

    @Test
    fun should_fallbackToOnDeviceAndMarkOffline_when_lyraReturns503() = runBlocking {
        val creds = credentials()
        var now = 1_000L
        val cache = LyraSingCapabilityCache(httpClient, clockMs = { now })
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"sing":{"enabled":true,"version":1}}"""),
        )
        assertTrue(cache.refresh(creds))
        assertTrue(cache.isSingEnabled(creds))

        val fallbackReady = SingStatus.Ready(SingSource.LocalFile(File("local.m4a")))
        val fakeFallback = object : SingStreamProvider {
            override fun stream(request: SingRequest): Flow<SingStatus> = flowOf(fallbackReady)
        }
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val statuses = provider(creds = creds, fallback = fakeFallback, capabilityCache = cache)
            .stream(singRequest())
            .toList()
        assertEquals(listOf(fallbackReady), statuses)
        assertFalse(cache.isSingEnabled(creds))
    }

    @Test
    fun should_probeCapabilitiesAndCacheResult_when_lyraOrNavidrome() = runBlocking {
        var now = 10_000L
        val cache = LyraSingCapabilityCache(httpClient, clockMs = { now })
        val creds = credentials()

        // Plain Navidrome returns 404.
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(cache.refresh(creds))
        assertFalse(cache.isSingEnabled(creds))
        // Cached hit within negative TTL makes no new HTTP call.
        assertFalse(cache.refresh(creds))
        assertEquals(1, server.requestCount)

        // Advance past negative TTL; server now reports Sing enabled.
        now += LyraSingCapabilityCache.DEFAULT_NEGATIVE_TTL_MS + 1L
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"sing":{"enabled":true,"version":1,"default_tier":"quality"}}"""),
        )
        assertTrue(cache.refresh(creds))
        assertTrue(cache.isSingEnabled(creds))
        assertEquals(2, server.requestCount)
    }
}
