package com.gpo.yoin.data.lyrics

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrclibLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: LrclibLyricsProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = LrclibLyricsProvider(
            client = OkHttpClient.Builder().build(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_returns_first_match() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"id":123,"trackName":"Paranoid","artistName":"Black Sabbath"},
                  {"id":456,"trackName":"Paranoid (Remix)","artistName":"Black Sabbath"}
                ]
                """.trimIndent(),
            ),
        )

        val match = provider.search("Paranoid", "Black Sabbath")

        assertNotNull(match)
        assertEquals("123", match?.songId)
        assertEquals("Paranoid", match?.title)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/api/search"))
        assertTrue(recorded.path!!.contains("track_name=Paranoid"))
        // Space may be encoded as '+' or '%20' depending on OkHttp's encoder —
        // we only care that Black + Sabbath ended up in the query somehow.
        assertTrue(recorded.path!!.contains("artist_name=Black"))
        assertTrue(recorded.path!!.contains("Sabbath"))
    }

    @Test
    fun search_returns_null_on_empty_result() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertNull(provider.search("nope", "nobody"))
    }

    @Test
    fun fetch_prefers_synced_over_plain() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": 42,
                  "syncedLyrics": "[00:01.00]synced line",
                  "plainLyrics": "plain line"
                }
                """.trimIndent(),
            ),
        )

        val lrc = provider.fetchLyric("42")

        assertEquals("[00:01.00]synced line", lrc)
        assertTrue(server.takeRequest().path!!.endsWith("/api/get/42"))
    }

    @Test
    fun fetch_falls_back_to_plain_when_no_synced() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":42,"syncedLyrics":null,"plainLyrics":"just plain"}""",
            ),
        )

        assertEquals("just plain", provider.fetchLyric("42"))
    }

    @Test
    fun fetch_returns_null_on_404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":404}"""))

        assertNull(provider.fetchLyric("missing"))
    }

    @Test
    fun getLyric_chains_search_and_fetch() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":7,"trackName":"x","artistName":"y"}]""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"syncedLyrics":"[00:00.00]hi"}""",
            ),
        )

        val lrc = provider.getLyric("x", "y")
        assertEquals("[00:00.00]hi", lrc)
    }
}
