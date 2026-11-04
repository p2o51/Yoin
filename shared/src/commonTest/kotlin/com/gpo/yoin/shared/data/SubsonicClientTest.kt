package com.gpo.yoin.shared.data

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.data.subsonic.AlbumListType
import com.gpo.yoin.shared.data.subsonic.SubsonicClient
import com.gpo.yoin.shared.data.subsonic.SubsonicErrorKind
import com.gpo.yoin.shared.data.subsonic.SubsonicException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsonicClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    /** Routes `/rest/<endpoint>.view` to a body; unknown endpoints answer Subsonic error 0. */
    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        baseUrl: String = "https://music.example.com/navidrome/",
        routes: Map<String, String>,
    ): SubsonicClient {
        val engine = MockEngine { request ->
            requests += request
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            respond(
                content = routes[endpoint] ?: SubsonicFixtures.failed(0, "Unknown endpoint $endpoint"),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"),
            )
        }
        return SubsonicClient(baseUrl, "alice", "sesame", "c19b2d", engine)
    }

    private val lastUrl: Url get() = requests.last().url

    // ------------------------------------------------------------------ auth

    @Test
    fun should_sendTokenAuthParams_when_callingAnyEndpoint() = runTest {
        client(routes = mapOf("ping" to SubsonicFixtures.ping)).ping()
        val url = lastUrl
        assertEquals("/navidrome/rest/ping.view", url.encodedPath)
        assertEquals("alice", url.parameters["u"])
        assertEquals("26719a1196d2a940705a59634eb18eab", url.parameters["t"])
        assertEquals("c19b2d", url.parameters["s"])
        assertEquals("1.16.1", url.parameters["v"])
        assertEquals("Yoin", url.parameters["c"])
        assertEquals("json", url.parameters["f"])
        assertNull(url.parameters["p"], "the password must never be sent")
    }

    @Test
    fun should_keepOneSaltPerClient_when_passwordConstructorUsed() {
        val c = SubsonicClient("https://h", "alice", "sesame")
        assertEquals(16, c.salt.length)
        assertEquals(c.token, SubsonicClient("https://h", "alice", c.token, c.salt).token)
        assertTrue(c.streamUrl("s-1", null, null).contains("t=${c.token}&s=${c.salt}"))
        c.close()
    }

    @Test
    fun should_reportServerInfo_when_pingSucceeds() = runTest {
        val info = client(routes = mapOf("ping" to SubsonicFixtures.ping)).ping()
        assertEquals("1.16.1", info.apiVersion)
        assertEquals("navidrome", info.type)
        assertEquals("0.55.0", info.serverVersion)
        assertTrue(info.openSubsonic)
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun should_throwWrongCredentials_when_serverReturnsCode40() = runTest {
        val c = client(routes = mapOf("ping" to SubsonicFixtures.failed(40, "Wrong username or password")))
        val e = assertFailsWith<SubsonicException> { c.ping() }
        assertEquals(SubsonicErrorKind.WRONG_CREDENTIALS, e.kind)
        assertEquals(40, e.code)
        assertEquals("Wrong username or password", e.message)
        assertTrue(e.isAuthFailure)
    }

    @Test
    fun should_mapEveryApiCode_when_statusFailed() = runTest {
        val expected = mapOf(
            0 to SubsonicErrorKind.GENERIC,
            10 to SubsonicErrorKind.MISSING_PARAMETER,
            20 to SubsonicErrorKind.CLIENT_TOO_OLD,
            30 to SubsonicErrorKind.SERVER_TOO_OLD,
            41 to SubsonicErrorKind.TOKEN_AUTH_NOT_SUPPORTED,
            42 to SubsonicErrorKind.AUTH_MECHANISM_NOT_SUPPORTED,
            43 to SubsonicErrorKind.CONFLICTING_AUTH,
            44 to SubsonicErrorKind.INVALID_API_KEY,
            50 to SubsonicErrorKind.NOT_AUTHORIZED,
            60 to SubsonicErrorKind.TRIAL_EXPIRED,
            70 to SubsonicErrorKind.NOT_FOUND,
            99 to SubsonicErrorKind.GENERIC,
        )
        expected.forEach { (code, kind) ->
            val c = client(routes = mapOf("getAlbum" to SubsonicFixtures.failed(code, "x")))
            val e = assertFailsWith<SubsonicException> { c.getAlbum("missing") }
            assertEquals(kind, e.kind, "code $code")
            assertEquals(code, e.code)
            assertEquals(code in 41..44, e.isAuthFailure, "code $code")
        }
    }

    @Test
    fun should_preferSubsonicError_when_httpStatusIsAlsoAnError() = runTest {
        val c = client(status = HttpStatusCode.Unauthorized, routes = mapOf("ping" to SubsonicFixtures.failed(40, "nope")))
        val e = assertFailsWith<SubsonicException> { c.ping() }
        assertEquals(SubsonicErrorKind.WRONG_CREDENTIALS, e.kind)
        assertEquals(401, e.httpStatus)
    }

    @Test
    fun should_throwHttp_when_non2xxWithoutSubsonicBody() = runTest {
        val engine = MockEngine { respond("<html>Bad Gateway</html>", HttpStatusCode.BadGateway) }
        val e = assertFailsWith<SubsonicException> { SubsonicClient("https://h", "u", "p", "salt", engine).ping() }
        assertEquals(SubsonicErrorKind.HTTP, e.kind)
        assertEquals(502, e.httpStatus)
    }

    @Test
    fun should_throwParse_when_okStatusButNotJson() = runTest {
        val engine = MockEngine { respond("<html>login page</html>", HttpStatusCode.OK) }
        val e = assertFailsWith<SubsonicException> { SubsonicClient("https://h", "u", "p", "salt", engine).ping() }
        assertEquals(SubsonicErrorKind.PARSE, e.kind)
    }

    @Test
    fun should_wrapAsNetwork_when_engineThrows() = runTest {
        val engine = MockEngine { throw IllegalStateException("Connection refused") }
        val e = assertFailsWith<SubsonicException> { SubsonicClient("https://h", "u", "p", "salt", engine).ping() }
        assertEquals(SubsonicErrorKind.NETWORK, e.kind)
        assertEquals(-1, e.code)
    }

    @Test
    fun should_redactCredentials_when_engineMessageQuotesTheUrl() = runTest {
        // Ktor's HttpRequestTimeoutException says "Request timeout has expired [url=<the signed URL>, ...]".
        val engine = MockEngine { request -> throw IllegalStateException("Request timeout has expired [url=${request.url}, request_timeout=30000 ms]") }
        val c = SubsonicClient("https://h", "alice", "sesame", "c19b2d", engine)
        val e = assertFailsWith<SubsonicException> { c.ping() }
        val message = e.message.orEmpty()
        assertFalse(message.contains(c.token), message)
        assertFalse(message.contains("c19b2d"), message)
        assertFalse(message.contains("alice"), message)
        assertTrue(message.contains("t=REDACTED"), message)
        assertTrue(message.contains("ping.view"), message)
    }

    @Test
    fun should_blankEveryCredentialParam_when_redacting() {
        val url = "https://h/rest/stream.view?id=s1&u=alice&t=abc123&s=salt9&v=1.16.1&p=enc:7365&apiKey=k"
        assertEquals(
            "https://h/rest/stream.view?id=s1&u=REDACTED&t=REDACTED&s=REDACTED&v=1.16.1&p=REDACTED&apiKey=REDACTED",
            SubsonicClient.redactCredentials(url),
        )
        assertEquals("no url here", SubsonicClient.redactCredentials("no url here"))
    }

    @Test
    fun should_listExtensionNames_when_openSubsonic() = runTest {
        val body = SubsonicFixtures.ok(
            """"openSubsonicExtensions": [{"name": "transcodeOffset", "versions": [1]}, {"name": "songLyrics", "versions": [1]}]""",
        )
        assertEquals(
            listOf("transcodeOffset", "songLyrics"),
            client(routes = mapOf("getOpenSubsonicExtensions" to body)).getOpenSubsonicExtensions(),
        )
        // A plain Subsonic server answers error 0 (unknown endpoint): no extensions.
        assertEquals(emptyList(), client(routes = emptyMap()).getOpenSubsonicExtensions())
    }

    // --------------------------------------------------------------- library

    @Test
    fun should_mapAlbumWithSongs_when_getAlbum() = runTest {
        val album = assertNotNull(client(routes = mapOf("getAlbum" to SubsonicFixtures.album)).getAlbum("al-1"))
        assertEquals("al-1", lastUrl.parameters["id"])
        assertEquals(MediaRef("subsonic", "al-1"), album.ref)
        assertEquals("subsonic:al-1", album.ref.key)
        assertEquals("Test Album", album.name)
        assertEquals("Test Artist", album.artist)
        assertEquals("ar-1", album.artistId)
        assertEquals(10, album.songCount)
        assertEquals(3600, album.durationSec)
        assertEquals(2024, album.year)
        assertEquals("Rock", album.genre)
        assertEquals(42, album.playCount)
        assertEquals("2023-05-02T10:00:00.000Z", album.created)
        assertTrue(album.starred)
        assertEquals("2024-01-01T00:00:00Z", album.starredAt)
        assertEquals("album", album.releaseType)
        val song = album.songs.single()
        assertEquals("s-1", song.id)
        assertEquals("Song One", song.title)
        assertEquals(1, song.track)
        assertEquals(2, song.disc)
        assertEquals(240, song.durationSec)
        assertEquals(320, song.bitRate)
        assertFalse(song.starred)
        assertNull(song.starredAt)
        assertNull(song.coverArtId)
        assertNull(song.userRating)
    }

    @Test
    fun should_fallBackToAlbumId_when_albumHasNoCoverArt() = runTest {
        val album = assertNotNull(client(routes = mapOf("getAlbum" to SubsonicFixtures.albumNoCover)).getAlbum("al-9"))
        assertEquals("al-9", album.coverArtId)
        assertTrue(album.songs.isEmpty())
        assertNull(album.releaseType)
    }

    @Test
    fun should_returnNull_when_okResponseHasNoAlbum() = runTest {
        assertNull(client(routes = mapOf("getAlbum" to SubsonicFixtures.ok())).getAlbum("al-1"))
    }

    @Test
    fun should_passTypeSizeOffset_when_getAlbumList2() = runTest {
        val c = client(routes = mapOf("getAlbumList2" to SubsonicFixtures.albumList2))
        val albums = c.getAlbumList2(AlbumListType.ALPHABETICAL_BY_NAME, 30, 60)
        assertEquals("alphabeticalByName", lastUrl.parameters["type"])
        assertEquals("30", lastUrl.parameters["size"])
        assertEquals("60", lastUrl.parameters["offset"])
        assertEquals(listOf("Album A", "Album B"), albums.map { it.name })
        assertEquals("ep", albums[0].releaseType)
        assertEquals("single", albums[1].releaseType, "first recognised type wins")
        listOf(
            AlbumListType.NEWEST to "newest",
            AlbumListType.RECENT to "recent",
            AlbumListType.FREQUENT to "frequent",
            AlbumListType.RANDOM to "random",
            AlbumListType.STARRED to "starred",
        ).forEach { (type, wire) ->
            c.getAlbumList2(type, 10, 0)
            assertEquals(wire, lastUrl.parameters["type"])
        }
    }

    @Test
    fun should_groupArtists_when_getArtists() = runTest {
        val index = client(routes = mapOf("getArtists" to SubsonicFixtures.artists)).getArtists()
        assertEquals(listOf("A", "B"), index.map { it.name })
        assertEquals(listOf("ABBA", "AC/DC"), index[0].artists.map { it.name })
        assertEquals(5, index[0].artists[0].albumCount)
        assertFalse(index[0].artists[0].starred)
        assertTrue(index[0].artists[1].starred)
    }

    @Test
    fun should_includeAlbums_when_getArtist() = runTest {
        val artist = assertNotNull(client(routes = mapOf("getArtist" to SubsonicFixtures.artist)).getArtist("ar-1"))
        assertEquals("Pink Floyd", artist.name)
        assertEquals(15, artist.albumCount)
        assertEquals("ar-1", artist.coverArtId)
        assertEquals(listOf("The Wall", "Wish You Were Here"), artist.albums.map { it.name })
        assertEquals(1979, artist.albums[0].year)
        assertEquals("al-1", artist.albums[0].coverArtId)
    }

    @Test
    fun should_passCounts_when_search3() = runTest {
        val result = client(routes = mapOf("search3" to SubsonicFixtures.search3)).search3("queen & co", 5, 10, 50)
        assertEquals("queen & co", lastUrl.parameters["query"])
        assertEquals("5", lastUrl.parameters["artistCount"])
        assertEquals("10", lastUrl.parameters["albumCount"])
        assertEquals("50", lastUrl.parameters["songCount"])
        assertEquals("Queen", result.artists.single().name)
        assertEquals("A Night at the Opera", result.albums.single().name)
        assertEquals(354, result.songs.single().durationSec)
    }

    @Test
    fun should_returnEmptyResult_when_searchResultMissing() = runTest {
        val result = client(routes = mapOf("search3" to SubsonicFixtures.ok())).search3("x", 20, 20, 20)
        assertTrue(result.artists.isEmpty() && result.albums.isEmpty() && result.songs.isEmpty())
    }

    @Test
    fun should_markEverythingStarred_when_getStarred2() = runTest {
        val starred = client(routes = mapOf("getStarred2" to SubsonicFixtures.starred2)).getStarred2()
        assertEquals("Radiohead", starred.artists.single().name)
        assertEquals("OK Computer", starred.albums.single().name)
        assertEquals("Karma Police", starred.songs.single().title)
        assertTrue(starred.artists.single().starred && starred.albums.single().starred && starred.songs.single().starred)
        assertEquals("2024-06-01", starred.songs.single().starredAt)
    }

    @Test
    fun should_mapEveryField_when_getRandomSongs() = runTest {
        val songs = client(routes = mapOf("getRandomSongs" to SubsonicFixtures.randomSongs)).getRandomSongs(25)
        assertEquals("25", lastUrl.parameters["size"])
        val s = songs[0]
        assertEquals("s-99", s.id)
        assertEquals("Full Song", s.title)
        assertEquals("Full Album", s.album)
        assertEquals("Full Artist", s.artist)
        assertEquals("al-1", s.albumId)
        assertEquals("ar-1", s.artistId)
        assertEquals(3, s.track)
        assertNull(s.disc)
        assertEquals(2023, s.year)
        assertEquals("Jazz", s.genre)
        assertEquals("s-99", s.coverArtId)
        assertEquals(8_500_000L, s.size)
        assertEquals("audio/flac", s.contentType)
        assertEquals("flac", s.suffix)
        assertEquals(300, s.durationSec)
        assertEquals(1411, s.bitRate)
        assertEquals("Full Artist/Full Album/03 - Full Song.flac", s.path)
        assertTrue(s.starred)
        assertEquals(5, s.userRating)
        assertEquals(7, s.playCount)
        assertNull(songs[1].album)
    }

    @Test
    fun should_applyAndroidOwnershipRules_when_getPlaylists() = runTest {
        val lists = client(routes = mapOf("getPlaylists" to SubsonicFixtures.playlists)).getPlaylists()
        val byId = lists.associateBy { it.id }
        // Owned by the login → editable, mine.
        assertTrue(byId.getValue("pl1").canWrite)
        assertEquals(true, byId.getValue("pl1").ownedByMe)
        assertEquals(3, byId.getValue("pl1").songCount)
        assertEquals("pl1", byId.getValue("pl1").coverArtId)
        // Someone else's public playlist → read-only, not mine; its own coverArt id wins.
        assertFalse(byId.getValue("pl2").canWrite)
        assertEquals(false, byId.getValue("pl2").ownedByMe)
        assertEquals(true, byId.getValue("pl2").isPublic)
        assertEquals("pl-pl2", byId.getValue("pl2").coverArtId)
        // readonly wins over ownership; owner matches in any case.
        assertFalse(byId.getValue("pl3").canWrite)
        assertEquals(true, byId.getValue("pl3").ownedByMe)
        // Legacy server without owner/readonly → editable, ownership unknown.
        assertTrue(byId.getValue("pl4").canWrite)
        assertNull(byId.getValue("pl4").ownedByMe)
    }

    @Test
    fun should_includeEntries_when_getPlaylist() = runTest {
        val detail = assertNotNull(client(routes = mapOf("getPlaylist" to SubsonicFixtures.playlist)).getPlaylist("pl1"))
        assertEquals("pl1", lastUrl.parameters["id"])
        assertEquals("for the road", detail.summary.comment)
        assertEquals(false, detail.summary.isPublic)
        assertEquals("2024-02-01T00:00:00Z", detail.summary.changed)
        assertEquals(listOf("One", "Two"), detail.entries.map { it.title })
    }

    // ---------------------------------------------------------- write actions

    @Test
    fun should_sendOnlyGivenIdKind_when_starAndUnstar() = runTest {
        val c = client(routes = mapOf("star" to SubsonicFixtures.ok(), "unstar" to SubsonicFixtures.ok()))
        c.star(null, null, "ar-1")
        assertEquals("/navidrome/rest/star.view", lastUrl.encodedPath)
        assertEquals("ar-1", lastUrl.parameters["artistId"])
        assertNull(lastUrl.parameters["id"])
        assertNull(lastUrl.parameters["albumId"])
        c.unstar("s-1", null, null)
        assertEquals("/navidrome/rest/unstar.view", lastUrl.encodedPath)
        assertEquals("s-1", lastUrl.parameters["id"])
        assertNull(lastUrl.parameters["artistId"])
        c.star(null, "al-1", null)
        assertEquals("al-1", lastUrl.parameters["albumId"])
    }

    @Test
    fun should_clampRating_when_setRating() = runTest {
        val c = client(routes = mapOf("setRating" to SubsonicFixtures.ok()))
        c.setRating("s-1", 4)
        assertEquals("4", lastUrl.parameters["rating"])
        c.setRating("s-1", 9)
        assertEquals("5", lastUrl.parameters["rating"])
        c.setRating("s-1", -2)
        assertEquals("0", lastUrl.parameters["rating"])
    }

    @Test
    fun should_sendSubmissionAndTime_when_scrobble() = runTest {
        val c = client(routes = mapOf("scrobble" to SubsonicFixtures.ok()))
        c.scrobble("s-1", true, 1_700_000_000_000L)
        assertEquals("s-1", lastUrl.parameters["id"])
        assertEquals("true", lastUrl.parameters["submission"])
        assertEquals("1700000000000", lastUrl.parameters["time"])
        c.scrobble("s-1", false, null)
        assertEquals("false", lastUrl.parameters["submission"])
        assertNull(lastUrl.parameters["time"])
    }

    // ---------------------------------------------------------------- lyrics

    @Test
    fun should_sortAndOffsetSyncedLines_when_structuredLyrics() = runTest {
        val lyrics = assertNotNull(
            client(routes = mapOf("getLyricsBySongId" to SubsonicFixtures.structuredLyrics)).getLyricsBySongId("s-1"),
        )
        assertTrue(lyrics.synced)
        assertEquals("eng", lyrics.language)
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
        assertEquals(listOf(0L, 4_900L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun should_keepTextUnsynced_when_structuredLyricsNotSynced() = runTest {
        val lyrics = assertNotNull(
            client(routes = mapOf("getLyricsBySongId" to SubsonicFixtures.unsyncedStructuredLyrics)).getLyricsBySongId("s-1"),
        )
        assertFalse(lyrics.synced)
        assertEquals("one\ntwo", lyrics.text)
    }

    @Test
    fun should_fallBackToGetLyrics_when_structuredEmpty() = runTest {
        val c = client(
            routes = mapOf(
                "getLyricsBySongId" to SubsonicFixtures.emptyLyricsList,
                "getLyrics" to SubsonicFixtures.plainLyrics,
            ),
        )
        val lyrics = assertNotNull(c.lyricsFor("s-1", "Queen", "Bohemian Rhapsody"))
        assertEquals("Queen", lastUrl.parameters["artist"])
        assertEquals("Bohemian Rhapsody", lastUrl.parameters["title"])
        assertFalse(lyrics.synced)
        assertEquals(listOf("Is this the real life?", "Is this just fantasy?"), lyrics.lines.map { it.text })
    }

    @Test
    fun should_fallBackToGetLyrics_when_serverLacksOpenSubsonicEndpoint() = runTest {
        // Unknown endpoint → Subsonic error 0 / 70 from plain servers; getLyrics' value may be LRC.
        val c = client(routes = mapOf("getLyrics" to SubsonicFixtures.plainLrcLyrics))
        val lyrics = assertNotNull(c.lyricsFor("s-1", "A", "B"))
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_000L, 2_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun should_notFallBack_when_structuredLyricsFound() = runTest {
        val c = client(routes = mapOf("getLyricsBySongId" to SubsonicFixtures.structuredLyrics))
        assertTrue(assertNotNull(c.lyricsFor("s-1", "A", "B")).synced)
        assertEquals(1, requests.size)
    }

    @Test
    fun should_rethrowAuthFailure_when_lyricsForFails() = runTest {
        val c = client(routes = mapOf("getLyricsBySongId" to SubsonicFixtures.failed(40, "bad")))
        val e = assertFailsWith<SubsonicException> { c.lyricsFor("s-1", "A", "B") }
        assertEquals(SubsonicErrorKind.WRONG_CREDENTIALS, e.kind)
    }

    @Test
    fun should_returnNull_when_noLyricsAnywhere() = runTest {
        val c = client(
            routes = mapOf(
                "getLyricsBySongId" to SubsonicFixtures.emptyLyricsList,
                "getLyrics" to SubsonicFixtures.ok(""""lyrics": {}"""),
            ),
        )
        assertNull(c.lyricsFor("s-1", "A", "B"))
    }

    // ---------------------------------------------------------- URL builders

    @Test
    fun should_buildAndroidShapedStreamUrl_when_noOptions() {
        val c = client(baseUrl = "https://music.example.com/", routes = emptyMap())
        assertEquals(
            "https://music.example.com/rest/stream.view?id=s-1&u=alice&t=26719a1196d2a940705a59634eb18eab" +
                "&s=c19b2d&v=1.16.1&c=Yoin&f=json",
            c.streamUrl("s-1", null, null),
        )
        assertTrue(requests.isEmpty(), "URL builders never touch the network")
    }

    @Test
    fun should_addBitRateAndFormat_when_streamOptionsGiven() {
        val url = Url(client(routes = emptyMap()).streamUrl("s 1", 320, "mp3"))
        assertEquals("/navidrome/rest/stream.view", url.encodedPath)
        assertEquals("s 1", url.parameters["id"])
        assertEquals("320", url.parameters["maxBitRate"])
        assertEquals("mp3", url.parameters["format"])
    }

    @Test
    fun should_buildCoverArtUrl_when_sizeOptional() {
        val c = client(baseUrl = "http://127.0.0.1:4533", routes = emptyMap())
        val sized = Url(c.coverArtUrl("al-1", 600))
        assertEquals("/rest/getCoverArt.view", sized.encodedPath)
        assertEquals(4533, sized.port)
        assertEquals("al-1", sized.parameters["id"])
        assertEquals("600", sized.parameters["size"])
        assertNull(Url(c.coverArtUrl("al-1", null)).parameters["size"])
    }
}
