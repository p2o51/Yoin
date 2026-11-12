package com.gpo.yoin.data.integration.neodb

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "别人怎么说" page's parsing, against payloads shaped like neodb.social's (checked 2026-11-08). */
class NeoDbVoicesParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun item(uuid: String, title: String, ratingCount: Int?, artist: List<String> = emptyList()) =
        ShelfItem.Item(uuid = uuid, title = title, ratingCount = ratingCount, artist = artist)

    @Test
    fun should_pickMostRatedEdition_when_severalItemsShareTheTitle() {
        val items = listOf(
            item("apple", "OK Computer", 4, listOf("Radiohead")),
            item("main", "OK Computer", 510, listOf("Radiohead")),
            item("other", "OK Computer OKNOTOK 1997 2017", 80, listOf("Radiohead")),
        )
        assertEquals("main", NeoDbVoicesParsing.pickAlbumItem("OK Computer", "Radiohead", items)?.uuid)
    }

    @Test
    fun should_findNothing_when_onlyAnotherArtistsAlbumSharesTheTitle() {
        // neodb.social, 2026-11-09: OWIS's "Museum" isn't there; the search answers with other museums.
        val items = listOf(
            item("dean", "하루살이 (feat. 설리, Rad Museum)", 15, listOf("DEAN", "딘")),
            item("towa", "Sound Museum", 9, listOf("Towa Tei")),
            item("jazz", "Museum", 10, listOf("ElyonBeats")),
        )
        assertNull(NeoDbVoicesParsing.pickAlbumItem("Museum", "OWIS", items))
    }

    @Test
    fun should_findNothing_when_noTitleMatches() {
        val items = listOf(item("first", "Something else", 577), item("second", "Another", 900))
        assertNull(NeoDbVoicesParsing.pickAlbumItem("Blonde", "Frank Ocean", items))
    }

    @Test
    fun should_matchOneOfSeveralArtists_when_yoinListsFeaturedNames() {
        val items = listOf(item("x", "Museum", 3, listOf("OWIS", "오위스")))
        assertEquals("x", NeoDbVoicesParsing.pickAlbumItem("Museum", "Someone, OWIS feat. Guest", items)?.uuid)
    }

    @Test
    fun should_takeLongerEditionTitle_only_when_artistAgrees() {
        val items = listOf(
            item("deluxe", "Blonde Deluxe Edition", 40, listOf("Frank Ocean")),
            item("cover", "Blonde Covers", 90, listOf("Tribute Band")),
        )
        assertEquals("deluxe", NeoDbVoicesParsing.pickAlbumItem("Blonde", "Frank Ocean", items)?.uuid)
    }

    @Test
    fun should_acceptExactTitle_when_artistIsUnknown() {
        val items = listOf(item("a", "Blonde", 7))
        assertEquals("a", NeoDbVoicesParsing.pickAlbumItem("Blonde", null, items)?.uuid)
    }

    @Test
    fun should_keepSearchOrder_when_countsTie() {
        val items = listOf(item("a", "Blonde", null), item("b", "Blonde", null))
        assertEquals("a", NeoDbVoicesParsing.pickAlbumItem("Blonde", null, items)?.uuid)
    }

    @Test
    fun should_returnNull_when_noResults() {
        assertNull(NeoDbVoicesParsing.pickAlbumItem("Blonde", null, emptyList()))
    }

    @Test
    fun should_ignoreCaseAndEditionSuffix_when_normalizingTitles() {
        assertEquals(
            NeoDbVoicesParsing.normalizeTitle("ok computer"),
            NeoDbVoicesParsing.normalizeTitle("OK Computer (Remastered) [Deluxe]"),
        )
        assertEquals(
            NeoDbVoicesParsing.normalizeTitle("醜奴兒"),
            NeoDbVoicesParsing.normalizeTitle("醜奴兒（全景声）"),
        )
    }

    @Test
    fun should_readSpreadAndLinks_when_searchItemHasThem() {
        val body = """
            {"data":[{"uuid":"16eojnHcEjtDUsJP2yMEKk","url":"/album/16eojnHcEjtDUsJP2yMEKk","title":"醜奴兒",
            "artist":["草東沒有派對"],"rating":8.7,"rating_count":577,"rating_distribution":[0,2,10,35,53],
            "external_resources":[{"url":"https://music.douban.com/subject/26753594/"},
            {"url":"https://www.discogs.com/master/1076839"},
            {"url":"https://musicbrainz.org/release/ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6"}]}]}
        """.trimIndent()
        val item = json.decodeFromString<AlbumSearchResponse>(body).data.single()
        val album = NeoDbVoicesParsing.albumVoices("https://neodb.social/", item)!!
        assertEquals("https://neodb.social/album/16eojnHcEjtDUsJP2yMEKk", album.itemUrl)
        assertEquals(577, album.ratingCount)
        assertEquals(listOf(0, 2, 10, 35, 53), album.distribution)
        assertEquals("https://music.douban.com/subject/26753594/", album.doubanUrl)
        assertEquals("https://www.discogs.com/master/1076839", album.discogsUrl)
        assertTrue(album.musicBrainzUrl!!.contains("ad0e6676"))
    }

    @Test
    fun should_readCommentAndRating_when_postIsAShortComment() {
        val page = json.decodeFromString<NeoDbPostPage>(
            """
            {"pages":9,"count":173,"data":[{"id":"1","url":"https://neodb.social/@a/posts/1/",
            "created_at":"2026-08-15T17:30:11.376Z","sensitive":false,"spoiler_text":"",
            "account":{"acct":"everglow@neodb.social","username":"everglow","display_name":"Everglow",
            "avatar":"https://neodb.social/s/img/avatar.png"},
            "ext_neodb":{"relatedWith":[
              {"type":"Status","status":"complete"},
              {"type":"Comment","content":"很情绪，很动人"},
              {"type":"Rating","value":9,"best":10,"worst":1}]}}]}
            """.trimIndent(),
        )
        val voices = NeoDbVoicesParsing.page(1, page)
        assertEquals(173, voices.count)
        assertEquals(9, voices.pages)
        val voice = voices.voices.single()
        assertEquals("很情绪，很动人", voice.text)
        assertEquals(9, voice.rating)
        assertEquals("Everglow", voice.author)
        assertEquals("everglow@neodb.social", voice.handle)
        assertNull(voice.reviewTitle)
        assertFalse(voice.folded)
        assertEquals(1786815011376L, voice.createdAtMillis)
    }

    @Test
    fun should_useTitleAndOpening_when_postIsAReview() {
        val post = json.decodeFromString<NeoDbPost>(
            """
            {"id":"2","account":{"acct":"liz@neodb.social","display_name":""},
            "ext_neodb":{"relatedWith":[{"type":"Review","name":"爱人同质",
            "href":"https://neodb.social/review/1RLz","content":"\n B+ \n\n这绝对不是一张**完美**的专辑。"}]}}
            """.trimIndent(),
        )
        val voice = NeoDbVoicesParsing.voice(post)!!
        assertEquals("爱人同质", voice.reviewTitle)
        assertEquals("B+ 这绝对不是一张完美的专辑。", voice.text)
        assertEquals("https://neodb.social/review/1RLz", voice.url)
        assertEquals("liz", voice.author)
    }

    @Test
    fun should_foldVoice_when_writerAddedAContentWarning() {
        val post = json.decodeFromString<NeoDbPost>(
            """
            {"id":"3","sensitive":true,"spoiler_text":"剧透",
            "ext_neodb":{"relatedWith":[{"type":"Comment","content":"结尾那首……"}]}}
            """.trimIndent(),
        )
        val voice = NeoDbVoicesParsing.voice(post)!!
        assertTrue(voice.folded)
        assertEquals("剧透", voice.foldNote)
    }

    @Test
    fun should_skipPost_when_itCarriesNoWords() {
        val post = json.decodeFromString<NeoDbPost>(
            """{"id":"4","ext_neodb":{"relatedWith":[{"type":"Status"},{"type":"Rating","value":8}]}}""",
        )
        assertNull(NeoDbVoicesParsing.voice(post))
    }

    @Test
    fun should_keepOneVoice_when_pageRepeatsAPost() {
        val page = json.decodeFromString<NeoDbPostPage>(
            """
            {"pages":1,"count":2,"data":[
            {"id":"7","ext_neodb":{"relatedWith":[{"type":"Comment","content":"好听"}]}},
            {"id":"7","ext_neodb":{"relatedWith":[{"type":"Comment","content":"好听"}]}},
            {"id":"8","ext_neodb":{"relatedWith":[{"type":"Comment","content":"一般"}]}}]}
            """.trimIndent(),
        )
        // The grid keys its cards by id: the same id twice would crash it.
        assertEquals(listOf("7", "8"), NeoDbVoicesParsing.page(1, page).voices.map { it.id })
    }

    @Test
    fun should_openOnlyWebLinks_when_linkComesFromAPost() {
        assertTrue(NeoDbVoicesParsing.isWebLink("https://neodb.social/@a/posts/1/"))
        assertTrue(NeoDbVoicesParsing.isWebLink("HTTP://example.org/review/1"))
        assertFalse(NeoDbVoicesParsing.isWebLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(NeoDbVoicesParsing.isWebLink("file:///sdcard/Download/a.html"))
        assertFalse(NeoDbVoicesParsing.isWebLink(""))
    }

    @Test
    fun should_cutLongReviewAtASentence_when_openingIsTooLong() {
        val sentence = "这是一句不短不长的话。"
        val opening = NeoDbVoicesParsing.reviewOpening(sentence.repeat(40))
        assertTrue(opening.length <= 180)
        assertTrue(opening.endsWith("。"))
    }

    @Test
    fun should_readCatalogueIds_when_linksComeFromNeoDb() {
        assertEquals("master" to 21491L, NeoDbVoicesParsing.discogsId("https://www.discogs.com/master/21491"))
        assertEquals("release" to 4950798L, NeoDbVoicesParsing.discogsId("https://www.discogs.com/release/4950798-X"))
        assertEquals(
            "release" to "ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6",
            NeoDbVoicesParsing.musicBrainzId("https://musicbrainz.org/release/ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6"),
        )
        assertNull(NeoDbVoicesParsing.discogsId("https://music.douban.com/subject/1/"))
    }

    @Test
    fun should_buildSearchLinks_when_artistIsKnown() {
        assertEquals(
            "https://rateyourmusic.com/search?searchtype=l&searchterm=OK+Computer+Radiohead",
            NeoDbVoicesParsing.rymSearchUrl("OK Computer", "Radiohead"),
        )
        assertEquals(
            "https://www.albumoftheyear.org/search/albums/?q=Blonde",
            NeoDbVoicesParsing.aotySearchUrl("Blonde", " "),
        )
    }
}
