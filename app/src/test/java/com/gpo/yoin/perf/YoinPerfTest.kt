package com.gpo.yoin.perf

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class YoinPerfTest {
    private val lines = mutableListOf<String>()
    private lateinit var originalSink: (String) -> Unit
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        originalSink = YoinPerf.sink
        YoinPerf.sink = { line -> synchronized(lines) { lines += line } }
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        YoinPerf.sink = originalSink
        server.shutdown()
    }

    @Test
    fun should_dropNullFieldsAndJoinWhitespace_when_formatting() {
        val line = YoinPerf.format(
            "detail.load",
            arrayOf("kind" to "album", "id" to "subsonic:a b", "joined" to null, "ms" to 12L),
            t = 345L
        )
        assertEquals("detail.load kind=album id=subsonic:a_b ms=12 t=345", line)
    }

    @Test
    fun should_templateIdSegments_when_pathCarriesIds() {
        assertEquals("/v1/albums/{id}", templatePath(listOf("v1", "albums", "4aawyAB9vmqN3uQ7FjRGTy")))
        assertEquals("/v1/me/player/currently-playing", templatePath(listOf("v1", "me", "player", "currently-playing")))
        assertEquals("/v1/catalog/us/albums/{id}", templatePath(listOf("v1", "catalog", "us", "albums", "1440857781")))
        assertEquals("/v1/me/library/albums/{id}", templatePath(listOf("v1", "me", "library", "albums", "l.AbCdEfG")))
        assertEquals("/rest/getAlbumList2.view", templatePath(listOf("rest", "getAlbumList2.view")))
        assertEquals("/", templatePath(listOf("")))
    }

    @Test
    fun should_logHostPathAndCode_withoutQuery_when_callSucceeds() {
        // Release unit tests build YoinPerf disabled: nothing is logged there by design.
        assumeTrue(YoinPerf.enabled)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = OkHttpClient.Builder().addInterceptor(YoinPerfHttpInterceptor).build()
        val url = server.url("/rest/getAlbum.view").newBuilder()
            .addQueryParameter("u", "alice")
            .addQueryParameter("t", "secret-token")
            .addQueryParameter("s", "salt123")
            .addQueryParameter("id", "42")
            .build()

        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        val http = synchronized(lines) { lines.single { it.startsWith("http ") } }
        assertTrue(http, http.contains("path=/rest/getAlbum.view"))
        assertTrue(http, http.contains("code=200"))
        assertTrue(http, http.contains("host=${url.host}"))
        listOf("alice", "secret-token", "salt123", "?", "u=").forEach { leaked ->
            assertFalse("leaked $leaked: $http", http.contains(leaked))
        }
    }
}
