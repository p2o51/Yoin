package com.gpo.yoin.player.sing

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sing status stream (`/api/v1/sing/{id}/status?stream=1`) stays open for the whole
 * separation, 20–90 s. A call timeout inherited from the client it derives from would cut it
 * and drop the provider to 1 s polling (PR #331 review).
 */
class LyraSseClientTimeoutTest {

    @Test
    fun theStatusStreamHasNoCallTimeout_evenWhenItsBaseClientHasOne() {
        val base = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
        val provider = LyraSingStreamProvider(credentialsProvider = { null }, httpClient = base)
        assertEquals(0, provider.sseClient.callTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(45), provider.sseClient.readTimeoutMillis.toLong())
    }

    @Test
    fun aStreamLongerThanTheBaseCallTimeoutIsReadToTheEnd() {
        val server = MockWebServer()
        // Events every 300 ms for ~1.8 s: past the base client's 1 s call timeout.
        val events = (1..6).joinToString("") { "data: {\"progress\":$it}\n\n" }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events)
                .throttleBody(events.length / 6L, 300, TimeUnit.MILLISECONDS),
        )
        server.start()
        try {
            val base = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
            val provider = LyraSingStreamProvider(credentialsProvider = { null }, httpClient = base)
            val body = provider.sseClient.newCall(Request.Builder().url(server.url("/api/v1/sing/x/status?stream=1")).build())
                .execute().use { it.body.string() }
            assertTrue("stream cut short: $body", body.contains("\"progress\":6"))
        } finally {
            server.shutdown()
        }
    }
}
