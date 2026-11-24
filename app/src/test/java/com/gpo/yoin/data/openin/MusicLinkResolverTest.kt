package com.gpo.yoin.data.openin

import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Every host is answered by [server]: the transport rewrites requests to it, keeping the path. runBlocking, not
 * runTest: the resolver's timeout would run on runTest's virtual clock while the request is on a real thread.
 */
class MusicLinkResolverTest {
    private lateinit var server: MockWebServer
    private lateinit var resolver: MusicLinkResolver

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val local: HttpUrl = original.url.newBuilder()
                .scheme("http")
                .host(server.hostName)
                .port(server.port)
                .build()
            chain.proceed(original.newBuilder().url(local).header("X-Original-Host", original.url.host).build())
        }.build()
        resolver = MusicLinkResolver(
            transport = transport,
            appleMusicCatalog = AppleMusicApiClient(
                developerToken = { "dev-token" },
                musicUserToken = { "user-token-must-not-be-sent" },
                transport = OkHttpClient(),
                baseUrl = server.url("/"),
            ),
            activeSpotify = { null },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_readPublicSpotifyPage_withoutAnyIdentifier() = runBlocking {
        server.enqueue(MockResponse().setBody(SPOTIFY_TRACK_PAGE))
        val link = YoinMusicLinkParser.parse("https://open.spotify.com/intl-de/track/3n3Ppam7vgaVa1iaRUc9Lp?si=abc")!!

        val metadata = resolver.metadata(link)

        assertEquals(
            MusicLinkMetadata(
                kind = YoinMusicLinkKind.Track,
                title = "Mr. Brightside",
                artist = "The Killers",
                album = "Hot Fuss",
                durationSec = 222,
                artworkUrl = "https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73",
            ),
            metadata,
        )
        val request = server.takeRequest()
        // The canonical page: no si=, no intl- prefix, no cookie, no auth.
        assertEquals("/track/3n3Ppam7vgaVa1iaRUc9Lp", request.path)
        assertEquals("open.spotify.com", request.getHeader("X-Original-Host"))
        assertEquals("en", request.getHeader("Accept-Language"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun should_returnNull_when_spotifyPageFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(resolver.metadata(YoinMusicLinkParser.parse("spotify:track:3n3Ppam7vgaVa1iaRUc9Lp")!!))
    }

    @Test
    fun should_followShortLinkRedirect_toTheTrack() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", "https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp?si=xyz&_branch_match_id=1"),
        )

        val followed = resolver.resolve(YoinMusicLinkParser.parse("https://spotify.link/ZbMi8QkTZDb")!!)

        assertEquals(YoinMusicLinkKind.Track, followed?.kind)
        assertEquals("3n3Ppam7vgaVa1iaRUc9Lp", followed?.id)
        // The first hop that names the track ends it: open.spotify.com itself isn't fetched.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_readLandingPage_when_shortLinkAnswersWithHtml() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<html><head><meta property="og:url" content="https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj?si=1"/>""" +
                    """</head><body><a href="https://spotify.link/ZbMi8QkTZDb">again</a></body></html>""",
            ),
        )

        val followed = resolver.resolve(YoinMusicLinkParser.parse("https://spotify.link/ZbMi8QkTZDb")!!)

        assertEquals(YoinMusicLinkKind.Album, followed?.kind)
        assertEquals("4yP0hdKOZPNshxUOjY0cZj", followed?.id)
    }

    @Test
    fun should_readAppleCatalog_withDeveloperTokenOnly() = runBlocking {
        server.enqueue(MockResponse().setBody(APPLE_SONG))
        val link = YoinMusicLinkParser.parse("https://music.apple.com/gb/album/after-hours/1499378108?i=1499378615")!!

        val metadata = resolver.metadata(link)

        assertEquals("After Hours", metadata?.title)
        assertEquals("The Weeknd", metadata?.artist)
        assertEquals("USUG12000586", metadata?.isrc)
        assertEquals(361, metadata?.durationSec)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/6f/bc/e6/6fbce6c4/20UMGIM12176.rgb.jpg/600x600bb.jpg",
            metadata?.artworkUrl,
        )
        val request = server.takeRequest()
        assertEquals("/v1/catalog/gb/songs/1499378615", request.path)
        assertEquals("Bearer dev-token", request.getHeader("Authorization"))
        assertNull(request.getHeader("Music-User-Token"))
    }

    @Test
    fun should_tryTheDefaultStorefront_when_linksStorefrontLacksIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(APPLE_PLAYLIST))
        val link = YoinMusicLinkParser.parse("https://music.apple.com/jp/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb")!!

        val metadata = resolver.metadata(link)

        assertEquals("Today’s Hits", metadata?.title)
        assertEquals("Apple Music Hits", metadata?.artist)
        assertEquals("/v1/catalog/jp/playlists/pl.f4d106fed2bd41149aaacabb233eb5eb", server.takeRequest().path)
        assertEquals("/v1/catalog/us/playlists/pl.f4d106fed2bd41149aaacabb233eb5eb", server.takeRequest().path)
    }

    private companion object {
        // Trimmed from open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp, 2026-11-24.
        val SPOTIFY_TRACK_PAGE = """
            <!DOCTYPE html><html lang="en"><head><meta charSet="utf-8"/>
            <title>Mr. Brightside - Song, Lyrics, and Music Video by The Killers | Spotify</title>
            <meta property="og:title" content="Mr. Brightside"/>
            <meta property="og:description" content="The Killers · Hot Fuss · Song · 2004"/>
            <meta property="og:type" content="music.song"/>
            <meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73"/>
            <meta name="music:duration" content="222"/>
            <meta name="music:musician" content="https://open.spotify.com/artist/0C0XlULifJtAgn6ZNCW2eu"/>
            <meta name="music:musician_description" content="The Killers"/>
            </head><body></body></html>
        """.trimIndent()

        // Trimmed from GET /v1/catalog/us/songs/1499378615, 2026-11-24.
        val APPLE_SONG = """
            {"data":[{"id":"1499378615","type":"songs","href":"/v1/catalog/us/songs/1499378615","attributes":{
            "albumName":"After Hours","artistName":"The Weeknd","durationInMillis":361025,"isrc":"USUG12000586",
            "name":"After Hours","genreNames":["R&B/Soul","Music"],"trackNumber":14,
            "url":"https://music.apple.com/us/album/after-hours/1499378108?i=1499378615",
            "playParams":{"id":"1499378615","kind":"song"},
            "artwork":{"width":3000,"height":3000,
            "url":"https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/6f/bc/e6/6fbce6c4/20UMGIM12176.rgb.jpg/{w}x{h}bb.jpg"}}}]}
        """.trimIndent()

        // Trimmed from GET /v1/catalog/us/playlists/pl.f4d106fed2bd41149aaacabb233eb5eb, 2026-11-24.
        val APPLE_PLAYLIST = """
            {"data":[{"id":"pl.f4d106fed2bd41149aaacabb233eb5eb","type":"playlists","attributes":{
            "name":"Today’s Hits","curatorName":"Apple Music Hits","playlistType":"editorial",
            "artwork":{"url":"https://is1-ssl.mzstatic.com/image/thumb/Features/v4/e2/2e/01/d89a15a0.png/{w}x{h}SC.DN02.jpg?l=en-US"}}}]}
        """.trimIndent()
    }
}
