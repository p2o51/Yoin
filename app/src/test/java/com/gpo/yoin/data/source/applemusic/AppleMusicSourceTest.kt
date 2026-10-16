package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.WebLinkKind
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppleMusicSourceTest {
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

    @Test fun should_loadEveryPlaylistPage_andKeepLibrarySeparateFromFavorites() = runTest {
        reply(
            """
{
  "data": [
    {
      "id": "p.test",
      "type": "library-playlists",
      "attributes": {
        "name": "Mine",
        "canEdit": true
      }
    }
  ]
}
"""
        )
        reply(
            """
{
  "data": [
    {
      "id": "i.1",
      "type": "library-songs",
      "attributes": {
        "name": "One",
        "playParams": {
          "catalogId": "123"
        }
      }
    }
  ],
  "next": "/v1/me/library/playlists/p.test/tracks?offset=1"
}
"""
        )
        reply(
            """
{
  "data": [
    {
      "id": "i.2",
      "type": "library-songs",
      "attributes": {
        "name": "Two",
        "playParams": {
          "catalogId": "456"
        }
      }
    }
  ]
}
"""
        )
        val playlist = source.getPlaylist(MediaId("applemusic", "library:p.test"))!!
        assertEquals(listOf("123", "456"), playlist.tracks.map { it.id.rawId })
        assertEquals(2, playlist.songCount)
        assertFalse(playlist.canWrite)
        assertTrue(playlist.tracks.none { it.isStarred })
        repeat(3) { assertEquals("user", server.takeRequest().getHeader("Music-User-Token")) }
        assertTrue(source.setFavorite(playlist.tracks.first().id, true).isFailure)
        assertTrue(source.getStarred().tracks.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun should_openCatalogPlaylist_when_libraryRefusesASavedPlaylist() = runTest {
        val catalogTracks = mutableListOf<RecordedRequest>()
        server.dispatcher = playlistDispatcher(
            libraryPlaylist = MockResponse().setResponseCode(403),
            onCatalogTracks = { catalogTracks += it },
        )
        source.getPlaylists()
        val playlist = source.getPlaylist(MediaId("applemusic", "library:p.saved"))!!
        // The library's id and name, the catalog playlist's tracks, read without the music user token.
        assertEquals(MediaId("applemusic", "library:p.saved"), playlist.id)
        assertEquals("Saved", playlist.name)
        assertEquals(listOf("123"), playlist.tracks.map { it.id.rawId })
        assertEquals(1, playlist.songCount)
        assertNull(catalogTracks.single().getHeader("Music-User-Token"))
    }

    @Test fun should_openCatalogPlaylist_when_libraryRefusesOnlyTheTracks() = runTest {
        server.dispatcher = playlistDispatcher(
            libraryPlaylist = json("""{"data":[$SavedLibraryPlaylist]}"""),
            libraryTracks = MockResponse().setResponseCode(403),
        )
        // Not listed first: the library playlist's own answer names its catalog playlist.
        val playlist = source.getPlaylist(MediaId("applemusic", "library:p.saved"))!!
        assertEquals(listOf("123"), playlist.tracks.map { it.id.rawId })
    }

    @Test fun should_failAsBefore_when_aLibraryOnlyPlaylistIsRefused() = runTest {
        server.dispatcher = playlistDispatcher(
            listed = """{"id":"p.mine","type":"library-playlists","attributes":{"name":"Mine",""" +
                """"playParams":{"id":"p.mine","isLibrary":true,"kind":"playlist"}}}""",
            libraryPlaylist = MockResponse().setResponseCode(403),
        )
        source.getPlaylists()
        val error = runCatching { source.getPlaylist(MediaId("applemusic", "library:p.mine")) }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.AccessDenied, (error as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun should_keepTheLibraryFailure_when_theCatalogPlaylistFailsToo() = runTest {
        server.dispatcher = playlistDispatcher(
            libraryPlaylist = MockResponse().setResponseCode(403),
            catalogPlaylist = MockResponse().setResponseCode(404),
        )
        source.getPlaylists()
        val error = runCatching { source.getPlaylist(MediaId("applemusic", "library:p.saved")) }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.AccessDenied, (error as AppleMusicApiException).failure)
        assertEquals(
            AppleMusicApiFailure.Http(404),
            (error.suppressed.single() as AppleMusicApiException).failure,
        )
    }

    @Test fun should_notReadTheCatalog_when_appleRateLimitsTheLibrary() = runTest {
        server.dispatcher = playlistDispatcher(libraryPlaylist = MockResponse().setResponseCode(429))
        source.getPlaylists()
        val error = runCatching { source.getPlaylist(MediaId("applemusic", "library:p.saved")) }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.RateLimited, (error as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    /** A library with one playlist saved from the catalog (`pl.u-shared`), each read answered by path. */
    private fun playlistDispatcher(
        listed: String = SavedLibraryPlaylist,
        libraryPlaylist: MockResponse,
        libraryTracks: MockResponse = json("""{"data":[]}"""),
        catalogPlaylist: MockResponse = json(
            """{"data":[{"id":"pl.u-shared","type":"playlists",""" +
                """"attributes":{"name":"Saved","curatorName":"Someone"}}]}"""
        ),
        onCatalogTracks: (RecordedRequest) -> Unit = {},
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
            "/v1/me/library/playlists" -> json("""{"data":[$listed]}""")
            "/v1/me/library/playlists/p.saved", "/v1/me/library/playlists/p.mine" -> libraryPlaylist
            "/v1/me/library/playlists/p.saved/tracks" -> libraryTracks
            "/v1/me/storefront" -> json("""{"data":[{"id":"jp"}]}""")
            "/v1/catalog/jp/playlists/pl.u-shared" -> catalogPlaylist
            "/v1/catalog/jp/playlists/pl.u-shared/tracks" -> {
                onCatalogTracks(request)
                json("""{"data":[{"id":"123","type":"songs","attributes":{"name":"One"}}]}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun json(body: String) = MockResponse().setBody(body)

    @Test fun should_rejectCrossOriginRelationshipPagination_beforeSendingSecrets() = runTest {
        reply(
            """
{
  "data": [
    {
      "id": "p.test",
      "type": "library-playlists",
      "attributes": {
        "name": "Mine"
      }
    }
  ]
}
"""
        )
        reply(
            """
{
  "data": [],
  "next": "https://untrusted.example/v1/me/library/playlists/p.test/tracks"
}
"""
        )
        assertTrue(
            runCatching {
                source.getPlaylist(MediaId("applemusic", "library:p.test"))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertEquals(2, server.requestCount)
    }

    @Test fun should_searchCatalogWithRegion_andPreserveNavigationIds() = runTest {
        reply(
            """
{
  "data": [
    {
      "id": "jp"
    }
  ]
}
"""
        )
        reply(
            """
{
  "results": {
    "songs": {
      "data": [
        {
          "id": "123",
          "type": "songs",
          "attributes": {
            "name": "Title"
          },
          "relationships": {
            "albums": {
              "data": [
                {
                  "id": "12",
                  "type": "albums"
                }
              ]
            },
            "artists": {
              "data": [
                {
                  "id": "34",
                  "type": "artists"
                }
              ]
            }
          }
        }
      ]
    }
  }
}
"""
        )
        val track = source.search("日本 & Music").tracks.single()
        assertEquals(MediaId("applemusic", "12"), track.albumId)
        assertEquals(MediaId("applemusic", "34"), track.artistId)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/v1/catalog/jp/search", request.requestUrl!!.encodedPath)
        assertEquals("日本 & Music", request.requestUrl!!.queryParameter("term"))
        assertNull(request.getHeader("Music-User-Token"))
        assertEquals(
            PlaybackHandle.ExternalController(PlaybackHandle.ControllerType.APPLE_MUSIC_KIT, "123"),
            source.handleFor(track)
        )
    }

    @Test fun should_rejectUnmatchedImport_insteadOfTreatingPreviewAsPlayback() = runTest {
        val track = AppleMusicSong.fromJson(
            Json.parseToJsonElement(
                """
{
  "id": "i.import",
  "type": "library-songs",
  "attributes": {
    "name": "Imported"
  }
}
"""
            ).jsonObject
        ).toTrack()
        val failure = runCatching { source.handleFor(track) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("imported song"))
        assertEquals(0, server.requestCount)
    }

    @Test fun should_refuseWithTheImportMessage_when_anImportComesWithAnEmptyCatalog() = runTest {
        // Library › Songs includes `catalog`, which Apple answers empty for an import as well.
        val track = AppleMusicSong.fromJson(
            Json.parseToJsonElement(
                """{"id": "i.import", "type": "library-songs", "attributes": {"name": "Imported"},
                 "relationships": {"catalog": {"data": []}}}"""
            ).jsonObject
        ).toTrack()
        // A row an earlier build marked both ways is still refused as the import it is.
        val marked = track.copy(extras = track.extras + (AppleMusicSong.EXTRA_CATALOG_UNAVAILABLE to "true"))

        assertNull(track.extras[AppleMusicSong.EXTRA_CATALOG_UNAVAILABLE])
        assertTrue(track.isUnplayableAppleImport)
        listOf(track, marked).forEach { row ->
            val failure = runCatching { source.handleFor(row) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("imported song"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun should_refuseWithoutTextOfItsOwn_when_theStorefrontHasNoCatalogSong() = runTest {
        // The refusal adds no English-only line: PlaybackManager shows its localized "Unable to play this song".
        val pulled = AppleMusicSong.fromJson(
            Json.parseToJsonElement(
                """{"id": "i.pulled", "type": "library-songs", "attributes": {"playParams": {"catalogId": "321"}},
                 "relationships": {"catalog": {"data": []}}}"""
            ).jsonObject
        ).toTrack()

        val failure = runCatching { source.handleFor(pulled) }.exceptionOrNull()

        assertEquals("true", pulled.extras[AppleMusicSong.EXTRA_CATALOG_UNAVAILABLE])
        assertTrue(failure is IllegalArgumentException)
        assertNull(failure!!.message)
    }

    // PlaybackManager leaves isUnplayableAppleImport tracks out before MusicKit
    // sees them: the predicate must name exactly the tracks handleFor refuses.
    @Test fun should_refuseExactlyTheTracksMarkedUnplayable_when_resolvingPlayback() = runTest {
        val parsed = listOf(
            """{"id": "123", "type": "songs", "attributes": {"name": "Catalog"}}""",
            """{"id": "i.matched", "type": "library-songs", "attributes": {"playParams": {"catalogId": "456"}}}""",
            """{"id": "i.import", "type": "library-songs", "attributes": {"name": "Imported"}}"""
        ).map { json -> AppleMusicSong.fromJson(Json.parseToJsonElement(json).jsonObject).toTrack() }
        // A library id that carries its catalog id in extras only.
        val extrasOnly = parsed.last().let { it.copy(extras = it.extras + (AppleMusicSong.EXTRA_CATALOG_ID to "789")) }
        // Its play parameters name a catalog song the storefront doesn't have.
        val pulled = AppleMusicSong.fromJson(
            Json.parseToJsonElement(
                """{"id": "i.pulled", "type": "library-songs", "attributes": {"playParams": {"catalogId": "321"}},
                 "relationships": {"catalog": {"data": []}}}"""
            ).jsonObject
        ).toTrack()
        val tracks = parsed + extrasOnly + pulled

        assertEquals(listOf(false, false, true, false, true), tracks.map { it.isUnplayableAppleImport })
        tracks.forEach { track ->
            val refused = runCatching { source.handleFor(track) }.isFailure
            assertEquals(track.id.toString(), track.isUnplayableAppleImport, refused)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun should_searchOnlyUserLibrary_when_libraryScopeIsSelected() = runTest {
        reply(
            """{
              "results":{
                "library-songs":{"data":[{
                  "id":"i.saved","type":"library-songs",
                  "attributes":{"name":"Saved","playParams":{"catalogId":"123"}}
                }]},
                "library-albums":{"data":[{"id":"l.album","type":"library-albums","attributes":{"name":"Album"}}]},
                "library-artists":{"data":[{"id":"r.artist","type":"library-artists","attributes":{"name":"Artist"}}]},
                "library-playlists":{"data":[{"id":"p.list","type":"library-playlists","attributes":{"name":"List"}}]}
              }
            }"""
        )
        reply("""{"data":[{"id":"i.saved","type":"library-songs","relationships":{"catalog":{"data":[{"id":"123","type":"songs"}]}}}]}""")
        val results = source.searchLibrary("日本 & Music")
        assertEquals(MediaId("applemusic", "123"), results.tracks.single().id)
        assertEquals("i.saved", results.tracks.single().extras["appleMusicLibraryId"])
        assertEquals(MediaId("applemusic", "library:l.album"), results.albums.single().id)
        assertEquals(MediaId("applemusic", "library:r.artist"), results.artists.single().id)
        assertEquals(MediaId("applemusic", "library:p.list"), results.playlists.single().id)
        assertFalse(results.tracks.single().isStarred)
        val request = server.takeRequest()
        assertEquals("/v1/me/library/search", request.requestUrl!!.encodedPath)
        assertEquals("日本 & Music", request.requestUrl!!.queryParameter("term"))
        assertEquals("user", request.getHeader("Music-User-Token"))
        assertEquals(
            "library-songs,library-albums,library-artists,library-playlists",
            request.requestUrl!!.queryParameter("types")
        )
        assertEquals(setOf("term", "types", "limit"), request.requestUrl!!.queryParameterNames)
        val songRequest = server.takeRequest()
        assertEquals("/v1/me/library/songs", songRequest.requestUrl!!.encodedPath)
        assertEquals("i.saved", songRequest.requestUrl!!.queryParameter("ids"))
        assertEquals("catalog", songRequest.requestUrl!!.queryParameter("include"))
        assertEquals("user", songRequest.getHeader("Music-User-Token"))
    }

    @Test fun should_refuseUnavailableSongs_when_librarySearchReturnsStaleCatalogIds() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                // Match Apple's endpoint contracts, not the generic scoped-include convention:
                // search has no include; only the separate multiple-library-songs read supports it.
                if (url.encodedPath == "/v1/me/library/search") {
                    if (url.queryParameterNames.any { it !in setOf("term", "types", "limit", "offset", "l") }) {
                        return MockResponse().setResponseCode(400)
                    }
                    return MockResponse().setBody(
                        """{"results":{"library-songs":{"data":[
                      {"id":"i.playable","type":"library-songs",
                       "attributes":{"name":"Available","playParams":{"catalogId":"111"}},
                       "relationships":{"albums":{"data":[{"id":"l.album","type":"library-albums"}]}}},
                      {"id":"i.pulled","type":"library-songs",
                       "attributes":{"name":"Unavailable","playParams":{"catalogId":"221"}}},
                      {"id":"i.import","type":"library-songs","attributes":{"name":"Imported"}},
                      {"id":"i.missing","type":"library-songs",
                       "attributes":{"name":"Missing answer","playParams":{"catalogId":"331"}}},
                      {"id":"i.unanswered","type":"library-songs",
                       "attributes":{"name":"No relationship","playParams":{"catalogId":"441"}}}
                    ]}}}"""
                    )
                }
                if (url.encodedPath != "/v1/me/library/songs" || url.queryParameter("include") != "catalog") {
                    return MockResponse().setResponseCode(400)
                }
                // Return a different order, unrelated metadata, a missing resource, and one resource
                // with no catalog answer. Join only answered relationships by the library resource id.
                return MockResponse().setBody(
                    """{"data":[
                      {"id":"i.import","type":"library-songs","relationships":{"catalog":{"data":[]}}},
                      {"id":"i.unanswered","type":"library-songs","attributes":{"name":"Replacement"}},
                      {"id":"i.pulled","type":"library-songs","relationships":{"catalog":{"data":[]}}},
                      {"id":"i.playable","type":"library-songs","attributes":{"name":"Replacement"},
                       "relationships":{"catalog":{"data":[{"id":"112","type":"songs"}]}}}
                    ]}"""
                )
            }
        }

        val tracks = source.searchLibrary("song").tracks

        assertEquals(listOf("112", "221", "library:i.import", "331", "441"), tracks.map { it.id.rawId })
        assertEquals(listOf(false, true, true, false, false), tracks.map { it.isUnplayableAppleImport })
        assertEquals(listOf("Available", "Unavailable", "Imported", "Missing answer", "No relationship"), tracks.map { it.title })
        assertEquals(MediaId("applemusic", "library:l.album"), tracks[0].albumId)
        assertEquals(
            PlaybackHandle.ExternalController(PlaybackHandle.ControllerType.APPLE_MUSIC_KIT, "112"),
            source.handleFor(tracks[0])
        )
        assertTrue(runCatching { source.handleFor(tracks[1]) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("i.pulled", tracks[1].extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertEquals("true", tracks[1].extras[AppleMusicSong.EXTRA_CATALOG_UNAVAILABLE])
        assertEquals(2, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/me/library/search", request.requestUrl!!.encodedPath)
        assertEquals("user", request.getHeader("Music-User-Token"))
        val lookup = server.takeRequest()
        assertEquals(setOf("ids", "include"), lookup.requestUrl!!.queryParameterNames)
        assertEquals("i.playable,i.pulled,i.import,i.missing,i.unanswered", lookup.requestUrl!!.queryParameter("ids"))
        assertEquals("user", lookup.getHeader("Music-User-Token"))
    }

    @Test fun should_keepSearchResultsUnconfirmed_when_catalogLookupFails() = runTest {
        for (status in listOf(400, 403, 429, 500)) {
            reply(
                """{"results":{
                  "library-songs":{"data":[{"id":"i.saved","type":"library-songs",
                    "attributes":{"name":"Saved","playParams":{"catalogId":"123"}}}]},
                  "library-albums":{"data":[{"id":"l.album","type":"library-albums","attributes":{"name":"Album"}}]}
                }}"""
            )
            server.enqueue(MockResponse().setResponseCode(status))

            val results = source.searchLibrary("song")

            assertEquals("123", results.tracks.single().id.rawId)
            assertEquals("Saved", results.tracks.single().title)
            assertFalse(results.tracks.single().isUnplayableAppleImport)
            assertNull(results.tracks.single().extras[AppleMusicSong.EXTRA_CATALOG_UNAVAILABLE])
            assertEquals("Album", results.albums.single().name)
        }
        assertEquals(8, server.requestCount)
    }

    @Test fun should_skipCatalogLookup_when_librarySearchHasNoSongs() = runTest {
        reply("""{"results":{"library-albums":{"data":[{"id":"l.album","type":"library-albums","attributes":{"name":"Album"}}]}}}""")

        assertEquals("Album", source.searchLibrary("album").albums.single().name)

        assertEquals(1, server.requestCount)
    }

    @Test fun should_propagateCancellation_when_catalogLookupIsCancelled() = runTest {
        reply("""{"results":{"library-songs":{"data":[{"id":"i.saved","type":"library-songs","attributes":{"playParams":{"catalogId":"123"}}}]}}}""")
        var tokenCalls = 0
        val cancellableSource = AppleMusicSource(
            ProfileCredentials.AppleMusic("https://token.test", "user"),
            api = AppleMusicApiClient(
                {
                    if (++tokenCalls == 2) throw CancellationException("cancel catalog lookup")
                    "developer"
                },
                { "user" },
                baseUrl = server.url("/")
            )
        )

        assertTrue(runCatching { cancellableSource.searchLibrary("song") }.exceptionOrNull() is CancellationException)
        assertEquals(1, server.requestCount)
    }

    @Test fun should_listOneRow_when_twoLibrarySongsShareACatalogSong() = runTest {
        reply(
            """{
              "results":{
                "library-songs":{"data":[
                  {"id":"i.a","type":"library-songs","attributes":{"name":"Song","playParams":{"catalogId":"123"}}},
                  {"id":"i.b","type":"library-songs","attributes":{"name":"Song","playParams":{"catalogId":"123"}}}
                ]}
              }
            }"""
        )
        reply("""{"data":[]}""")
        val results = source.searchLibrary("song")
        assertEquals(listOf(MediaId("applemusic", "123")), results.tracks.map { it.id })
    }

    @Test fun should_markSongsUnplayable_when_appleHasNoCatalogSongForThem() = runTest {
        reply(
            """{"data":[
              {"id":"i.played","type":"library-songs","attributes":{"name":"Plays","playParams":{"catalogId":"111"}},
               "relationships":{"catalog":{"data":[{"id":"112","type":"songs"}]}}},
              {"id":"i.pulled","type":"library-songs","attributes":{"name":"Angel","playParams":{"catalogId":"221"}},
               "relationships":{"catalog":{"data":[]}}},
              {"id":"i.import","type":"library-songs","attributes":{"name":"Imported"},
               "relationships":{"catalog":{"data":[]}}},
              {"id":"i.unasked","type":"library-songs","attributes":{"name":"Unasked","playParams":{"catalogId":"331"}}}
            ]}"""
        )

        val tracks = source.getLibrarySongs(size = 10)

        // The storefront's own catalog song plays; one Apple says it has none of keeps its id but can't play.
        assertEquals(listOf("112", "221", "library:i.import", "331"), tracks.map { it.id.rawId })
        assertEquals(listOf(false, true, true, false), tracks.map { it.isUnplayableAppleImport })
        val pulled = tracks[1]
        assertEquals("i.pulled", pulled.extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        val refusal = runCatching { source.handleFor(pulled) }.exceptionOrNull()
        assertTrue(refusal is IllegalArgumentException)
        // Without the relationship there is nothing to go on: its play parameters' id stands.
        assertEquals(
            PlaybackHandle.ExternalController(PlaybackHandle.ControllerType.APPLE_MUSIC_KIT, "331"),
            source.handleFor(tracks[3])
        )
    }

    @Test fun should_loadRequestedLibrarySongPages_when_providerCannotReturnRandomSongs() = runTest {
        reply(
            """{
              "data":[{"id":"i.1","type":"library-songs","attributes":{"playParams":{"catalogId":"123"}}}],
              "next":"/v1/me/library/songs?offset=6&include=catalog,albums,artists"
            }"""
        )
        reply("""{"data":[{"id":"i.import","type":"library-songs","attributes":{"name":"Imported"}}]}""")
        val tracks = source.getLibrarySongs(size = 2, offset = 5)
        assertEquals(listOf("123", "library:i.import"), tracks.map { it.id.rawId })
        val first = server.takeRequest()
        assertEquals("/v1/me/library/songs", first.requestUrl!!.encodedPath)
        assertEquals("5", first.requestUrl!!.queryParameter("offset"))
        assertEquals("2", first.requestUrl!!.queryParameter("limit"))
        assertEquals("catalog,albums,artists", first.requestUrl!!.queryParameter("include"))
        assertEquals("6", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertTrue(Capability.LIBRARY_SONGS in source.capabilities)
        assertFalse(Capability.RANDOM_SONGS in source.capabilities)
    }

    @Test fun should_confirmExactMembershipBeforeReturningAdded_when_libraryWriteAppearsAfterDelay() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        reply("""{"data":[]}""")
        server.enqueue(MockResponse().setResponseCode(202))
        reply("""{"data":[]}""")
        reply("""{"data":[{"id":"i.saved","type":"library-songs"}]}""")
        assertEquals(LibraryMembership.Added, source.addToLibrary(MediaId("applemusic", "123")).getOrThrow())
        assertEquals("/v1/me/storefront", server.takeRequest().path)
        assertEquals("/v1/catalog/jp/songs/123/library", server.takeRequest().path)
        assertEquals("POST", server.takeRequest().method)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/v1/catalog/jp/songs/123/library", request.path)
            assertEquals("user", request.getHeader("Music-User-Token"))
        }
    }

    @Test fun should_reportNotAdded_when_missingRelationshipHasExactValidCatalogSong() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        server.enqueue(MockResponse().setResponseCode(404))
        reply("""{"data":[{"id":"123","type":"songs"}]}""")
        assertEquals(LibraryMembership.NotAdded, source.libraryMembership(MediaId("applemusic", "123")).getOrThrow())
        assertEquals(3, server.requestCount)
    }

    @Test fun should_addUnsavedSong_when_relationship404IsConfirmedAgainstCatalog() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        server.enqueue(MockResponse().setResponseCode(404))
        reply("""{"data":[{"id":"123","type":"songs"}]}""")
        server.enqueue(MockResponse().setResponseCode(202))
        reply("""{"data":[{"id":"i.saved","type":"library-songs"}]}""")
        assertEquals(LibraryMembership.Added, source.addToLibrary(MediaId("applemusic", "123")).getOrThrow())
        val methods = (0 until server.requestCount).map { server.takeRequest().method }
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"), methods)
    }

    @Test fun should_preserveMissingCatalogFailure_when_noExactSongCanBeConfirmed() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val error = source.libraryMembership(MediaId("applemusic", "123")).exceptionOrNull()
        assertTrue(error is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.Http(404), (error as AppleMusicApiException).failure)
    }

    @Test fun should_keepAuthorizationFailure_when_membershipIsForbidden() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        server.enqueue(MockResponse().setResponseCode(403))
        val error = source.addToLibrary(MediaId("applemusic", "123")).exceptionOrNull()
        assertTrue(error is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.AccessDenied, (error as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun should_remainPendingWithoutResubmitting_when_acceptedSongIsNotConfirmed() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        reply("""{"data":[]}""")
        server.enqueue(MockResponse().setResponseCode(202))
        repeat(5) { reply("""{"data":[]}""") }
        val id = MediaId("applemusic", "123")
        assertEquals(LibraryMembership.Pending, source.addToLibrary(id).getOrThrow())
        reply("""{"data":[]}""")
        repeat(5) { reply("""{"data":[]}""") }
        assertEquals(LibraryMembership.Pending, source.addToLibrary(id).getOrThrow())
        reply("""{"data":[]}""")
        assertEquals(LibraryMembership.Pending, source.libraryMembership(id).getOrThrow())
        reply("""{"data":[{"id":"i.saved","type":"library-songs"}]}""")
        assertEquals(LibraryMembership.Added, source.libraryMembership(id).getOrThrow())
        val methods = (0 until server.requestCount).map { server.takeRequest().method }
        assertEquals(1, methods.count { it == "POST" })
    }

    @Test fun should_preservePending_when_confirmationFailsAfterAcceptedWrite() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        reply("""{"data":[]}""")
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(403))
        val id = MediaId("applemusic", "123")
        assertEquals(LibraryMembership.Pending, source.addToLibrary(id).getOrThrow())
        reply("""{"data":[]}""")
        assertEquals(LibraryMembership.Pending, source.libraryMembership(id).getOrThrow())
        assertEquals(5, server.requestCount)
    }

    @Test fun should_skipMutation_when_songIsAlreadyInLibrary() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        reply("""{"data":[{"id":"i.saved","type":"library-songs"}]}""")
        assertEquals(LibraryMembership.Added, source.addToLibrary(MediaId("applemusic", "123")).getOrThrow())
        assertEquals(LibraryMembership.Added, source.addToLibrary(MediaId("applemusic", "library:i.saved")).getOrThrow())
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test fun should_rejectForeignOrNonCatalogIds_when_mutatingAppleLibrary() = runTest {
        assertTrue(source.addToLibrary(MediaId("spotify", "123")).isFailure)
        assertTrue(source.addToLibrary(MediaId("applemusic", "not-a-song-id")).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun should_rethrowCancellationAndKeepAcceptedWritePending_when_confirmationIsCancelled() = runTest {
        var tokenCalls = 0
        val cancellableSource = AppleMusicSource(
            ProfileCredentials.AppleMusic("https://token.test", "user"),
            api = AppleMusicApiClient(
                {
                    if (++tokenCalls == 4) throw CancellationException("cancel confirmation")
                    "developer"
                },
                { "user" },
                baseUrl = server.url("/")
            )
        )
        reply("""{"data":[{"id":"jp"}]}""")
        reply("""{"data":[]}""")
        server.enqueue(MockResponse().setResponseCode(202))
        val id = MediaId("applemusic", "123")
        assertTrue(runCatching { cancellableSource.addToLibrary(id) }.exceptionOrNull() is CancellationException)
        reply("""{"data":[]}""")
        assertEquals(LibraryMembership.Pending, cancellableSource.libraryMembership(id).getOrThrow())
        assertEquals(4, server.requestCount)
    }

    @Test fun should_keepPageLimit_when_followingArtistAlbumsNextLink() = runTest {
        reply("""{"data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Artist"}}]}""")
        reply(
            """{
              "data":[{"id":"l.1","type":"library-albums","attributes":{"name":"One"}}],
              "next":"/v1/me/library/artists/r.1/albums?offset=100"
            }"""
        )
        reply("""{"data":[{"id":"l.2","type":"library-albums","attributes":{"name":"Two"}}]}""")
        val artist = source.getArtist(MediaId("applemusic", "library:r.1"))!!
        assertEquals(listOf("library:l.1", "library:l.2"), artist.albums.map { it.id.rawId })
        assertEquals("/v1/me/library/artists/r.1", server.takeRequest().requestUrl!!.encodedPath)
        val first = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/library/artists/r.1/albums", first.encodedPath)
        assertEquals("100", first.queryParameter("limit"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("100", second.queryParameter("offset"))
        // Apple's link carries only the offset; the page size must not fall back to 25.
        assertEquals(listOf("100"), second.queryParameterValues("limit"))
    }

    @Test fun should_keepPageLimit_when_followingLibraryArtistsNextLink() = runTest {
        reply(
            """{
              "data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}],
              "next":"/v1/me/library/artists?offset=100&limit=100"
            }"""
        )
        reply("""{"data":[{"id":"r.2","type":"library-artists","attributes":{"name":"Beta"}}]}""")
        val indices = source.getArtists()
        assertEquals(listOf("Alpha", "Beta"), indices.flatMap { index -> index.artists.map { it.name } })
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("limit"))
        // A link that already names the limit is sent as it is, not with a second one.
        assertEquals(listOf("100"), server.takeRequest().requestUrl!!.queryParameterValues("limit"))
    }

    @Test fun should_takeTheCatalogArtistsArtwork_when_libraryArtistsIncludeTheirCatalogArtist() = runTest {
        reply(
            """{
              "data":[{
                "id":"r.1","type":"library-artists","attributes":{"name":"Alpha"},
                "relationships":{"catalog":{"data":[{
                  "id":"159260351","type":"artists",
                  "attributes":{"name":"Alpha","artwork":{"url":"https://is1.test/a/{w}x{h}bb.jpg","width":2400}}
                }]}}
              }],
              "next":"/v1/me/library/artists?offset=100"
            }"""
        )
        reply("""{"data":[{"id":"r.2","type":"library-artists","attributes":{"name":"Beta"}}]}""")
        val artists = source.getArtists().flatMap { index -> index.artists }
        // The library id stays: the row still opens the library artist page.
        assertEquals(listOf("library:r.1", "library:r.2"), artists.map { it.id.rawId })
        assertEquals(CoverRef.Url("https://is1.test/a/600x600bb.jpg"), artists[0].coverArt)
        assertNull(artists[1].coverArt)
        val first = server.takeRequest().requestUrl!!
        assertEquals("catalog", first.queryParameter("include"))
        assertEquals("100", first.queryParameter("limit"))
        // Apple's link carries only the offset; the next page asks for the catalog artist again.
        val second = server.takeRequest().requestUrl!!
        assertEquals(listOf("catalog"), second.queryParameterValues("include"))
        assertEquals(listOf("100"), second.queryParameterValues("limit"))
    }

    @Test fun should_leaveThePortraitEmpty_when_aLibraryArtistHasNoCatalogArtist() = runTest {
        reply(
            """{"data":[
              {"id":"r.1","type":"library-artists","attributes":{"name":"Imported"},
               "relationships":{"catalog":{"data":[]}}},
              {"id":"r.2","type":"library-artists","attributes":{"name":"Bare"}},
              {"id":"r.3","type":"library-artists","attributes":{"name":"Faceless"},
               "relationships":{"catalog":{"data":[{"id":"7","type":"artists","attributes":{"name":"Faceless"}}]}}}
            ]}"""
        )
        val artists = source.getArtists().flatMap { index -> index.artists }
        assertEquals(listOf("Bare", "Faceless", "Imported"), artists.map { it.name })
        assertTrue(artists.all { it.coverArt == null })
    }

    @Test fun should_openALibraryArtistWithItsCatalogPortrait_when_loadingTheArtistPage() = runTest {
        reply(
            """{"data":[{
              "id":"r.1","type":"library-artists","attributes":{"name":"Alpha"},
              "relationships":{"catalog":{"data":[{
                "id":"159260351","type":"artists",
                "attributes":{"name":"Alpha","artwork":{"url":"https://is1.test/a/{w}x{h}bb.jpg"}}
              }]}}
            }]}"""
        )
        reply("""{"data":[{"id":"l.1","type":"library-albums","attributes":{"name":"One"}}]}""")
        val artist = source.getArtist(MediaId("applemusic", "library:r.1"))!!
        assertEquals(MediaId("applemusic", "library:r.1"), artist.id)
        assertEquals(CoverRef.Url("https://is1.test/a/600x600bb.jpg"), artist.coverArt)
        assertEquals(listOf("library:l.1"), artist.albums.map { it.id.rawId })
        val artistRequest = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/library/artists/r.1", artistRequest.encodedPath)
        assertEquals("catalog", artistRequest.queryParameter("include"))
        // The releases are read as before.
        val albumsRequest = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/library/artists/r.1/albums", albumsRequest.encodedPath)
        assertNull(albumsRequest.queryParameter("include"))
    }

    @Test fun should_notAskForACatalogRelationship_when_loadingACatalogArtist() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        reply(
            """{"data":[{"id":"159260351","type":"artists",
              "attributes":{"name":"Alpha","artwork":{"url":"https://is1.test/a/{w}x{h}bb.jpg"}}}]}"""
        )
        reply("""{"data":[]}""")
        val artist = source.getArtist(MediaId("applemusic", "159260351"))!!
        assertEquals(CoverRef.Url("https://is1.test/a/600x600bb.jpg"), artist.coverArt)
        assertEquals("/v1/me/storefront", server.takeRequest().path)
        val artistRequest = server.takeRequest().requestUrl!!
        assertEquals("/v1/catalog/jp/artists/159260351", artistRequest.encodedPath)
        assertNull(artistRequest.queryParameter("include"))
    }

    @Test fun should_listLibraryArtistsWithoutPortraits_when_appleRefusesTheCatalogInclude() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply(
            """{
              "data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}],
              "next":"/v1/me/library/artists?offset=100"
            }"""
        )
        reply("""{"data":[{"id":"r.2","type":"library-artists","attributes":{"name":"Beta"}}]}""")
        val artists = source.getArtists().flatMap { index -> index.artists }
        assertEquals(listOf("library:r.1", "library:r.2"), artists.map { it.id.rawId })
        assertTrue(artists.all { it.coverArt == null })
        assertEquals("catalog", server.takeRequest().requestUrl!!.queryParameter("include"))
        // The list is read again from its first page without the include, and so is every later page.
        val retry = server.takeRequest().requestUrl!!
        assertNull(retry.queryParameter("offset"))
        assertNull(retry.queryParameter("include"))
        assertEquals("100", retry.queryParameter("limit"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("100", second.queryParameter("offset"))
        assertNull(second.queryParameter("include"))
        assertEquals(listOf("100"), second.queryParameterValues("limit"))
    }

    @Test fun should_failTheLibraryArtistList_when_appleRateLimitsTheRead() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val error = runCatching { source.getArtists() }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.RateLimited, (error as AppleMusicApiException).failure)
        // A rate limit is not a refused include: no second request.
        assertEquals(1, server.requestCount)
    }

    @Test fun should_retryTheSameArtistPageWithoutInclude_when_aLaterPageRejectsCatalogInclude() = runTest {
        for (status in listOf(400, 503)) {
            for (includeInNext in listOf(false, true)) {
                val next = "/v1/me/library/artists?offset=100" + if (includeInNext) "&include=catalog" else ""
                reply(
                    """{
                      "data":[{
                        "id":"r.1","type":"library-artists","attributes":{"name":"Alpha"},
                        "relationships":{"catalog":{"data":[{
                          "id":"159260351","type":"artists",
                          "attributes":{"name":"Alpha","artwork":{"url":"https://is1.test/a/{w}x{h}bb.jpg"}}
                        }]}}
                      }],
                      "next":"$next"
                    }"""
                )
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"errors":[]}"""))
                reply(
                    """{
                      "data":[{"id":"r.2","type":"library-artists","attributes":{"name":"Beta"}}],
                      "next":"/v1/me/library/artists?offset=200&limit=50&include=catalog"
                    }"""
                )
                reply("""{"data":[{"id":"r.3","type":"library-artists","attributes":{"name":"Gamma"}}]}""")

                val requestsBefore = server.requestCount
                val artists = source.getArtists().flatMap { index -> index.artists }
                assertEquals(listOf("library:r.1", "library:r.2", "library:r.3"), artists.map { it.id.rawId })
                assertEquals(CoverRef.Url("https://is1.test/a/600x600bb.jpg"), artists.first().coverArt)
                assertTrue(artists.drop(1).all { it.coverArt == null })
                assertEquals(4, server.requestCount - requestsBefore)

                val requests = List(4) { server.takeRequest().requestUrl!! }
                assertTrue(requests.all { it.encodedPath == "/v1/me/library/artists" })
                assertEquals(listOf(null, "100", "100", "200"), requests.map { it.queryParameter("offset") })
                assertEquals(listOf("catalog", "catalog", null, null), requests.map { it.queryParameter("include") })
                assertEquals(listOf("100", "100", "100", "50"), requests.map { it.queryParameter("limit") })
                assertTrue(requests.all { it.queryParameterValues("limit").size == 1 })
            }
        }
    }

    @Test fun should_failTheLibraryArtistListWithoutAnotherRetry_when_theFallbackPageFails() = runTest {
        reply(
            """{
              "data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}],
              "next":"/v1/me/library/artists?offset=100&include=catalog"
            }"""
        )
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"errors":[]}"""))
        val error = runCatching { source.getArtists() }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.Http(503), (error as AppleMusicApiException).failure)
        assertEquals(3, server.requestCount)
    }

    @Test fun should_failTheLibraryArtistListWithoutAnotherRetry_when_aPageAfterFallbackFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply(
            """{
              "data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}],
              "next":"/v1/me/library/artists?offset=100&include=catalog"
            }"""
        )
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"errors":[]}"""))
        val error = runCatching { source.getArtists() }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.Http(503), (error as AppleMusicApiException).failure)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        val failedPage = server.takeRequest().requestUrl!!
        assertEquals("100", failedPage.queryParameter("offset"))
        assertNull(failedPage.queryParameter("include"))
    }

    @Test fun should_rejectRepeatedPagination_when_anArtistFallbackReturnsTheSameNextLink() = runTest {
        val body = """{
          "data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}],
          "next":"/v1/me/library/artists?offset=100&include=catalog"
        }"""
        reply(body)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply(body)
        val error = runCatching { source.getArtists() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Apple Music repeated a pagination link", error?.message)
        assertEquals(3, server.requestCount)
    }

    @Test fun should_openALibraryArtistWithoutAPortrait_when_appleRefusesTheCatalogInclude() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply("""{"data":[{"id":"r.1","type":"library-artists","attributes":{"name":"Alpha"}}]}""")
        reply("""{"data":[{"id":"l.1","type":"library-albums","attributes":{"name":"One"}}]}""")
        val artist = source.getArtist(MediaId("applemusic", "library:r.1"))!!
        assertEquals(MediaId("applemusic", "library:r.1"), artist.id)
        assertNull(artist.coverArt)
        assertEquals(listOf("library:l.1"), artist.albums.map { it.id.rawId })
        assertEquals("catalog", server.takeRequest().requestUrl!!.queryParameter("include"))
        val retry = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/library/artists/r.1", retry.encodedPath)
        assertNull(retry.queryParameter("include"))
        assertEquals("/v1/me/library/artists/r.1/albums", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun should_failTheCatalogArtist_when_itsReadFails() = runTest {
        reply("""{"data":[{"id":"jp"}]}""")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        val error = runCatching { source.getArtist(MediaId("applemusic", "159260351")) }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.Http(400), (error as AppleMusicApiException).failure)
        // It asked for no include, so there is none to drop: no retry.
        assertEquals(2, server.requestCount)
    }

    @Test fun should_askForLargerPages_when_readingRecentlyAddedAlbums() = runTest {
        reply(
            """{
              "data":[{"id":"l.1","type":"library-albums","attributes":{"name":"One"}}],
              "next":"/v1/me/library/recently-added?offset=25"
            }"""
        )
        reply("""{"data":[{"id":"l.2","type":"library-albums","attributes":{"name":"Two"}}]}""")
        val albums = source.getAlbumList("newest", size = 10, offset = 0)
        assertEquals(listOf("library:l.1", "library:l.2"), albums.map { it.id.rawId })
        val first = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/library/recently-added", first.encodedPath)
        assertEquals("25", first.queryParameter("limit"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("25", second.queryParameter("offset"))
        assertEquals(listOf("25"), second.queryParameterValues("limit"))
    }

    @Test fun should_fallBackToDefaultPages_when_recentlyAddedRefusesThePageSize() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply("""{"data":[{"id":"l.1","type":"library-albums","attributes":{"name":"One"}}]}""")
        val albums = source.getAlbumList("newest", size = 10, offset = 0)
        assertEquals(listOf("library:l.1"), albums.map { it.id.rawId })
        assertEquals("25", server.takeRequest().requestUrl!!.queryParameter("limit"))
        assertEquals(null, server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test fun should_giveRecentlyPlayedLibraryAlbumsTheirCatalogId_when_appleIncludesTheCatalog() = runTest {
        reply(
            """{
              "data":[
                {"id":"l.1","type":"library-albums","attributes":{"name":"Mine"},
                 "relationships":{"catalog":{"data":[{"id":"1440857781","type":"albums"}]}}},
                {"id":"l.2","type":"library-albums","attributes":{"name":"Imported"},
                 "relationships":{"catalog":{"data":[]}}},
                {"id":"pl.1","type":"playlists","attributes":{"name":"A playlist"}},
                {"id":"310730204","type":"albums","attributes":{"name":"Catalog"}},
                {"id":"l.3","type":"library-albums","attributes":{"name":"Mine again"},
                 "relationships":{"catalog":{"data":[{"id":"310730204","type":"albums"}]}}}
              ]
            }"""
        )
        val albums = source.getRecentlyPlayedAlbums(10)
        // The catalog id the album opens as and is rated under; an unmatched one keeps its library id.
        assertEquals(listOf("1440857781", "library:l.2", "310730204"), albums.map { it.id.rawId })
        val request = server.takeRequest().requestUrl!!
        assertEquals("/v1/me/recent/played", request.encodedPath)
        assertEquals("catalog", request.queryParameter("include[library-albums]"))
        assertNull(request.queryParameter("include"))
        assertEquals("10", request.queryParameter("limit"))
        assertEquals(1, server.requestCount)
    }

    @Test fun should_readRecentlyPlayedWithoutTheInclude_when_appleRefusesIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[]}"""))
        reply("""{"data":[{"id":"l.1","type":"library-albums","attributes":{"name":"Mine"}}]}""")
        reply("""{"data":[{"id":"l.1","type":"library-albums","attributes":{"name":"Mine"}}]}""")

        assertEquals(listOf("library:l.1"), source.getRecentlyPlayedAlbums(10).map { it.id.rawId })
        assertEquals("catalog", server.takeRequest().requestUrl!!.queryParameter("include[library-albums]"))
        val retry = server.takeRequest().requestUrl!!
        assertNull(retry.queryParameter("include[library-albums]"))
        assertEquals("10", retry.queryParameter("limit"))

        // Refused with a 400: the next read doesn't ask again.
        assertEquals(listOf("library:l.1"), source.getRecentlyPlayedAlbums(10).map { it.id.rawId })
        assertNull(server.takeRequest().requestUrl!!.queryParameter("include[library-albums]"))
        assertEquals(3, server.requestCount)
    }

    @Test fun should_askForTheCatalogAgain_when_recentlyPlayedFailedWithAServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"errors":[]}"""))
        reply("""{"data":[]}""")
        reply("""{"data":[]}""")

        source.getRecentlyPlayedAlbums(10)
        source.getRecentlyPlayedAlbums(10)

        val includes = List(3) { server.takeRequest().requestUrl!!.queryParameter("include[library-albums]") }
        // A 5xx may be passing: it costs this read's include, not the next one's.
        assertEquals(listOf("catalog", null, "catalog"), includes)
    }

    @Test fun should_failRecentlyPlayed_when_appleRateLimitsIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"errors":[]}"""))

        val error = runCatching { source.getRecentlyPlayedAlbums(10) }.exceptionOrNull()

        assertEquals(AppleMusicApiFailure.RateLimited, (error as AppleMusicApiException).failure)
        // Not a refused include: no second request.
        assertEquals(1, server.requestCount)
    }

    @Test fun should_readTheStorefrontOnce_when_manyCatalogReadsStartTogether() = runTest {
        // A source that went live without a ping (a delete's hand-off): Home's refresh opens many albums at once.
        val storefrontReads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/me/storefront" -> {
                    storefrontReads.incrementAndGet()
                    // Slow enough that every caller asks before the first answer is in.
                    MockResponse().setBody("""{"data":[{"id":"jp"}]}""").setBodyDelay(300, TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val links = withContext(Dispatchers.Default) {
            List(8) { index -> async { source.webUrl(WebLinkKind.Album, MediaId("applemusic", "12$index")) } }
                .awaitAll()
        }

        assertEquals(List(8) { "https://music.apple.com/jp/album/12$it" }, links)
        assertEquals(1, storefrontReads.get())
    }

    @Test fun should_readTheStorefrontAgain_when_theFirstReadFailed() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        reply("""{"data":[{"id":"jp"}]}""")

        assertNull(source.webUrl(WebLinkKind.Album, MediaId("applemusic", "123")))
        assertEquals(
            "https://music.apple.com/jp/album/123",
            source.webUrl(WebLinkKind.Album, MediaId("applemusic", "123"))
        )
        // Kept once it answered: the next catalog link asks no more.
        assertEquals(
            "https://music.apple.com/jp/album/456",
            source.webUrl(WebLinkKind.Album, MediaId("applemusic", "456"))
        )
        assertEquals(2, server.requestCount)
    }

    @Test fun should_roundTripAppleCredentials_withProviderDiscriminator() {
        val credentials = ProfileCredentials.AppleMusic("https://token.test/token", "user")
        val codec = PlaintextProfileCredentialsCodec()
        assertEquals(credentials, codec.decode(codec.encode(credentials)))
        assertEquals("applemusic", credentials.providerId)
    }
    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))
}

private const val SavedLibraryPlaylist =
    """{"id":"p.saved","type":"library-playlists","attributes":{"name":"Saved","canEdit":false,"hasCatalog":true,""" +
        """"playParams":{"id":"p.saved","isLibrary":true,"kind":"playlist","globalId":"pl.u-shared"}}}"""
