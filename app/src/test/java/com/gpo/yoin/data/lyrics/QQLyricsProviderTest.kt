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

class QQLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: QQLyricsProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        provider = QQLyricsProvider(
            client = OkHttpClient.Builder().build(),
            searchUrl = "$base/search",
            primaryLyricUrl = "$base/primary",
            backupLyricUrl = "$base/backup",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_parses_mid_title_and_first_singer() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "req": {
                    "data": {
                      "body": {
                        "song": {
                          "list": [
                            {
                              "mid": "abc123",
                              "title": "Lost Stars",
                              "singer": [{"name": "Adam Levine"}, {"name": "Keira Knightley"}]
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val match = provider.search("Lost Stars", "Adam Levine")

        assertNotNull(match)
        assertEquals("abc123", match?.songId)
        assertEquals("Lost Stars", match?.title)
        assertEquals("Adam Levine", match?.artist)
        assertTrue(server.takeRequest().path!!.endsWith("/search"))
    }

    @Test
    fun search_returns_empty_when_list_missing() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"req": {"data": {"body": {"song": {}}}}}""",
            ),
        )

        assertNull(provider.search("nope", "nobody"))
    }

    @Test
    fun fetchLyric_returns_lyric_field() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"lyric":"[00:01.00]hello","trans":"[00:01.00]你好"}""",
            ),
        )

        val lrc = provider.fetchLyric("abc123")

        assertEquals("[00:01.00]hello", lrc)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/primary"))
        assertTrue(req.path!!.contains("songmid=abc123"))
        assertTrue(req.path!!.contains("nobase64=1"))
    }

    @Test
    fun fetchLyric_flips_to_backup_on_403() = runTest {
        // 1st call: primary → 403 triggers domain flip, returns null
        server.enqueue(MockResponse().setResponseCode(403))
        val first = provider.fetchLyric("abc")
        assertNull(first)
        assertTrue(server.takeRequest().path!!.startsWith("/primary"))

        // 2nd call: now uses backup domain
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"lyric":"[00:00.00]ok"}"""),
        )
        val second = provider.fetchLyric("abc")
        assertEquals("[00:00.00]ok", second)
        assertTrue(server.takeRequest().path!!.startsWith("/backup"))
    }
}
