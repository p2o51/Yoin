package com.gpo.yoin.data.lyrics

import android.util.Log
import com.gpo.yoin.data.model.Lyrics
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
 * Fixtures under `src/test/resources/lyrics/huawei/` are anonymous Huawei Music
 * responses recorded 2026-10-04/05 from a JP IP: search lists cut to the first few
 * songs and to the fields the provider reads; lyric files cut to their header and
 * first two or three lines. Every request is routed to one MockWebServer by an
 * interceptor that maps `https://<host>/<path>` to `http://mock/<host>/<path>`, so
 * the provider sees the same absolute https URLs it sees in production.
 */
class HuaweiLyricsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: HuaweiLyricsProvider
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
                val handler = routes[request.requestUrl?.encodedPath.orEmpty()]
                return handler?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        provider = newProvider()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    fun should_postSongFuzzySearch_when_searchingManually() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        provider.searchMultiple("Lemon 米津玄師", "", limit = 3)

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("api-drcn.music.dbankcloud.cn", request.getHeader(ORIGINAL_HOST))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val fields = body.mapValues { (_, value) ->
            val primitive = value as JsonPrimitive
            assertTrue("every field is sent as a string", primitive.isString)
            primitive.content
        }
        assertEquals(
            mapOf("contentType" to "1", "queryWord" to "Lemon 米津玄師", "start" to "0", "limit" to "6"),
            fields,
        )
    }

    @Test
    fun should_sendNoCredentials_when_searchingAndFetching() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on(LEMON_LRC_PATH) { ok(fixture("lemon.lrc")) }

        val match = provider.searchMultiple("Lemon", "米津玄師", limit = 3).first()
        provider.fetchLyricWithTranslation(match.songId)

        assertEquals(2, seen.size)
        for (request in seen) {
            val names = request.headers.names().map { it.lowercase() }
            assertFalse(names.toString(), names.any { it == "authorization" || it == "cookie" || "token" in it })
            assertTrue(request.getHeader("User-Agent")!!.startsWith("Yoin/"))
        }
    }

    @Test
    fun should_keepPlatformOrderAndSkipLyriclessEntries_when_searchMultiple() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        val matches = provider.searchMultiple("Lemon", "米津玄師", limit = 3)

        // The music-box version (subType "abs") in second place has no lyrics to offer.
        assertEquals(listOf("14584432", "MFAkyyYXn9TxOlrAp"), matches.map { contentIdOf(it.songId) })
        assertEquals(SongMatch(matches[0].songId, "Lemon", "米津玄師(よねづ けんし)"), matches[0])
        assertEquals("Lemon(翻自：米津玄師)", matches[1].title)
        assertEquals(1, provider.searchMultiple("Lemon", "米津玄師", limit = 1).size)
    }

    @Test
    fun should_buildHintFromHuaweiNames_when_listingCandidates() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        val songId = provider.searchMultiple("lemon", "", limit = 1).single().songId

        assertTrue(songId.startsWith("hw1|14584432|"))
        assertTrue(provider.canFetch(songId))
        // Huawei's own title + artist without the "(よねづ けんし)" alias: re-searching
        // that finds the same contentID on page one.
        assertEquals(HuaweiSongRef("14584432", "Lemon 米津玄師"), HuaweiSongRef.parse(songId))
    }

    @Test
    fun should_throwUnavailable_when_searchFails() = runTest {
        val failures = listOf(
            MockResponse().setResponseCode(500),
            MockResponse().setResponseCode(400).setHeader("x-error-code", "400"),
            ok("<html>gateway</html>"),
            ok("""{"result":{"resultCode":"100001","resultMessage":"Failed"}}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        for (failure in failures) {
            onSearch { failure }
            try {
                provider.searchMultiple("Lemon", "米津玄師", limit = 3)
                fail("expected IOException for $failure")
            } catch (e: IOException) {
                assertEquals("Huawei Music is unavailable right now", e.message)
            }
            assertNull(provider.search("Lemon", "米津玄師"))
        }
    }

    @Test
    fun should_returnEmptyList_when_searchSucceedsWithoutSongs() = runTest {
        onSearch { ok(fixture("search_empty.json")) }

        assertEquals(emptyList<SongMatch>(), provider.searchMultiple("zzzz", "", limit = 3))
        assertNull(provider.search("zzzz", "nobody"))
    }

    @Test
    fun should_skipEntries_when_contentIdOrNameMissing() = runTest {
        onSearch {
            ok(
                searchBody(
                    song(id = null, name = "No Id", lineByLine = lrcUrl("a")),
                    song(id = "12345", name = null, lineByLine = lrcUrl("b")),
                    song(id = "bad|id", name = "Pipe", lineByLine = lrcUrl("c")),
                    song(id = "67890", name = "Kept", lineByLine = lrcUrl("d")),
                ),
            )
        }

        assertEquals(listOf("67890"), provider.searchMultiple("x", "", limit = 5).map { contentIdOf(it.songId) })
    }

    @Test
    fun should_readArtistFromContentExInfo_when_artistNameMissing() = runTest {
        onSearch {
            ok(
                searchBody(
                    song(id = "1", name = "Usseewa", artist = null, artistNames = "Ado", lineByLine = lrcUrl("a")),
                ),
            )
        }

        assertEquals("Ado", provider.searchMultiple("Usseewa", "", limit = 1).single().artist)
    }

    @Test
    fun should_pickVerifiedCandidate_when_artistHasParentheticalAlias() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        assertEquals("14584432", provider.search("Lemon", "米津玄師")?.songId?.let(::contentIdOf))
        val body = Json.parseToJsonElement(seen.single().body.readUtf8()).jsonObject
        assertEquals("10", (body["limit"] as JsonPrimitive).content)
    }

    @Test
    fun should_returnNull_when_artistCannotBeVerified() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        // Romanised artist vs. kanji on Huawei's side: undecidable, and only one cut.
        assertNull(provider.search("Lemon", "Kenshi Yonezu"))
    }

    @Test
    fun should_pickTranslatedOriginal_when_titleCarriesChineseName() = runTest {
        // Huawei lists "Pretender(假装者)" (with translation) first and a plain
        // "Pretender" (no translation) third; the Chinese name is not a version note.
        onSearch { ok(fixture("search_pretender.json")) }

        assertEquals("51044549", provider.search("Pretender", "Official髭男dism")?.songId?.let(::contentIdOf))
    }

    @Test
    fun should_preferStudioCut_when_plainTitleComesFromTourAlbum() = runTest {
        onSearch {
            ok(
                searchBody(
                    song(
                        id = "1",
                        name = "Pretender",
                        artist = "Official髭男dism",
                        album = "one-man tour 2021-2022 -Editorial-",
                        lineByLine = lrcUrl("a"),
                    ),
                    song(
                        id = "2",
                        name = "Pretender",
                        artist = "Official髭男dism",
                        album = "Traveler",
                        lineByLine = lrcUrl("b"),
                    ),
                ),
            )
        }

        assertEquals("2", provider.search("Pretender", "Official髭男dism")?.songId?.let(::contentIdOf))
    }

    @Test
    fun should_stripOnlyChineseTitleNames_when_preparingMatcherTitle() {
        assertEquals("Pretender", HuaweiLyricsProvider.withoutTranslatedTitle("Pretender(假装者)"))
        assertEquals("アイドル", HuaweiLyricsProvider.withoutTranslatedTitle("アイドル(偶像)"))
        assertEquals("夜に駆ける", HuaweiLyricsProvider.withoutTranslatedTitle("夜に駆ける(向夜晚奔去)"))
        assertEquals(
            "Espresso(On Vacation Version)",
            HuaweiLyricsProvider.withoutTranslatedTitle("Espresso(On Vacation Version)(浓缩咖啡)"),
        )
        // Version notes and Chinese titles stay as they are.
        assertEquals("Lemon(伴奏)", HuaweiLyricsProvider.withoutTranslatedTitle("Lemon(伴奏)"))
        assertEquals("Lemon(钢琴版)", HuaweiLyricsProvider.withoutTranslatedTitle("Lemon(钢琴版)"))
        assertEquals("晴天(现场)", HuaweiLyricsProvider.withoutTranslatedTitle("晴天(现场)"))
        assertEquals("孤勇者(电视剧)", HuaweiLyricsProvider.withoutTranslatedTitle("孤勇者(电视剧)"))
        assertEquals("Blinding Lights(Live)", HuaweiLyricsProvider.withoutTranslatedTitle("Blinding Lights(Live)"))
    }

    // ── lyric ────────────────────────────────────────────────────────────────

    @Test
    fun should_fetchAndSplitTranslation_when_songSeenInSearch() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on(LEMON_LRC_PATH) { ok(fixture("lemon.lrc")).setHeader("Content-Type", "application/octet-stream") }

        val match = provider.searchMultiple("Lemon", "米津玄師", limit = 3).first()
        val payload = provider.fetchLyricWithTranslation(match.songId)!!

        assertEquals(listOf("POST", "GET"), seen.map { it.method })
        assertEquals(LEMON_LRC_PATH, seen.last().requestUrl?.encodedPath)
        assertFalse(payload.lyric.contains('^'))
        assertTrue(payload.lyric.contains("[00:01.60]夢ならば\n"))
        val translation = payload.translatedLyric!!
        assertTrue(translation.contains("[00:01.60]如果只是一场梦"))
        // Title and credit lines share 00:00.00 and get placeholders, one per line.
        assertEquals(3, translation.lines().count { it == "[00:00.00]//" })
        val original = LrcParser.parse(payload.lyric) as Lyrics.Synced
        val translated = LrcParser.parse(translation) as Lyrics.Synced
        assertEquals(original.lines.map { it.startMs }, translated.lines.map { it.startMs })
        assertEquals(payload.lyric, provider.fetchLyric(match.songId))
    }

    @Test
    fun should_reResolveBySearch_when_memoryIsEmpty() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on(LEMON_LRC_PATH) { ok(fixture("lemon.lrc")) }
        val songId = HuaweiSongRef("14584432", "Lemon 米津玄師").encode()

        val lyric = newProvider().fetchLyric(songId)

        assertNotNull(lyric)
        val search = seen.first()
        val body = Json.parseToJsonElement(search.body.readUtf8()).jsonObject
        assertEquals("Lemon 米津玄師", (body["queryWord"] as JsonPrimitive).content)
        assertEquals("10", (body["limit"] as JsonPrimitive).content)
        assertEquals(LEMON_LRC_PATH, seen.last().requestUrl?.encodedPath)
    }

    @Test
    fun should_findOtherVersion_when_contentIdIsAChildContent() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on("/lyric.tingmall.com/lyric/34/893/34893867-LRC-LRC.lrc") { ok(fixture("lemon.lrc")) }

        val lyric = provider.fetchLyric(HuaweiSongRef("34893867", "Lemon 米津玄師").encode())

        assertNotNull(lyric)
        // The version parameter in the address is passed through untouched.
        assertEquals("1596882207000", seen.last().requestUrl?.queryParameter("t"))
    }

    @Test
    fun should_returnNull_when_reResolvedResultsLackContentId() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }

        // Same title, other recordings in the results: never substitute one of them.
        assertNull(provider.fetchLyric(HuaweiSongRef("99999999", "Lemon 米津玄師").encode()))
        assertEquals(1, seen.size)
    }

    @Test
    fun should_reResolveOnce_when_rememberedAddressIsGone() = runTest {
        val goneResponses = listOf(
            MockResponse().setResponseCode(404),
            MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(410),
            ok(fixture("nosuchkey.xml")),
            ok(""),
        )
        for (gone in goneResponses) {
            reset()
            val searches = ArrayDeque(listOf(fixture("search_lemon.json"), lemonSearchWithMovedLyric()))
            onSearch { ok(searches.removeFirst()) }
            on(LEMON_LRC_PATH) { gone }
            on(MOVED_LRC_PATH) { ok(fixture("lemon.lrc")) }

            val match = provider.searchMultiple("Lemon", "米津玄師", limit = 1).single()
            val payload = provider.fetchLyricWithTranslation(match.songId)

            assertNotNull("re-resolved after $gone", payload?.translatedLyric)
            assertEquals(
                listOf("search", LEMON_LRC_PATH, "search", MOVED_LRC_PATH),
                seen.map { if (it.method == "POST") "search" else it.requestUrl?.encodedPath },
            )
        }
    }

    @Test
    fun should_stopAfterOneRetry_when_reResolvedAddressIsGoneToo() = runTest {
        val searches = ArrayDeque(listOf(fixture("search_lemon.json"), lemonSearchWithMovedLyric()))
        onSearch { ok(searches.removeFirst()) }
        on(LEMON_LRC_PATH) { MockResponse().setResponseCode(404) }
        on(MOVED_LRC_PATH) { MockResponse().setResponseCode(404) }

        val match = provider.searchMultiple("Lemon", "米津玄師", limit = 1).single()

        assertNull(provider.fetchLyric(match.songId))
        assertEquals(4, seen.size)
    }

    @Test
    fun should_returnNull_when_reResolvedAddressIsUnchanged() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on(LEMON_LRC_PATH) { MockResponse().setResponseCode(404) }

        val match = provider.searchMultiple("Lemon", "米津玄師", limit = 1).single()

        assertNull(provider.fetchLyric(match.songId))
        assertEquals(listOf("POST", "GET", "POST"), seen.map { it.method })
    }

    @Test
    fun should_returnNull_when_freshlyResolvedAddressIsGone() = runTest {
        onSearch { ok(fixture("search_lemon.json")) }
        on(LEMON_LRC_PATH) { MockResponse().setResponseCode(404) }

        assertNull(provider.fetchLyric(HuaweiSongRef("14584432", "Lemon 米津玄師").encode()))
        assertEquals(listOf("POST", "GET"), seen.map { it.method })
    }

    @Test
    fun should_notReResolve_when_lyricFetchFailsTransiently() = runTest {
        val transientFailures = listOf(
            MockResponse().setResponseCode(503),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        for (failure in transientFailures) {
            reset()
            onSearch { ok(fixture("search_lemon.json")) }
            on(LEMON_LRC_PATH) { failure }

            val match = provider.searchMultiple("Lemon", "米津玄師", limit = 1).single()

            assertNull(provider.fetchLyricWithTranslation(match.songId))
            // OkHttp may retry a dropped GET itself; what matters is no second search.
            assertEquals(1, seen.count { it.method == "POST" })
            assertTrue(seen.filter { it.method == "GET" }.all { it.requestUrl?.encodedPath == LEMON_LRC_PATH })
        }
    }

    @Test
    fun should_preferLineByLineLyric_then_lyricAddress_then_lyricAddres() = runTest {
        onSearch {
            ok(
                searchBody(
                    song(
                        id = "1",
                        name = "A",
                        lineByLine = lrcUrl("line"),
                        lyricAddress = lrcUrl("top"),
                        lyricAddres = lrcUrl("ex"),
                    ),
                    song(id = "2", name = "B", lyricAddress = lrcUrl("top"), lyricAddres = lrcUrl("ex")),
                    song(id = "3", name = "C", lyricAddres = lrcUrl("ex")),
                ),
            )
        }
        for (key in listOf("line", "top", "ex")) on("/music-pics-drcn.dbankcdn.cn/$key.lrc") { ok("[00:01.00]$key") }

        val ids = provider.searchMultiple("x", "", limit = 3).map { it.songId }

        assertEquals(listOf("[00:01.00]line", "[00:01.00]top", "[00:01.00]ex"), ids.map { provider.fetchLyric(it) })
    }

    @Test
    fun should_skipEntry_when_lyricAddressIsNotHttps() = runTest {
        onSearch {
            ok(searchBody(song(id = "1", name = "Plain", lineByLine = "http://music-pics-drcn.dbankcdn.cn/a.lrc")))
        }

        assertEquals(emptyList<SongMatch>(), provider.searchMultiple("Plain", "", limit = 3))
        assertNull(provider.fetchLyric(HuaweiSongRef("1", "Plain").encode()))
        assertTrue(seen.all { it.method == "POST" })
    }

    @Test
    fun should_returnNull_when_lyricIsInstrumentalPlaceholder() = runTest {
        onSearch { ok(searchBody(song(id = "1", name = "Theme", lineByLine = lrcUrl("theme"), subType = "scrolling"))) }
        on("/music-pics-drcn.dbankcdn.cn/theme.lrc") { ok(fixture("instrumental.lrc")) }

        val match = provider.searchMultiple("Theme", "", limit = 1).single()

        assertNull(provider.fetchLyricWithTranslation(match.songId))
    }

    @Test
    fun should_stripBomAndCrlf_when_lyricHostedOnTingmall() = runTest {
        val address = "https://lyric.tingmall.com/lyric/09/285/9285974-LRC-LRC.lrc?t=1585123456000"
        onSearch { ok(searchBody(song(id = "9285974", name = "打上花火", lineByLine = address))) }
        on("/lyric.tingmall.com/lyric/09/285/9285974-LRC-LRC.lrc") { ok(fixture("uchiage_hanabi_bom_crlf.lrc")) }

        val match = provider.searchMultiple("打上花火", "", limit = 1).single()
        val payload = provider.fetchLyricWithTranslation(match.songId)!!

        assertTrue(payload.lyric.startsWith("[ti:打上花火"))
        assertFalse(payload.lyric.contains('\r') || payload.lyric.contains('﻿'))
        assertTrue(payload.lyric.contains("[00:20.30]あの日見わたした渚\n"))
        assertTrue(payload.translatedLyric!!.contains("[00:20.30]直到现在我也能想起"))
        assertTrue(payload.translatedLyric!!.contains("[00:06.69]//"))
    }

    @Test
    fun should_keepCaretsInOriginal_when_subTypeHasNoTranslate() = runTest {
        onSearch { ok(searchBody(song(id = "1", name = "Up", lineByLine = lrcUrl("up"), subType = "scrolling"))) }
        on("/music-pics-drcn.dbankcdn.cn/up.lrc") { ok("[00:01.00]Up ^ and down^ again") }

        val match = provider.searchMultiple("Up", "", limit = 1).single()
        val payload = provider.fetchLyricWithTranslation(match.songId)!!

        assertEquals("[00:01.00]Up ^ and down^ again", payload.lyric)
        assertNull(payload.translatedLyric)
    }

    // ── id ───────────────────────────────────────────────────────────────────

    @Test
    fun should_rejectMalformedIds_when_canFetch() = runTest {
        val malformed = listOf(
            "97773",
            "",
            "hw1|",
            "hw1||Lemon",
            "hw1|14584432|",
            "hw1|14584432|Lemon|extra",
            "hw2|14584432|Lemon",
            "hw1|1458 4432|Lemon",
            "hw1|14584432|%E3%8",
        )
        for (songId in malformed) {
            assertFalse(songId, provider.canFetch(songId))
            assertNull(provider.fetchLyric(songId))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_acceptDashAndUnderscore_when_canFetch() {
        assertTrue(provider.canFetch("hw1|NGZib8cFkavPHYYv_|Anti-Hero+Taylor+Swift"))
        assertTrue(provider.canFetch("hw1|NQ1PJ-GUJrtGMN4hk|Blinding+Lights+The+Weeknd"))
    }

    @Test
    fun should_roundTripSongRef_when_namesAreUnicode() {
        val ref = HuaweiSongRef("66957894", "アイドル(偶像) YOASOBI | live")

        val encoded = ref.encode()

        assertEquals(2, encoded.count { it == '|' })
        assertEquals(ref, HuaweiSongRef.parse(encoded))

        val long = HuaweiSongRef("1", "🎵".repeat(150))
        val parsed = HuaweiSongRef.parse(long.encode())!!
        assertEquals(100, parsed.hint.codePointCount(0, parsed.hint.length))
        assertEquals("🎵".repeat(100), parsed.hint)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun newProvider(): HuaweiLyricsProvider {
        val toMockServer = Interceptor { chain ->
            val original = chain.request()
            val target = server.url("/${original.url.host}${original.url.encodedPath}").newBuilder()
                .encodedQuery(original.url.encodedQuery)
                .build()
            chain.proceed(
                original.newBuilder().url(target).header(ORIGINAL_HOST, original.url.host).build(),
            )
        }
        return HuaweiLyricsProvider(client = OkHttpClient.Builder().addInterceptor(toMockServer).build())
    }

    /** Fresh provider (empty memory) and no recorded requests, for loops over cases. */
    private fun reset() {
        routes.clear()
        seen.clear()
        provider = newProvider()
    }

    private fun onSearch(response: (RecordedRequest) -> MockResponse) = on(SEARCH_PATH, response)

    private fun on(path: String, response: (RecordedRequest) -> MockResponse) {
        routes[path] = response
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("lyrics/huawei/$name")) { "missing fixture $name" }
            .readText()

    private fun lemonSearchWithMovedLyric(): String =
        fixture("search_lemon.json").replace(LEMON_LRC_FILE, MOVED_LRC_FILE)

    private fun contentIdOf(songId: String): String = HuaweiSongRef.parse(songId)!!.contentId

    private fun lrcUrl(key: String) = "https://music-pics-drcn.dbankcdn.cn/$key.lrc"

    private fun song(
        id: String?,
        name: String?,
        artist: String? = "Artist",
        lineByLine: String? = null,
        subType: String? = "translate|scrolling",
        lyricAddress: String? = null,
        lyricAddres: String? = null,
        artistNames: String? = null,
        album: String? = null,
    ): JsonObject = buildJsonObject {
        id?.let { put("contentID", it) }
        name?.let { put("contentName", it) }
        artist?.let { put("artistName", it) }
        album?.let { put("albumName", it) }
        lyricAddress?.let { put("lyricAddress", it) }
        val lyrics = buildJsonObject {
            lineByLine?.let { put("lineByLineLyric", it) }
            subType?.let { put("lineByLineLyric_subType", it) }
        }
        val extra = buildJsonObject {
            put("lyrics", lyrics.toString())
            lyricAddres?.let { put("lyricAddres", it) }
            artistNames?.let { put("artistNames", it) }
        }
        put("contentExInfo", extra.toString())
    }

    private fun searchBody(vararg songs: JsonObject): String = buildJsonObject {
        putJsonObject("result") {
            put("resultCode", "000000")
            put("resultMessage", "Success!")
        }
        putJsonArray("songSimpleInfos") { songs.forEach { add(it) } }
    }.toString()

    private companion object {
        const val ORIGINAL_HOST = "X-Test-Original-Host"
        const val SEARCH_PATH = "/api-drcn.music.dbankcloud.cn/music-search-service/v10/service/fuzzysearch"
        const val LEMON_LRC_FILE = "72a33102-7b76-4d58-a46c-fa533fd11a99.lrc"
        const val MOVED_LRC_FILE = "0b1e0000-0000-4000-8000-000000000000.lrc"
        const val LEMON_LRC_DIR =
            "/music-pics-drcn.dbankcdn.cn/4c23bb0d6931ddf3fd18fed3a0a8372e/fccc85b2037b9d71f82f28a3a433384d/"
        const val LEMON_LRC_PATH = LEMON_LRC_DIR + LEMON_LRC_FILE
        const val MOVED_LRC_PATH = LEMON_LRC_DIR + MOVED_LRC_FILE
    }
}
