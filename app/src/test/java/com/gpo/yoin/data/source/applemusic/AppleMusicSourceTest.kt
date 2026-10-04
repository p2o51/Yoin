package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import com.gpo.yoin.data.source.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test fun should_roundTripAppleCredentials_withProviderDiscriminator() {
        val credentials = ProfileCredentials.AppleMusic("https://token.test/token", "user")
        val codec = PlaintextProfileCredentialsCodec()
        assertEquals(credentials, codec.decode(codec.encode(credentials)))
        assertEquals("applemusic", credentials.providerId)
    }
    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))
}
