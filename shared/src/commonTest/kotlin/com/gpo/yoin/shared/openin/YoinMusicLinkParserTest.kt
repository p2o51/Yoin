package com.gpo.yoin.shared.openin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoinMusicLinkParserTest {

    private fun spotify(kind: YoinMusicLinkKind, id: String, url: String) =
        YoinMusicLink(YoinMusicLinkService.Spotify, kind, id, storefront = null, url = url)

    private fun apple(kind: YoinMusicLinkKind, id: String, storefront: String?, url: String) =
        YoinMusicLink(YoinMusicLinkService.AppleMusic, kind, id, storefront, url)

    // ── Spotify web links ──────────────────────────────────────────────

    @Test
    fun should_readSpotifyTrack_when_linkCarriesSiParameter() {
        val url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=a1b2c3d4e5f64789"
        assertEquals(spotify(YoinMusicLinkKind.Track, "0VjIjW4GlUZAMYd2vXMi3b", url), YoinMusicLinkParser.parse(url))
    }

    @Test
    fun should_readEverySpotifyKind_when_pathNamesIt() {
        val cases = mapOf(
            "https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj" to YoinMusicLinkKind.Album,
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=xyz&pt=abc" to YoinMusicLinkKind.Playlist,
            "https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ?si=QWERTY" to YoinMusicLinkKind.Artist,
            "https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp" to YoinMusicLinkKind.Track,
        )
        for ((url, kind) in cases) {
            assertEquals(kind, YoinMusicLinkParser.parse(url)?.kind, url)
            assertEquals(YoinMusicLinkService.Spotify, YoinMusicLinkParser.parse(url)?.service, url)
        }
    }

    @Test
    fun should_skipIntlSegment_when_linkIsLocalised() {
        val de = YoinMusicLinkParser.parse("https://open.spotify.com/intl-de/track/3n3Ppam7vgaVa1iaRUc9Lp?si=1")
        val ja = YoinMusicLinkParser.parse("https://open.spotify.com/intl-ja/album/3GzwPyPZCyrqUTaurTaS23")
        val ptBr = YoinMusicLinkParser.parse("https://open.spotify.com/intl-pt/artist/64tJ2EAv1R6UaZqc4iOCyj")
        assertEquals("3n3Ppam7vgaVa1iaRUc9Lp", de?.id)
        assertEquals(YoinMusicLinkKind.Album, ja?.kind)
        assertEquals(YoinMusicLinkKind.Artist, ptBr?.kind)
    }

    @Test
    fun should_readEmbedAndLegacyUserPlaylist_when_shapesAreOld() {
        assertEquals(
            "0VjIjW4GlUZAMYd2vXMi3b",
            YoinMusicLinkParser.parse("https://open.spotify.com/embed/track/0VjIjW4GlUZAMYd2vXMi3b?utm_source=generator")?.id,
        )
        val legacy = YoinMusicLinkParser.parse("https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(YoinMusicLinkKind.Playlist, legacy?.kind)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", legacy?.id)
        assertEquals("3n3Ppam7vgaVa1iaRUc9Lp", YoinMusicLinkParser.parse("http://play.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp")?.id)
    }

    @Test
    fun should_readLinkWithoutScheme_when_pastedBare() {
        assertEquals(
            "0VjIjW4GlUZAMYd2vXMi3b",
            YoinMusicLinkParser.parse("open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b")?.id,
        )
    }

    @Test
    fun should_rejectSpotifyLink_when_idIsMalformedOrKindUnsupported() {
        assertNull(YoinMusicLinkParser.parse("https://open.spotify.com/track/short"))
        assertNull(YoinMusicLinkParser.parse("https://open.spotify.com/episode/4rOoJ6Egrf8K2IrywzwOMk"))
        assertNull(YoinMusicLinkParser.parse("https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk"))
        assertNull(YoinMusicLinkParser.parse("https://open.spotify.com/"))
        assertNull(YoinMusicLinkParser.parse("https://spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"))
        assertNull(YoinMusicLinkParser.parse("https://open.spotify.com.evil.example/track/0VjIjW4GlUZAMYd2vXMi3b"))
        assertNull(YoinMusicLinkParser.parse("ftp://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"))
    }

    // ── Spotify URIs and short links ──────────────────────────────────

    @Test
    fun should_readSpotifyUri_when_textIsAUri() {
        assertEquals(
            spotify(YoinMusicLinkKind.Track, "0VjIjW4GlUZAMYd2vXMi3b", "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"),
            YoinMusicLinkParser.parse("spotify:track:0VjIjW4GlUZAMYd2vXMi3b"),
        )
        assertEquals(YoinMusicLinkKind.Album, YoinMusicLinkParser.parse("spotify:album:4yP0hdKOZPNshxUOjY0cZj")?.kind)
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            YoinMusicLinkParser.parse("spotify:user:spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")?.id,
        )
        assertNull(YoinMusicLinkParser.parse("spotify:episode:4rOoJ6Egrf8K2IrywzwOMk"))
    }

    @Test
    fun should_markShortLinkForRedirect_when_hostIsSpotifyLink() {
        val link = YoinMusicLinkParser.parse("https://spotify.link/ZbMi8QkTZDb")
        assertEquals(YoinMusicLinkKind.ShortLink, link?.kind)
        assertEquals("ZbMi8QkTZDb", link?.id)
        assertTrue(link!!.needsRedirectResolution)
        assertEquals("https://spotify.link/ZbMi8QkTZDb", link.canonicalUrl)
        assertEquals(YoinMusicLinkKind.ShortLink, YoinMusicLinkParser.parse("https://spoti.fi/3xYzAbC")?.kind)
        assertEquals(YoinMusicLinkKind.ShortLink, YoinMusicLinkParser.parse("spotify.app.link/AbCd1234?_p=c1")?.kind)
        assertNull(YoinMusicLinkParser.parse("https://spotify.link/"))
    }

    // ── Apple Music ───────────────────────────────────────────────────

    @Test
    fun should_readAppleMusicSong_when_albumLinkHasTrackParameter() {
        val url = "https://music.apple.com/us/album/after-hours/1499378108?i=1499378615"
        assertEquals(apple(YoinMusicLinkKind.Track, "1499378615", "us", url), YoinMusicLinkParser.parse(url))
    }

    @Test
    fun should_readAppleMusicKinds_when_pathNamesThem() {
        assertEquals(
            apple(
                YoinMusicLinkKind.Album,
                "1499378108",
                "us",
                "https://music.apple.com/us/album/after-hours/1499378108",
            ),
            YoinMusicLinkParser.parse("https://music.apple.com/us/album/after-hours/1499378108"),
        )
        val song = YoinMusicLinkParser.parse("https://music.apple.com/gb/song/blinding-lights/1488408568")
        assertEquals(YoinMusicLinkKind.Track, song?.kind)
        assertEquals("gb", song?.storefront)
        val artist = YoinMusicLinkParser.parse("https://music.apple.com/jp/artist/yoasobi/1487953183")
        assertEquals(YoinMusicLinkKind.Artist, artist?.kind)
        assertEquals("1487953183", artist?.id)
        val playlist = YoinMusicLinkParser.parse(
            "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
        )
        assertEquals(YoinMusicLinkKind.Playlist, playlist?.kind)
        assertEquals("pl.f4d106fed2bd41149aaacabb233eb5eb", playlist?.id)
        val userPlaylist = YoinMusicLinkParser.parse("https://music.apple.com/cn/playlist/my-mix/pl.u-mJjJTyKu8Zx1")
        assertEquals("pl.u-mJjJTyKu8Zx1", userPlaylist?.id)
    }

    @Test
    fun should_readAppleMusicLink_when_slugIsUnicodeOrMissing() {
        val unicode = YoinMusicLinkParser.parse("https://music.apple.com/jp/album/夜に駆ける/1487953175?i=1487953184")
        assertEquals(YoinMusicLinkKind.Track, unicode?.kind)
        assertEquals("1487953184", unicode?.id)
        assertEquals("jp", unicode?.storefront)
        val encoded = YoinMusicLinkParser.parse(
            "https://music.apple.com/cn/album/%E5%A4%9C%E3%81%AB%E9%A7%86%E3%81%91%E3%82%8B/1487953175",
        )
        assertEquals(YoinMusicLinkKind.Album, encoded?.kind)
        assertEquals("1487953175", YoinMusicLinkParser.parse("https://music.apple.com/album/1487953175")?.id)
        assertNull(YoinMusicLinkParser.parse("https://music.apple.com/album/1487953175")?.storefront)
        assertEquals(
            "1499378615",
            YoinMusicLinkParser.parse("https://music.apple.com/us/album/after-hours/1499378108?l=zh-Hans-CN&i=1499378615")?.id,
        )
    }

    @Test
    fun should_readAppleMusicLink_when_hostIsGeoOrLegacyItunes() {
        val geo = YoinMusicLinkParser.parse("https://geo.music.apple.com/us/album/_/1499378108?i=1499378615&mt=1&app=music")
        assertEquals(YoinMusicLinkKind.Track, geo?.kind)
        val legacy = YoinMusicLinkParser.parse("https://itunes.apple.com/us/album/after-hours/id1499378108?i=1499378615")
        assertEquals(apple(YoinMusicLinkKind.Track, "1499378615", "us", "https://itunes.apple.com/us/album/after-hours/id1499378108?i=1499378615"), legacy)
        val legacyAlbum = YoinMusicLinkParser.parse("https://itunes.apple.com/us/album/after-hours/id1499378108")
        assertEquals(YoinMusicLinkKind.Album, legacyAlbum?.kind)
        assertEquals("1499378108", legacyAlbum?.id)
        val legacyArtist = YoinMusicLinkParser.parse("https://itunes.apple.com/us/artist/the-weeknd/id479756766")
        assertEquals(YoinMusicLinkKind.Artist, legacyArtist?.kind)
    }

    @Test
    fun should_rejectAppleLink_when_notMusicContent() {
        assertNull(YoinMusicLinkParser.parse("https://www.apple.com/apple-music/"))
        assertNull(YoinMusicLinkParser.parse("https://music.apple.com/us/station/the-weeknd-station/ra.479756766"))
        assertNull(YoinMusicLinkParser.parse("https://music.apple.com/us/music-video/blinding-lights/1500952066"))
        assertNull(YoinMusicLinkParser.parse("https://music.apple.com/us/browse"))
        assertNull(YoinMusicLinkParser.parse("https://podcasts.apple.com/us/podcast/x/id1200361736"))
        assertNull(YoinMusicLinkParser.parse("https://music.apple.com/us/album/after-hours/notanid"))
    }

    // ── Canonical form and keys ───────────────────────────────────────

    @Test
    fun should_shareKey_when_linksDifferOnlyInDecoration() {
        val a = YoinMusicLinkParser.parse("https://open.spotify.com/intl-fr/track/0VjIjW4GlUZAMYd2vXMi3b?si=1")!!
        val b = YoinMusicLinkParser.parse("spotify:track:0VjIjW4GlUZAMYd2vXMi3b")!!
        assertEquals(a.key, b.key)
        assertEquals("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b", a.canonicalUrl)
        val song = YoinMusicLinkParser.parse("https://music.apple.com/album/x/1499378108?i=1499378615")!!
        assertEquals("https://music.apple.com/us/song/1499378615", song.canonicalUrl)
    }

    // ── Finding a link in shared text ─────────────────────────────────

    @Test
    fun should_findLink_when_sharedTextWrapsIt() {
        val text = "Check out this song! Blinding Lights by The Weeknd https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc"
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b", YoinMusicLinkParser.findFirst(text)?.id)
    }

    @Test
    fun should_findLink_when_cjkPunctuationEndsIt() {
        val text = "听这首：https://music.apple.com/cn/album/after-hours/1499378108?i=1499378615，太好听了。"
        val link = YoinMusicLinkParser.findFirst(text)
        assertEquals("1499378615", link?.id)
        assertEquals("cn", link?.storefront)
        val trailing = "Listen (https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj)."
        assertEquals("4yP0hdKOZPNshxUOjY0cZj", YoinMusicLinkParser.findFirst(trailing)?.id)
    }

    @Test
    fun should_takeFirstLink_when_textHoldsSeveral() {
        val text = "Apple: https://music.apple.com/us/album/after-hours/1499378108 " +
            "Spotify: https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"
        assertEquals(YoinMusicLinkService.AppleMusic, YoinMusicLinkParser.findFirst(text)?.service)
        val uriFirst = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b or https://music.apple.com/us/song/x/1488408568"
        assertEquals(YoinMusicLinkService.Spotify, YoinMusicLinkParser.findFirst(uriFirst)?.service)
    }

    @Test
    fun should_skipUnsupportedLinks_when_aSupportedOneFollows() {
        val text = "https://www.apple.com/music/ and https://open.spotify.com/episode/4rOoJ6Egrf8K2IrywzwOMk " +
            "then https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp"
        assertEquals("3n3Ppam7vgaVa1iaRUc9Lp", YoinMusicLinkParser.findFirst(text)?.id)
    }

    @Test
    fun should_findNothing_when_textHasNoMusicLink() {
        assertNull(YoinMusicLinkParser.findFirst(""))
        assertNull(YoinMusicLinkParser.findFirst("Blinding Lights The Weeknd"))
        assertNull(YoinMusicLinkParser.findFirst("https://www.youtube.com/watch?v=4NRXx6U8ABQ"))
        assertFalse(YoinMusicLinkParser.containsLink("see https://example.com/open.spotify.com"))
        assertTrue(YoinMusicLinkParser.containsLink("https://spotify.link/ZbMi8QkTZDb"))
    }

    @Test
    fun should_findShortLink_when_spotifyAppSharesIt() {
        val text = "Mr. Brightside - The Killers https://spotify.link/ZbMi8QkTZDb"
        val link = YoinMusicLinkParser.findFirst(text)
        assertEquals(YoinMusicLinkKind.ShortLink, link?.kind)
    }
}
