package com.gpo.yoin.data.remote

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production clients of [YoinNetworkCore] (ported from the perf review's failing
 * experiments): one connection pool, a request queue per traffic class, so a stream
 * never waits behind a cover burst.
 */
class YoinNetworkCoreTest {

    private val noop = object : Callback {
        override fun onFailure(call: Call, e: IOException) = Unit
        override fun onResponse(call: Call, response: Response) = response.close()
    }

    @Test
    fun clientsShareOnePoolButNotOneQueue() {
        val api = YoinNetworkCore.newApiBuilder().build()
        val clients = listOf(api, YoinNetworkCore.mediaClient, YoinNetworkCore.imageClient, YoinNetworkCore.apiClient)
        clients.forEach { assertSame(YoinNetworkCore.connectionPool, it.connectionPool) }

        assertNotSame(YoinNetworkCore.mediaClient.dispatcher, YoinNetworkCore.imageClient.dispatcher)
        assertNotSame(YoinNetworkCore.mediaClient.dispatcher, api.dispatcher)
        assertNotSame(YoinNetworkCore.imageClient.dispatcher, api.dispatcher)
        assertEquals(YoinNetworkCore.IMAGE_MAX_REQUESTS_PER_HOST, YoinNetworkCore.imageClient.dispatcher.maxRequestsPerHost)
        assertEquals(YoinNetworkCore.MEDIA_MAX_REQUESTS_PER_HOST, YoinNetworkCore.mediaClient.dispatcher.maxRequestsPerHost)
        // OkHttp's own default per host for API clients: what each provider client had alone.
        assertEquals(OkHttpClient().dispatcher.maxRequestsPerHost, api.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun timeoutsStayOkHttpsDefaults_whereCallersSetNone() {
        // Playback, downloads and Coil used `OkHttpClient()`: 10 s connect / read / write.
        val defaults = OkHttpClient()
        listOf(YoinNetworkCore.mediaClient, YoinNetworkCore.imageClient, YoinNetworkCore.newApiBuilder().build()).forEach {
            assertEquals(defaults.connectTimeoutMillis, it.connectTimeoutMillis)
            assertEquals(defaults.readTimeoutMillis, it.readTimeoutMillis)
            assertEquals(defaults.writeTimeoutMillis, it.writeTimeoutMillis)
            assertEquals(defaults.callTimeoutMillis, it.callTimeoutMillis)
        }
    }

    /** Review A: a tablet Library grid's 48 covers (600 ms each to resize), then the user taps play. */
    @Test
    fun streamResponseIsNotQueuedBehindA48CoverBurst() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("getCoverArt")) {
                    MockResponse().setHeadersDelay(600, TimeUnit.MILLISECONDS).setBody("x".repeat(2048))
                } else {
                    MockResponse().setBody("audio")
                }
        }
        server.start()
        try {
            repeat(48) { i ->
                YoinNetworkCore.imageClient.newCall(
                    Request.Builder().url(server.url("/rest/getCoverArt.view?id=$i&size=480")).build(),
                ).enqueue(noop)
            }
            Thread.sleep(20)
            val headersAt = AtomicLong(-1)
            val latch = CountDownLatch(1)
            val start = System.nanoTime()
            // Media3's OkHttpDataSource enqueue()s too.
            YoinNetworkCore.mediaClient.newCall(Request.Builder().url(server.url("/rest/stream.view?id=1")).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = latch.countDown()
                    override fun onResponse(call: Call, response: Response) {
                        headersAt.set((System.nanoTime() - start) / 1_000_000)
                        response.close()
                        latch.countDown()
                    }
                })
            latch.await(30, TimeUnit.SECONDS)
            assertTrue("stream start delayed by the cover burst: ${headersAt.get()} ms", headersAt.get() in 0 until 500)
        } finally {
            server.shutdown()
        }
    }

    /** Review C: 20 covers at 1.5 s over a slow tunnel; the stream request must reach the server at once. */
    @Test
    fun streamRequestReachesTheServerWhileCoversLoad() {
        val server = MockWebServer()
        val streamSeenAt = AtomicLong(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/rest/stream.view")) {
                    streamSeenAt.set(System.nanoTime())
                    MockResponse().setBody("audio")
                } else {
                    MockResponse().setBody("img").setHeadersDelay(1500, TimeUnit.MILLISECONDS)
                }
        }
        server.start()
        try {
            val done = CountDownLatch(21)
            val counting = object : Callback {
                override fun onFailure(call: Call, e: IOException) = done.countDown()
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    done.countDown()
                }
            }
            repeat(20) { i ->
                YoinNetworkCore.imageClient.newCall(
                    Request.Builder().url(server.url("/rest/getCoverArt.view?id=c$i")).build(),
                ).enqueue(counting)
            }
            Thread.sleep(100)
            val start = System.nanoTime()
            YoinNetworkCore.mediaClient.newCall(Request.Builder().url(server.url("/rest/stream.view?id=s")).build())
                .enqueue(counting)
            done.await(10, TimeUnit.SECONDS)
            val ms = (streamSeenAt.get() - start) / 1_000_000
            assertTrue("stream request queued behind cover art for $ms ms", ms in 0 until 500)
        } finally {
            server.shutdown()
        }
    }

    /**
     * Review A, checked and kept: a stream the player stopped reading (buffer full) on a
     * shared HTTP/2 connection does not starve an API call to the same host.
     */
    @Test
    fun stalledStreamOnASharedH2ConnectionDoesNotStarveApiCalls() {
        val server = MockWebServer()
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/rest/stream")) {
                    MockResponse().setBody(Buffer().write(ByteArray(8 * 1024 * 1024)))
                } else {
                    MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}""")
                }
        }
        server.start()
        try {
            val media = YoinNetworkCore.mediaClient.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
            val api = YoinNetworkCore.newApiBuilder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val stream = media.newCall(Request.Builder().url(server.url("/rest/stream.view?id=1")).build()).execute()
            stream.body.source().require(64 * 1024)
            Thread.sleep(1_000)
            val result = runCatching {
                api.newCall(Request.Builder().url(server.url("/rest/getStarred2.view")).build()).execute()
                    .use { it.body.string() }
            }
            stream.close()
            assertTrue("API call starved by a stalled stream: ${result.exceptionOrNull()}", result.isSuccess)
        } finally {
            server.shutdown()
        }
    }
}
