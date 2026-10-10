package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Library albums open as their full catalog album; membership rides on the tracks. */
class AppleMusicAlbumAlignmentTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AppleMusicSource

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        source = AppleMusicSource(
            ProfileCredentials.AppleMusic("https://token.test", "user"),
            api = AppleMusicApiClient({ "developer" }, { "user" }, baseUrl = server.url("/"))
        )
    }

    @After fun cleanup() = server.shutdown()

    @Test fun should_openLibraryAlbumAsFullCatalogAlbum_andMarkAddedTracks() = runTest {
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = "900")),
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900")),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to "11")),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11", "12"))
        )

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "900"), album.id)
        assertEquals(listOf("10", "11", "12"), album.tracks.map { it.id.rawId })
        assertEquals(3, album.songCount)
        val byId = album.tracks.associateBy { it.id.rawId }
        assertEquals("i.1", byId.getValue("10").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertEquals("i.3", byId.getValue("11").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertNull(byId.getValue("12").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        assertTrue("Library membership is not a favorite", album.tracks.none { it.isStarred })

        // The library tracklist is read alongside the library album, so arrival order is not fixed.
        val requests = List(5) { server.takeRequest() }.associateBy { it.requestUrl!!.encodedPath }
        assertEquals(
            setOf(
                "/v1/me/library/albums/l.1",
                "/v1/me/library/albums/l.1/tracks",
                "/v1/me/storefront",
                "/v1/catalog/jp/albums/900",
                "/v1/catalog/jp/albums/900/tracks"
            ),
            requests.keys
        )
        val catalog = requests.getValue("/v1/catalog/jp/albums/900")
        assertEquals("artists,library", catalog.requestUrl!!.queryParameter("include"))
        assertEquals("albums,artists", catalog.requestUrl!!.queryParameter("include[songs]"))
        assertEquals("user", catalog.getHeader("Music-User-Token"))
        val libraryTracks = requests.getValue("/v1/me/library/albums/l.1/tracks")
        assertEquals("catalog", libraryTracks.requestUrl!!.queryParameter("include"))
        assertEquals(5, server.requestCount)
    }

    @Test fun should_requestLibraryTracksWithFirstRequest_when_openingLibraryAlbum() = runTest {
        // The library album is answered only once its tracklist has been asked for: a read that waited for the
        // library album (or the catalog album after it) would wait out the latch and get a 500 for the album.
        val tracksAsked = CountDownLatch(1)
        val requested = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requested += path
                return when (path) {
                    "/v1/me/library/albums/l.1" ->
                        if (tracksAsked.await(5, TimeUnit.SECONDS)) {
                            body(libraryAlbum("l.1", catalogId = "900"))
                        } else {
                            MockResponse().setResponseCode(500)
                        }
                    "/v1/me/library/albums/l.1/tracks" -> {
                        tracksAsked.countDown()
                        body(librarySongs("i.1" to "10", "i.2" to "10"))
                    }
                    "/v1/me/storefront" -> body(storefront())
                    "/v1/catalog/jp/albums/900" -> body(catalogAlbum("900", tracks = embedded(song("10"), song("11"))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "900"), album.id)
        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertEquals("i.1", album.tracks.first().extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertNull(album.tracks.last().extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        // Each read once; the catalog tracklist came with the catalog album.
        assertEquals(
            listOf(
                "/v1/me/library/albums/l.1",
                "/v1/me/library/albums/l.1/tracks",
                "/v1/me/storefront",
                "/v1/catalog/jp/albums/900"
            ).sorted(),
            requested.sorted()
        )
    }

    @Test fun should_skipCatalogTracksRequest_when_albumEmbedsCompleteTracks() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(
                catalogAlbum(
                    "900",
                    tracks = embedded(song("10"), musicVideo("50"), song("11", artistId = "8"), song("10"))
                )
            )
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        // Order kept, the music video left out, the repeated identity listed once.
        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertEquals(2, album.songCount)
        // Each song keeps its own artist (a featured artist, not the album's 7) and album as navigation targets.
        assertEquals(MediaId("applemusic", "8"), album.tracks.last().artistId)
        assertEquals(MediaId("applemusic", "7"), album.tracks.first().artistId)
        assertTrue(album.tracks.all { it.albumId == MediaId("applemusic", "900") })
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_ID) })
        server.takeRequest()
        val catalog = server.takeRequest().requestUrl!!
        assertEquals("artists,library", catalog.queryParameter("include"))
        assertEquals("albums,artists", catalog.queryParameter("include[songs]"))
        assertEquals(2, server.requestCount)
    }

    @Test fun should_fetchCatalogTracks_when_embeddedTracksHaveNextPage() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(
                catalogAlbum("900", tracks = embedded(song("10"), next = "/v1/catalog/jp/albums/900/tracks?offset=300"))
            ),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11", "12"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10", "11", "12"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        repeat(2) { server.takeRequest() }
        val tracklist = server.takeRequest().requestUrl!!
        // The whole tracklist from its first page, with the songs' relationships as before.
        assertEquals("/v1/catalog/jp/albums/900/tracks", tracklist.encodedPath)
        assertNull(tracklist.queryParameter("offset"))
        assertEquals("albums,artists", tracklist.queryParameter("include"))
        assertEquals(3, server.requestCount)
    }

    @Test fun should_fetchCatalogTracks_when_embeddedSongsLackRelationships() = runTest {
        // Apple leaving out the songs' relationships (an ignored include[songs]) loses no navigation targets.
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(
                catalogAlbum("900", tracks = embedded(song("10"), song("11", relationships = false)))
            ),
            "/v1/catalog/jp/albums/900/tracks" to body(
                """{"data":[${song("10")},${song("11", artistId = "8")}]}"""
            )
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(MediaId("applemusic", "8"), album.tracks.single { it.id.rawId == "11" }.artistId)
        assertEquals(3, server.requestCount)
    }

    @Test fun should_fetchCatalogTracks_when_embeddedTracklistIsEmpty() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", tracks = embedded())),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10"), album.tracks.map { it.id.rawId })
        assertEquals(3, server.requestCount)
    }

    @Test fun should_readAlbumWithoutSongRelationships_when_appleRefusesThem() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to MockResponse().setResponseCode(400),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", libraryId = "l.9")),
            "/v1/me/library/albums/l.9/tracks" to body(librarySongs("i.5" to "11")),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        // The personal read still answered, so the rows are marked.
        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertEquals("i.5", album.tracks.last().extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        val albumReads = List(5) { server.takeRequest().requestUrl!! }
            .filter { it.encodedPath == "/v1/catalog/jp/albums/900" }
        assertEquals(listOf("albums,artists", null), albumReads.map { it.queryParameter("include[songs]") })
        assertEquals(listOf("artists,library", "artists,library"), albumReads.map { it.queryParameter("include") })
        assertEquals(5, server.requestCount)
    }

    @Test fun should_failAlbum_when_libraryAlbumIsRateLimited_evenIfItsTracksArrive() = runTest {
        route(
            "/v1/me/library/albums/l.1" to MockResponse().setResponseCode(429),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10"))
        )

        val failure = runCatching { source.getAlbum(MediaId("applemusic", "library:l.1")) }.exceptionOrNull()

        assertEquals(AppleMusicApiFailure.RateLimited, (failure as AppleMusicApiException).failure)
    }

    @Test fun should_openCatalogAlbumUnmarked_when_libraryAlbumTracksFail() = runTest {
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = "900")),
            "/v1/me/library/albums/l.1/tracks" to MockResponse().setResponseCode(404),
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", tracks = embedded(song("10"), song("11"))))
        )

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_ID) })
    }

    @Test fun should_failAlbum_when_libraryAlbumTracksAreRateLimited() = runTest {
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = "900")),
            "/v1/me/library/albums/l.1/tracks" to MockResponse().setResponseCode(429),
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", tracks = embedded(song("10"))))
        )

        val failure = runCatching { source.getAlbum(MediaId("applemusic", "library:l.1")) }.exceptionOrNull()

        assertEquals(AppleMusicApiFailure.RateLimited, (failure as AppleMusicApiException).failure)
    }

    @Test fun should_useFirstLibraryRead_when_catalogTracklistFailsAndAlbumFallsBack() = runTest {
        // The catalog tracklist 404s, with its relationships and without; everything unrouted 404s.
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = "900")),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to null)),
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900"))
        )

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "library:l.1"), album.id)
        assertEquals(listOf("10", "library:i.3"), album.tracks.map { it.id.rawId })
        val paths = List(server.requestCount) { server.takeRequest().requestUrl!!.encodedPath }
        assertEquals(1, paths.count { it == "/v1/me/library/albums/l.1/tracks" })
        assertEquals(2, paths.count { it == "/v1/catalog/jp/albums/900/tracks" })
    }

    @Test fun should_stopOpening_when_cancelledWhileLibraryTracksAreInFlight() = runBlocking {
        val bothAsked = CountDownLatch(2)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = when (request.requestUrl!!.encodedPath) {
                    "/v1/me/library/albums/l.1" -> body(libraryAlbum("l.1", catalogId = "900"))
                    "/v1/me/library/albums/l.1/tracks" -> body(librarySongs("i.1" to "10"))
                    else -> return MockResponse().setResponseCode(404)
                }
                bothAsked.countDown()
                release.await(5, TimeUnit.SECONDS)
                return response
            }
        }

        val open = async(Dispatchers.Default) { source.getAlbum(MediaId("applemusic", "library:l.1")) }
        assertTrue(bothAsked.await(5, TimeUnit.SECONDS))
        open.cancel()
        release.countDown()

        assertTrue(runCatching { open.await() }.exceptionOrNull() is CancellationException)
        // Cancelled with both reads in flight: nothing after them was asked for.
        assertEquals(2, server.requestCount)
    }

    @Test fun should_readBothTracklistsAtOnce_when_albumHasLibraryCopy() = runTest {
        // Each tracklist is answered only once the other one has been asked for: a one-after-the-other read
        // would wait out the latch and get a 500 for its first tracklist.
        val bothAsked = CountDownLatch(2)
        val tracklists = mapOf(
            "/v1/me/library/albums/l.9/tracks" to librarySongs("i.5" to "11"),
            "/v1/catalog/jp/albums/900/tracks" to catalogSongs("10", "11")
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                tracklists[path]?.let { tracks ->
                    bothAsked.countDown()
                    val answered = bothAsked.await(5, TimeUnit.SECONDS)
                    return if (answered) body(tracks) else MockResponse().setResponseCode(500)
                }
                return when (path) {
                    "/v1/me/storefront" -> body(storefront())
                    "/v1/catalog/jp/albums/900" -> body(catalogAlbum("900", libraryId = "l.9"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertEquals("i.5", album.tracks.single { it.id.rawId == "11" }.extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
    }

    @Test fun should_failAlbum_when_libraryCopyTracksAreRateLimited() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", libraryId = "l.9")),
            "/v1/me/library/albums/l.9/tracks" to MockResponse().setResponseCode(429),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11"))
        )

        val failure = runCatching { source.getAlbum(MediaId("applemusic", "900")) }.exceptionOrNull()

        // Read in parallel or not, the user's copy failing like this fails the album (as it did read first).
        assertEquals(AppleMusicApiFailure.RateLimited, (failure as AppleMusicApiException).failure)
    }

    @Test fun should_markEveryTrackNotAdded_when_catalogAlbumHasNoLibraryCopy() = runTest {
        reply(storefront())
        reply(catalogAlbum("900"))
        reply(catalogSongs("10", "11"))

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(2, album.tracks.size)
        assertTrue(album.tracks.all { it.extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] == "true" })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_ID) })
        assertEquals(3, server.requestCount)
    }

    @Test fun should_fetchLibraryCopy_when_catalogAlbumIsInLibrary() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", libraryId = "l.9")),
            "/v1/me/library/albums/l.9/tracks" to body(librarySongs("i.5" to "11")),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        val byId = album.tracks.associateBy { it.id.rawId }
        assertNull(byId.getValue("10").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertEquals("i.5", byId.getValue("11").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        server.takeRequest()
        server.takeRequest()
        val tracklists = List(2) { server.takeRequest().requestUrl!!.encodedPath }
        assertTrue("/v1/me/library/albums/l.9/tracks" in tracklists)
    }

    @Test fun should_openCatalogAlbumUnmarked_when_libraryLookupFails() = runTest {
        // The personal read fails with and without the songs' relationships; the tracklist is read meanwhile.
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to MockResponse().setResponseCode(500),
            "/v1/catalog/jp/albums/900" to MockResponse().setResponseCode(500),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900")),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        val albumReads = List(5) { server.takeRequest() }
            .filter { it.requestUrl!!.encodedPath == "/v1/catalog/jp/albums/900" }
        assertEquals(
            listOf("artists,library", "artists,library", "artists"),
            albumReads.map { it.requestUrl!!.queryParameter("include") }
        )
        assertEquals(
            listOf("albums,artists", null, null),
            albumReads.map { it.requestUrl!!.queryParameter("include[songs]") }
        )
        assertNull(albumReads.last().getHeader("Music-User-Token"))
    }

    @Test fun should_openCatalogAlbum_when_tracklistRelationshipsFail() = runTest {
        reply(storefront())
        reply(catalogAlbum("900"))
        server.enqueue(MockResponse().setResponseCode(500))
        reply(catalogSongs("10", "11"))

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.all { it.albumId == MediaId("applemusic", "900") })
        repeat(2) { server.takeRequest() }
        assertEquals("albums,artists", server.takeRequest().requestUrl!!.queryParameter("include"))
        assertNull(server.takeRequest().requestUrl!!.queryParameter("include"))
    }

    @Test fun should_openCatalogAlbumUnmarked_when_libraryCopyTracksFail() = runTest {
        route(
            "/v1/me/storefront" to body(storefront()),
            "/v1/catalog/jp/albums/900" to body(catalogAlbum("900", libraryId = "l.9")),
            "/v1/me/library/albums/l.9/tracks" to MockResponse().setResponseCode(404),
            "/v1/catalog/jp/albums/900/tracks" to body(catalogSongs("10", "11"))
        )

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(2, album.tracks.size)
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_ID) })
    }

    @Test fun should_failAlbum_when_libraryLookupIsRateLimited() = runTest {
        reply(storefront())
        server.enqueue(MockResponse().setResponseCode(429))

        val failure = runCatching { source.getAlbum(MediaId("applemusic", "900")) }.exceptionOrNull()

        assertEquals(AppleMusicApiFailure.RateLimited, (failure as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun should_keepLibraryTracklist_when_catalogMatchIsNotServed() = runTest {
        // The catalog album 404s (as everything unrouted does), personal and public.
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = "900")),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10", "i.3" to null)),
            "/v1/me/storefront" to body(storefront())
        )

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "library:l.1"), album.id)
        assertEquals(listOf("10", "library:i.3"), album.tracks.map { it.id.rawId })
        val requests = List(server.requestCount) { server.takeRequest().requestUrl!! }
        // The tracklist read with the library album is the one the fallback uses: it is not asked for again.
        assertEquals(1, requests.count { it.encodedPath == "/v1/me/library/albums/l.1/tracks" })
        // A 404 is not a refused include: no second personal read, straight to the public one.
        assertEquals(
            listOf("artists,library", "artists"),
            requests.filter { it.encodedPath == "/v1/catalog/jp/albums/900" }.map { it.queryParameter("include") }
        )
    }

    @Test fun should_keepLibraryTracklistDeduplicated_when_albumHasNoCatalogMatch() = runTest {
        route(
            "/v1/me/library/albums/l.1" to body(libraryAlbum("l.1", catalogId = null)),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to null))
        )

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "library:l.1"), album.id)
        assertEquals(listOf("10", "library:i.3"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        assertEquals(2, server.requestCount)
    }

    @Test fun should_returnNull_when_libraryAlbumIsGone() = runTest {
        route(
            "/v1/me/library/albums/l.1" to body("""{"data":[]}"""),
            "/v1/me/library/albums/l.1/tracks" to body(librarySongs("i.1" to "10"))
        )

        assertNull(source.getAlbum(MediaId("applemusic", "library:l.1")))
    }

    @Test fun should_deduplicateSavedSongs_when_twoLibrarySongsShareOneCatalogSong() = runTest {
        reply(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to "11"))

        val songs = source.getLibrarySongs(size = 3)

        assertEquals(listOf("10", "11"), songs.map { it.id.rawId })
        assertEquals(1, server.requestCount)
    }

    @Test fun should_listLibraryAlbumsUnderCatalogIdentity_when_appleKnowsTheCatalogAlbum() = runTest {
        reply(
            """
{"data":[
  {"id":"l.1","type":"library-albums","attributes":{"name":"Matched"},
   "relationships":{"catalog":{"data":[{"id":"900","type":"albums"}]}}},
  {"id":"l.2","type":"library-albums","attributes":{"name":"Imported"},
   "relationships":{"catalog":{"data":[]}}}
]}
"""
        )

        val albums = source.getAlbumList("alphabeticalByName", 10, 0)

        assertEquals(listOf("900", "library:l.2"), albums.map { it.id.rawId })
        val request = server.takeRequest()
        assertEquals("/v1/me/library/albums", request.requestUrl!!.encodedPath)
        assertEquals("catalog", request.requestUrl!!.queryParameter("include"))
    }

    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))

    private fun body(body: String) = MockResponse().setBody(body)

    /**
     * Serves by path instead of in arrival order (each path's responses once, in order; anything else 404s):
     * an album's two tracklists are read in parallel, so which reaches the server first is not fixed.
     */
    private fun route(vararg responses: Pair<String, MockResponse>) {
        val byPath = responses.groupBy({ it.first }, { it.second }).mapValues { (_, list) -> ArrayDeque(list) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(byPath) {
                byPath[request.requestUrl!!.encodedPath]?.removeFirstOrNull()
            } ?: MockResponse().setResponseCode(404)
        }
    }

    private fun storefront() = """{"data":[{"id":"jp"}]}"""

    private fun libraryAlbum(id: String, catalogId: String?) = """
{"data":[{"id":"$id","type":"library-albums","attributes":{"name":"Album","trackCount":3},
 "relationships":{"catalog":{"data":[${catalogId?.let { """{"id":"$it","type":"albums"}""" } ?: ""}]}}}]}
"""

    /** [tracks] is the album's `tracks` relationship as Apple embeds it; without it the album embeds none. */
    private fun catalogAlbum(id: String, libraryId: String? = null, tracks: String? = null) = """
{"data":[{"id":"$id","type":"albums","attributes":{"name":"Album","artistName":"Artist","trackCount":3},
 "relationships":{
   "artists":{"data":[{"id":"7","type":"artists"}]},
   "library":{"data":[${libraryId?.let { """{"id":"$it","type":"library-albums"}""" } ?: ""}]}${tracks?.let { ""","tracks":$it""" } ?: ""}
 }}]}
"""

    private fun embedded(vararg tracks: String, next: String? = null): String {
        val nextLink = next?.let { ""","next":"$it"""" } ?: ""
        return """{"href":"/v1/catalog/jp/albums/900/tracks","data":[${tracks.joinToString(",")}]$nextLink}"""
    }

    /** A catalog song of album 900 with its album and artist relationships, as include[songs] asks for. */
    private fun song(id: String, artistId: String = "7", relationships: Boolean = true): String {
        val related = """,
 "relationships":{"albums":{"data":[{"id":"900","type":"albums"}]},"artists":{"data":[{"id":"$artistId","type":"artists"}]}}"""
        val attributes = """"attributes":{"name":"Song $id","durationInMillis":1000}"""
        return """{"id":"$id","type":"songs",$attributes${if (relationships) related else ""}}"""
    }

    private fun musicVideo(id: String) = """{"id":"$id","type":"music-videos","attributes":{"name":"Video $id"}}"""

    private fun librarySongs(vararg songs: Pair<String, String?>) = """
{"data":[${songs.joinToString(",") { (libraryId, catalogId) ->
        """{"id":"$libraryId","type":"library-songs","attributes":{"name":"$libraryId"},
   "relationships":{"catalog":{"data":[${catalogId?.let { """{"id":"$it","type":"songs"}""" } ?: ""}]}}}"""
    }}]}
"""

    private fun catalogSongs(vararg ids: String) = """
{"data":[${ids.joinToString(",") { """{"id":"$it","type":"songs","attributes":{"name":"Song $it","durationInMillis":1000}}""" }}]}
"""
}
