package com.gpo.yoin.data.lyrics

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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

class QQLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: QQLyricsProvider

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        provider = QQLyricsProvider(
            client = OkHttpClient.Builder().build(),
            searchUrl = "$base/search",
            playLyricInfoUrl = "$base/play",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    @Test
    fun search_parses_numeric_id_title_and_first_singer() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": {
                    "song": {
                      "list": [
                        {
                          "id": 97773,
                          "mid": "abc123",
                          "title": "Lost Stars",
                          "singer": [{"name": "Adam Levine"}, {"name": "Keira Knightley"}]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val match = provider.search("Lost Stars", "Adam Levine")

        assertNotNull(match)
        assertEquals("97773", match?.songId)
        assertEquals("Lost Stars", match?.title)
        assertEquals("Adam Levine", match?.artist)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.startsWith("/search"))
        assertTrue(req.path!!.contains("w=Lost%20Stars%20Adam%20Levine"))
    }

    @Test
    fun search_returns_empty_when_list_missing() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data": {"song": {}}}"""),
        )

        assertNull(provider.search("nope", "nobody"))
    }

    @Test
    fun search_skips_non_numeric_id() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": {
                    "song": {
                      "list": [
                        {
                          "id": "abc123",
                          "title": "Lost Stars",
                          "singer": [{"name": "Adam Levine"}]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNull(provider.search("Lost Stars", "Adam Levine"))
    }

    @Test
    fun fetchLyric_returns_lyric_field() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "req_1": {
                    "code": 0,
                    "data": {
                      "lyric": "[00:01.00]hello",
                      "trans": "[00:01.00]你好"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val lrc = provider.fetchLyric("97773")

        assertEquals("[00:01.00]hello", lrc)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/play"))
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"songID\":97773"))
        assertTrue(body.contains("\"crypt\":0"))
        assertTrue(body.contains("\"trans\":1"))
        assertTrue(body.contains("\"qrc\":0"))
        assertTrue(body.contains("\"roma\":0"))
        assertFalse(body.contains("songMID"))
    }

    @Test
    fun fetchLyric_decodes_base64_lyric() = runTest {
        val plain = "[00:01.00]hello world"
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"req_1": {"code": 0, "data": {"lyric": "$b64"}}}""",
            ),
        )

        val lrc = provider.fetchLyric("97773")

        assertEquals(plain, lrc)
    }

    @Test
    fun fetchLyric_returns_null_for_hex_encrypted_qrc() = runTest {
        // QRC-only 歌曲：服务端无视 crypt=0 强发 hex 密文，应识别并放弃。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"req_1": {"code": 0, "data": {"lyric": "E5D94A70C91F7022"}}}""",
            ),
        )

        assertNull(provider.fetchLyric("97773"))
    }

    @Test
    fun fetchLyric_rejects_non_numeric_song_id() = runTest {
        assertNull(provider.fetchLyric("abc123"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fetchLyric_returns_null_when_req_code_nonzero() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"req_1": {"code": 24001, "data": {"lyric": "[00:01.00]hello"}}}""",
            ),
        )

        assertNull(provider.fetchLyric("97773"))
    }

    @Test
    fun fetchLyricWithTranslation_returns_trans_field() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "req_1": {
                    "code": 0,
                    "data": {
                      "lyric": "[00:01.00]hello",
                      "trans": "[00:01.00]你好"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val payload = provider.fetchLyricWithTranslation("97773")

        assertEquals("[00:01.00]hello", payload?.lyric)
        assertEquals("[00:01.00]你好", payload?.translatedLyric)
    }
}
