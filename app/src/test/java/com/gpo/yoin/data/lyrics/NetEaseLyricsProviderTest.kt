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

class NetEaseLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: NetEaseLyricsProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = NetEaseLyricsProvider(
            client = OkHttpClient.Builder().build(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_hits_cloudsearch_and_reads_ar_first_name() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "result": {
                    "songs": [
                      {"id": 123456, "name": "晴天", "ar": [{"name": "周杰伦"}]}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val match = provider.search("晴天", "周杰伦")

        assertNotNull(match)
        assertEquals("123456", match?.songId)
        assertEquals("晴天", match?.title)
        assertEquals("周杰伦", match?.artist)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/cloudsearch"))
    }

    @Test
    fun fetchLyric_returns_plain_lrc() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "lrc": {"lyric": "[00:01.00]故事的小黄花"},
                  "tlyric": {"lyric": "[00:01.00]the little yellow flower"}
                }
                """.trimIndent(),
            ),
        )

        val lrc = provider.fetchLyric("123")

        assertEquals("[00:01.00]故事的小黄花", lrc)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/lyric/new"))
        assertTrue(req.path!!.contains("id=123"))
    }

    @Test
    fun fetchLyric_converts_yrc_json_to_standard_lrc() = runTest {
        // 逐字 JSON：每行是一个 {"t": ms, "c": [{"tx":"..."}, ...]}，
        // Provider 应该把它合成 [mm:ss.xx]text 的标准 LRC。
        val yrcLine1 = """{"t":1000,"c":[{"tx":"hel"},{"tx":"lo"}]}"""
        val yrcLine2 = """{"t":2500,"c":[{"tx":"world"}]}"""
        val yrcEscaped = "$yrcLine1\\n$yrcLine2".replace("\"", "\\\"")

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"lrc":{"lyric":"$yrcEscaped"}}""",
            ),
        )

        val lrc = provider.fetchLyric("456")

        assertNotNull(lrc)
        assertTrue(lrc!!.contains("[00:01.00]hello"))
        assertTrue(lrc.contains("[00:02.50]world"))
    }

    @Test
    fun fetchLyric_returns_null_on_empty_lrc() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"lrc":{"lyric":""}}""",
            ),
        )

        assertNull(provider.fetchLyric("789"))
    }
}
