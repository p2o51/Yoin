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

/**
 * Fixtures under `src/test/resources/lyrics/netease/` are live `cloudsearch` answers
 * from the Spotoolfy proxy, recorded 2026-10-05 and trimmed to the fields the
 * provider reads (`id`, `name`, `ar[].name`, `al.name`) plus duration for
 * eyeballing. `cloudsearch_blinding_lights_versions.json` keeps 4 of the 10
 * entries (positions 3, 5, 7 and 10), relative order unchanged. Search answers
 * carry no lyric text; lyric bodies in this file are placeholders.
 */
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

    // ── automatic matching (recorded cloudsearch fixtures) ───────────────────

    @Test
    fun should_returnNull_when_onlyResultIsAnUnrelatedSong() = runTest {
        // An English song with a katakana artist (Apple Music JP storefront). NetEase
        // answers with a single, unrelated Japanese song; taking the first result
        // used to show its lyrics on the English track.
        server.enqueue(ok(fixture("cloudsearch_what_was_that_katakana.json")))

        assertNull(provider.search("What Was That", "ロード"))

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/cloudsearch"))
        assertEquals("What Was That ロード", request.requestUrl?.queryParameter("keywords"))
        assertEquals("10", request.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun should_returnNull_when_katakanaArtistCannotBeVerified() = runTest {
        // Same title, Latin-script artist vs a katakana request: no way to tell, and a
        // single recording is not enough to trust the performer.
        server.enqueue(ok(fixture("cloudsearch_stupid_love_katakana.json")))

        assertNull(provider.search("Stupid Love", "レディー・ガガ"))
    }

    @Test
    fun should_returnNull_when_onlyCoversAndNamesakesAnswer() = runTest {
        // NetEase has no copy of the original here: covers, a same-title song by
        // someone else, and short uploads. Better to let the next provider try.
        server.enqueue(ok(fixture("cloudsearch_qingtian.json")))

        assertNull(provider.search("晴天", "周杰伦"))
    }

    @Test
    fun should_skipOtherArtistRankedFirst_when_artistIsComparable() = runTest {
        server.enqueue(ok(fixture("cloudsearch_let_it_go.json")))

        assertEquals(
            SongMatch(songId = "28814175", title = "Let It Go", artist = "Demi Lovato"),
            provider.search("Let It Go", "Demi Lovato"),
        )
    }

    @Test
    fun should_preferMatchingArtist_when_bareTitleBelongsToSomeoneElse() = runTest {
        server.enqueue(ok(fixture("cloudsearch_let_it_go.json")))

        // The bare "Let It Go" is Demi Lovato's; the requested artist's top entry
        // carries a soundtrack note and still wins.
        assertEquals("28031119", provider.search("Let It Go", "Idina Menzel")?.songId)
    }

    @Test
    fun should_pickOriginal_when_remixLiveAndInstrumentalRankFirst() = runTest {
        server.enqueue(ok(fixture("cloudsearch_blinding_lights_versions.json")))

        assertEquals(
            SongMatch(songId = "1433194293", title = "Blinding Lights", artist = "The Weeknd"),
            provider.search("Blinding Lights", "The Weeknd"),
        )
    }

    @Test
    fun should_pickStudioRemaster_when_plainTitleIsAnUnpluggedRecording() = runTest {
        server.enqueue(ok(fixture("cloudsearch_hotel_california.json")))

        // "Hotel California (2013 Remaster)" ranks with a bare title; the bare
        // "Hotel California" further down is from "Unplugged 1994 - The Second Night".
        assertEquals("26289183", provider.search("Hotel California", "Eagles")?.songId)
    }

    @Test
    fun should_readAlbumName_when_unpluggedRecordingRanksFirst() = runTest {
        // Entries 4 and 3 of the recorded Hotel California answer, swapped.
        val body = """{"result":{"songs":[
            {"id":1302701468,"name":"Hotel California","ar":[{"name":"Eagles"}],
             "al":{"name":"Unplugged 1994 - The Second Night"}},
            {"id":1318509823,"name":"Hotel California (1999 Remaster)","ar":[{"name":"Eagles"}],
             "al":{"name":"Selected Works 1972-1999"}}
        ]},"code":200}"""
        server.enqueue(ok(body))

        assertEquals("1318509823", provider.search("Hotel California", "Eagles")?.songId)
    }

    @Test
    fun should_pickStudioRemaster_when_plainTitleIsOnAnInterviewAlbum() = runTest {
        server.enqueue(ok(fixture("cloudsearch_yesterday.json")))

        // The bare "Yesterday" is from "Famous Interviews - The Beatles".
        assertEquals("4337372", provider.search("Yesterday", "The Beatles")?.songId)
    }

    @Test
    fun should_pickInstrumental_when_requestedTitleIsInstrumental() = runTest {
        server.enqueue(ok(fixture("cloudsearch_blinding_lights_versions.json")))

        assertEquals(
            "1478647776",
            provider.search("Blinding Lights (Instrumental)", "The Weeknd")?.songId,
        )
    }

    @Test
    fun should_pickDominantPerformer_when_artistIsWrittenInAnotherScript() = runTest {
        server.enqueue(ok(fixture("cloudsearch_lemon.json")))
        server.enqueue(ok(fixture("cloudsearch_lemon.json")))

        // Latin request vs kanji credits: unverifiable, but the top performer owns the
        // most same-title recordings, so the original single is taken.
        assertEquals("536622304", provider.search("Lemon", "Kenshi Yonezu")?.songId)
        assertEquals("536622304", provider.search("Lemon", "米津玄師")?.songId)
    }

    @Test
    fun should_notMatchByRequestedTitle_when_resultHasNoName() = runTest {
        val body = """{"result":{"songs":[{"id":1,"ar":[{"name":"Placeholder Artist"}]}]},"code":200}"""
        server.enqueue(ok(body))
        server.enqueue(ok(body))

        assertNull(provider.search("Placeholder Song", "Placeholder Artist"))
        // The manual list still shows it, labelled with the requested title as before.
        assertEquals(
            listOf(SongMatch("1", "Placeholder Song", "Placeholder Artist")),
            provider.searchMultiple("Placeholder Song", "Placeholder Artist", limit = 3),
        )
    }

    @Test
    fun should_returnNull_when_searchFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok("not json"))

        assertNull(provider.search("Placeholder Song", "Placeholder Artist"))
        assertEquals(emptyList<SongMatch>(), provider.searchMultiple("Placeholder Song", "Placeholder Artist", 3))
    }

    // ── manual search ────────────────────────────────────────────────────────

    @Test
    fun should_keepNetEaseOrder_when_searchingManually() = runTest {
        server.enqueue(ok(fixture("cloudsearch_blinding_lights_versions.json")))

        val matches = provider.searchMultiple("Blinding Lights", "The Weeknd", limit = 3)

        assertEquals(
            listOf(
                SongMatch("1500412585", "Blinding Lights (Remix)", "The Weeknd"),
                SongMatch("2027162148", "Blinding Lights (Live)", "The Weeknd"),
                SongMatch("1478647776", "Blinding Lights (Instrumental)", "The Weeknd"),
            ),
            matches,
        )
        val request = server.takeRequest()
        assertEquals("Blinding Lights The Weeknd", request.requestUrl?.queryParameter("keywords"))
        assertEquals("3", request.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun should_listUnrelatedResult_when_searchingManually() = runTest {
        // Manual search shows NetEase's own answer; the user decides.
        server.enqueue(ok(fixture("cloudsearch_what_was_that_katakana.json")))

        assertEquals(
            listOf(SongMatch("2079173646", "Bittersweet", "BONNIE PINK")),
            provider.searchMultiple("What Was That", "ロード", limit = 3),
        )
    }

    @Test
    fun should_skipRequest_when_manualLimitIsZero() = runTest {
        assertEquals(emptyList<SongMatch>(), provider.searchMultiple("Placeholder Song", "", limit = 0))
        assertEquals(0, server.requestCount)
    }

    // ── lyric ────────────────────────────────────────────────────────────────

    @Test
    fun fetchLyric_returns_plain_lrc() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "lrc": {"lyric": "[00:01.00]占位歌词一"},
                  "tlyric": {"lyric": "[00:01.00]placeholder line one"}
                }
                """.trimIndent(),
            ),
        )

        val lrc = provider.fetchLyric("123")

        assertEquals("[00:01.00]占位歌词一", lrc)
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

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("lyrics/netease/$name")) { "missing fixture $name" }
            .readText()
}
