package com.gpo.yoin.data.lyrics

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Fixtures under `src/test/resources/lyrics/qq/` are live QQ responses recorded
 * 2026-10-05 (search lists trimmed to the fields the provider reads; lyric
 * bodies cut to their opening lines — no full song texts in the repo). The route
 * layout mirrors production: two web hosts (`/web1`, `/web2`) and two gateway
 * hosts (`/gw1`, `/gw2`) on one MockWebServer.
 */
class QQLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: QQLyricsProvider
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                val handler = routes.entries.firstOrNull { path.startsWith(it.key) }?.value
                return handler?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        provider = QQLyricsProvider(
            client = OkHttpClient.Builder().build(),
            webBaseUrls = listOf("$base/web1", "$base/web2"),
            gatewayBaseUrls = listOf("$base/gw1", "$base/gw2"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    fun should_returnArtistMatchedSong_when_webSearchAnswers() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_qingtian.json")) }

        val match = provider.search("晴天", "周杰伦")

        assertEquals(SongMatch("97773", "晴天", "周杰伦"), match)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("晴天 周杰伦", request.requestUrl?.queryParameter("w"))
        assertEquals("1", request.requestUrl?.queryParameter("new_json"))
        assertEquals("10", request.requestUrl?.queryParameter("n"))
    }

    @Test
    fun should_tryNextWebHost_when_firstSearchHostReturns500() = runTest {
        on("/web1/soso/") { MockResponse().setResponseCode(500) }
        on("/web2/soso/") { ok(fixture("search_cp_qingtian.json")) }

        assertEquals("97773", provider.search("晴天", "周杰伦")?.songId)
        assertEquals("97773", provider.search("晴天", "周杰伦")?.songId)

        // The host that answered is tried first next time.
        assertEquals(1, seen.count { it.path!!.startsWith("/web1/") })
        assertEquals(2, seen.count { it.path!!.startsWith("/web2/") })
    }

    @Test
    fun should_tryNextWebHost_when_searchBodyIsBlank() = runTest {
        on("/web1/soso/") { ok("") }
        on("/web2/soso/") { ok(fixture("search_cp_qingtian.json")) }

        assertEquals("97773", provider.search("晴天", "周杰伦")?.songId)
    }

    @Test
    fun should_fallBackToGatewaySearch_when_everyWebSearchHostFails() = runTest {
        on("/web1/soso/") { MockResponse().setResponseCode(500) }
        on("/web2/soso/") { MockResponse().setResponseCode(502) }
        on("/gw1/") { ok(fixture("gateway_refused_2001.json")) }
        on("/gw2/") { ok(fixture("gateway_search_qingtian.json")) }

        val match = provider.search("晴天", "周杰伦")

        assertEquals("97773", match?.songId)
        val gatewayBody = seen.last { it.path!!.startsWith("/gw2/") }.body.readUtf8()
        assertTrue(gatewayBody.contains("\"method\":\"DoSearchForQQMusicDesktop\""))
        assertTrue(gatewayBody.contains("\"query\":\"晴天 周杰伦\""))
        assertTrue(gatewayBody.contains("\"ct\":\"19\""))
    }

    @Test
    fun should_throwUnavailable_when_everySearchRouteFails() = runTest {
        on("/") { MockResponse().setResponseCode(500) }

        try {
            provider.searchMultiple("晴天", "周杰伦", limit = 3)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("QQ Music is unavailable right now", e.message)
        }
        assertNull(provider.search("晴天", "周杰伦"))
    }

    @Test
    fun should_returnNull_when_katakanaArtistLeavesSameTitleAmbiguous() = runTest {
        // Apple Music (JP storefront) spells Lady Gaga in katakana; QQ leads with
        // Jason Derulo's "Stupid Love". Better to let NetEase try than to show it.
        on("/web1/soso/") { ok(fixture("search_cp_stupid_love_katakana.json")) }

        assertNull(provider.search("Stupid Love", "レディー・ガガ"))
    }

    @Test
    fun should_skipWrongArtistAtTop_when_artistIsComparable() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_stupid_love_katakana.json")) }

        assertEquals(
            SongMatch("267472054", "Stupid Love", "Lady Gaga"),
            provider.search("Stupid Love", "Lady Gaga"),
        )
    }

    @Test
    fun should_returnNull_when_unverifiableArtistMatchesSeveralRecordings() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_what_was_that_katakana.json")) }

        assertNull(provider.search("What Was That", "ロード"))
        assertEquals("586058642", provider.search("What Was That", "Lorde")?.songId)
    }

    @Test
    fun should_keepProviderOrder_when_searchingManually() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_stupid_love_katakana.json")) }

        val matches = provider.searchMultiple("Stupid Love", "", limit = 3)

        assertEquals(listOf("5017814", "449497875", "267472054"), matches.map { it.songId })
        assertEquals("Stupid Love", seen.single().requestUrl?.queryParameter("w"))
        assertEquals("3", seen.single().requestUrl?.queryParameter("n"))
    }

    @Test
    fun should_preferStudioAlbum_when_plainTitleComesFromConcertAlbum() = runTest {
        val list = """[
            {"id":"9001","title":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"周杰伦 2004 无与伦比 演唱会 Live CD"}},
            {"id":"9002","title":"晴天","singer":[{"name":"周杰伦"}],"albumname":"Live in Tokyo 1966"},
            {"id":"97773","title":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"叶惠美"}}
        ]"""
        on("/web1/soso/") { ok("""{"code":0,"data":{"song":{"list":$list}}}""") }

        assertEquals("97773", provider.search("晴天", "周杰伦")?.songId)
    }

    @Test
    fun should_skipSongsWithoutNumericId_when_parsingSearch() = runTest {
        val list = """[{"id":"abc123","title":"Lost Stars","singer":[{"name":"Adam Levine"}]}]"""
        on("/web1/soso/") { ok("""{"code":0,"data":{"song":{"list":$list}}}""") }

        assertNull(provider.search("Lost Stars", "Adam Levine"))
    }

    // ── lyric ────────────────────────────────────────────────────────────────

    @Test
    fun should_decodeLyricAndCleanTranslation_when_gatewayAnswers() = runTest {
        on("/gw1/") { ok(fixture("play_lyric_lemon_213086592.json")) }

        val payload = provider.fetchLyricWithTranslation("213086592")

        assertNotNull(payload)
        assertTrue(payload!!.lyric.startsWith("[ti:Lemon"))
        assertTrue(payload.lyric.contains("[00:01.54]夢ならば"))
        val translation = payload.translatedLyric!!
        assertTrue(translation.contains("[00:01.54]如果只是一场梦"))
        assertFalse(translation.lines().any { it.endsWith("]//") })
        val body = seen.single().body.readUtf8()
        assertTrue(body.contains("\"songID\":213086592"))
        assertTrue(body.contains("\"crypt\":0"))
        assertTrue(body.contains("\"trans\":1"))
        assertFalse(body.contains("songMID"))
    }

    @Test
    fun should_tryNextGatewayHost_when_lyricCodeIsNonZero() = runTest {
        on("/gw1/") { ok("""{"code":0,"req_1":{"code":24001}}""") }
        on("/gw2/") { ok(fixture("play_lyric_lemon_213086592.json")) }

        assertTrue(provider.fetchLyric("213086592")!!.startsWith("[ti:Lemon"))
    }

    @Test
    fun should_decodePlainBase64Lyric_when_gatewayAnswers() = runTest {
        val plain = "[00:01.00]hello world"
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        on("/gw1/") { ok("""{"req_1": {"code": 0, "data": {"lyric": "$b64"}}}""") }

        assertEquals(plain, provider.fetchLyric("97773"))
    }

    @Test
    fun should_useRememberedMid_when_gatewayIsUnreachable() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_qingtian.json")) }
        on("/gw") { MockResponse().setResponseCode(500) }
        on("/web1/lyric/") { ok(fixture("legacy_lyric_qingtian_0039MnYb0qxYhV.json")) }

        val match = provider.search("晴天", "周杰伦")!!
        val payload = provider.fetchLyricWithTranslation(match.songId)

        assertTrue(payload!!.lyric.startsWith("[ti:晴天]"))
        assertTrue(payload.lyric.contains("故事的小黄花"))
        val lyricRequest = seen.single { it.path!!.startsWith("/web1/lyric/") }
        assertEquals("0039MnYb0qxYhV", lyricRequest.requestUrl?.queryParameter("songmid"))
        assertEquals("1", lyricRequest.requestUrl?.queryParameter("nobase64"))
        assertTrue(seen.none { it.path!!.contains("fcg_play_single_song") })
    }

    @Test
    fun should_resolveMidBySongId_when_midIsUnknown() = runTest {
        on("/gw") { MockResponse().setResponseCode(500) }
        on("/web1/v8/") { ok(fixture("song_detail_97773.json")) }
        on("/web1/lyric/") { ok(fixture("legacy_lyric_qingtian_jsonp_base64.txt")) }

        val lyric = provider.fetchLyric("97773")

        assertTrue(lyric!!.startsWith("[ti:晴天]"))
        val detail = seen.single { it.path!!.startsWith("/web1/v8/") }
        assertEquals("97773", detail.requestUrl?.queryParameter("songid"))
    }

    @Test
    fun should_useWebLyric_when_gatewaySendsCipherText() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_qingtian.json")) }
        on("/gw1/") { ok("""{"req_1":{"code":0,"data":{"lyric":"${"E5D94A70C91F7022".repeat(4)}","trans":""}}}""") }
        on("/web1/lyric/") { ok(fixture("legacy_lyric_qingtian_0039MnYb0qxYhV.json")) }

        provider.search("晴天", "周杰伦")
        val lyric = provider.fetchLyric("97773")

        assertTrue(lyric!!.startsWith("[ti:晴天]"))
    }

    @Test
    fun should_returnNull_when_gatewayAnswersWithoutLyric() = runTest {
        on("/gw1/") { ok("""{"req_1":{"code":0,"data":{"lyric":"","trans":""}}}""") }

        assertNull(provider.fetchLyric("97773"))
        assertEquals(1, seen.size)
    }

    @Test
    fun should_tryNextWebHost_when_webLyricIsRefused() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_qingtian.json")) }
        on("/gw") { MockResponse().setResponseCode(500) }
        on("/web1/lyric/") { ok(fixture("legacy_lyric_refused_1101.json")) }
        on("/web2/lyric/") { ok(fixture("legacy_lyric_qingtian_0039MnYb0qxYhV.json")) }

        provider.search("晴天", "周杰伦")

        assertTrue(provider.fetchLyric("97773")!!.startsWith("[ti:晴天]"))
    }

    @Test
    fun should_stopAtFirstWebHost_when_songHasNoLyric() = runTest {
        on("/web1/soso/") { ok(fixture("search_cp_qingtian.json")) }
        on("/gw") { MockResponse().setResponseCode(500) }
        on("/web1/lyric/") { ok("""{"retcode":-1901,"code":-1901,"subcode":-1901}""") }

        provider.search("晴天", "周杰伦")

        assertNull(provider.fetchLyric("97773"))
        assertTrue(seen.none { it.path!!.startsWith("/web2/lyric/") })
    }

    @Test
    fun should_rejectNonNumericSongId_when_fetching() = runTest {
        assertNull(provider.fetchLyric("abc123"))
        assertEquals(0, server.requestCount)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun on(pathPrefix: String, response: (RecordedRequest) -> MockResponse) {
        routes[pathPrefix] = response
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("lyrics/qq/$name")) { "missing fixture $name" }
            .readText()
}
