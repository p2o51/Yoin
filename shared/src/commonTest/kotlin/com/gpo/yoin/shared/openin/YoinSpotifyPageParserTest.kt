package com.gpo.yoin.shared.openin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Against pages saved from open.spotify.com (2026-11-24, `Accept-Language: en`): [SpotifyPageFixtures]. */
class YoinSpotifyPageParserTest {

    @Test
    fun should_readTrackMeta_when_pageIsATrack() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.TRACK_MR_BRIGHTSIDE)
        assertEquals("Mr. Brightside", meta.title)
        assertEquals("The Killers", meta.artist)
        assertEquals("Hot Fuss", meta.album)
        assertEquals(222, meta.durationSec)
        assertEquals("https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73", meta.imageUrl)
        assertEquals("music.song", meta.ogType)
    }

    @Test
    fun should_keepEveryArtist_when_trackCreditsSeveral() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.TRACK_STAY)
        assertEquals("STAY (with Justin Bieber)", meta.title)
        assertEquals("The Kid LAROI, Justin Bieber", meta.artist)
        assertEquals("F*CK LOVE 3: OVER YOU", meta.album)
        assertEquals(142, meta.durationSec)
    }

    @Test
    fun should_keepCjkTitle_when_trackIsJapanese() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.TRACK_YORU_NI_KAKERU)
        assertEquals("夜に駆ける", meta.title)
        assertEquals("YOASOBI", meta.artist)
        assertEquals(261, meta.durationSec)
    }

    @Test
    fun should_stripListenToAndByline_when_pageIsAnAlbum() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.ALBUM_AFTER_HOURS)
        assertEquals("After Hours", meta.title)
        assertEquals("The Weeknd", meta.artist)
        assertNull(meta.album)
        assertNull(meta.durationSec)
        assertEquals("music.album", meta.ogType)
    }

    @Test
    fun should_readNameOnly_when_pageIsAnArtist() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.ARTIST_THE_WEEKND)
        assertEquals("The Weeknd", meta.title)
        assertNull(meta.artist)
        assertEquals("https://i.scdn.co/image/ab6761610000e5ebc1719ac9e6a75c1c25835018", meta.imageUrl)
    }

    @Test
    fun should_readOwner_when_pageIsAPlaylist() {
        val meta = YoinSpotifyPageParser.parse(SpotifyPageFixtures.PLAYLIST_TODAYS_TOP_HITS)
        assertEquals("Today’s Top Hits", meta.title)
        assertEquals("Spotify", meta.owner)
        assertNull(meta.artist)
    }

    @Test
    fun should_fallBackToTitleTag_when_metaTagsAreMissing() {
        val html = """
            <html><head>
            <title>Mr. Brightside - Song, Lyrics, and Music Video by The Killers | Spotify</title>
            </head></html>
        """.trimIndent()
        val meta = YoinSpotifyPageParser.parse(html)
        assertEquals("Mr. Brightside", meta.title)
        assertEquals("The Killers", meta.artist)
        assertNull(meta.durationSec)
    }

    @Test
    fun should_readArtistFromDescription_when_musicianTagIsMissing() {
        val html = """
            <meta content="Blinding Lights" property="og:title">
            <meta property='og:description' content='The Weeknd &amp; Friends · After Hours · Song · 2020'>
            <meta property="og:type" content="music.song">
        """.trimIndent()
        val meta = YoinSpotifyPageParser.parse(html)
        assertEquals("The Weeknd & Friends", meta.artist)
        assertEquals("After Hours", meta.album)
    }

    @Test
    fun should_decodeEntities_when_metaIsEscaped() {
        assertEquals("Tom & Jerry's “Song”", YoinSpotifyPageParser.decodeEntities("Tom &amp; Jerry&#39;s &#x201C;Song&#8221;"))
        assertEquals("a &bogus; b", YoinSpotifyPageParser.decodeEntities("a &bogus; b"))
    }

    @Test
    fun should_returnEmptyMeta_when_pageIsNotSpotify() {
        val meta = YoinSpotifyPageParser.parse("<html><body>Not found</body></html>")
        assertNull(meta.title)
        assertNull(meta.artist)
        assertNull(meta.imageUrl)
    }
}
