package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
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

    @Test fun should_roundTripAppleCredentials_withProviderDiscriminator() {
        val credentials = ProfileCredentials.AppleMusic("https://token.test/token", "user")
        val codec = PlaintextProfileCredentialsCodec()
        assertEquals(credentials, codec.decode(codec.encode(credentials)))
        assertEquals("applemusic", credentials.providerId)
    }
    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))
}
