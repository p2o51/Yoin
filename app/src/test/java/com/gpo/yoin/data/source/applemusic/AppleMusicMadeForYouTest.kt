package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.source.spotify.MadeForYouKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Home's Made For You on Apple Music: the personal mixes (`pl.pm-…`) among the recommendations. */
class AppleMusicMadeForYouTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AppleMusicSource
    private val requests = mutableListOf<RecordedRequest>()
    private val routes = mutableMapOf<String, String>()

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val body = routes[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
        routes["/v1/me/storefront"] = """{"data":[{"id":"jp"}]}"""
        source = AppleMusicSource(
            ProfileCredentials.AppleMusic("https://token.test", "user"),
            api = AppleMusicApiClient({ "developer" }, { "user" }, baseUrl = server.url("/"))
        )
    }

    @After fun cleanup() = server.shutdown()

    @Test fun should_listPersonalMixes_inAppleOrder_when_recommendationsHaveThem() = runTest {
        routes["/v1/me/recommendations"] = """
{
  "data": [
    {
      "id": "6-27s5hU6azhJY", "type": "personal-recommendation",
      "attributes": { "title": { "stringForDisplay": "为你推荐" } },
      "relationships": { "contents": { "data": [
        { "id": "pl.pm-favorites", "type": "playlists", "attributes": {
          "name": "Favorites Mix", "curatorName": "Apple Music",
          "description": { "short": "Updated Wednesdays", "standard": "The songs you love." },
          "artwork": { "url": "https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg", "width": 1080, "height": 1080 }
        } },
        { "id": "pl.pm-newmusic", "type": "playlists", "attributes": { "name": "New Music Mix" } },
        { "id": "ra.pm-station", "type": "stations", "attributes": { "name": "Your Station" } }
      ] } }
    },
    {
      "id": "6-editorial", "type": "personal-recommendation",
      "attributes": { "title": { "stringForDisplay": "最近播放" } },
      "relationships": { "contents": { "data": [
        { "id": "pl.u-editorial", "type": "playlists", "attributes": { "name": "Today's Hits" } },
        { "id": "1440", "type": "albums", "attributes": { "name": "An album" } },
        { "id": "pl.pm-favorites", "type": "playlists", "attributes": { "name": "Favorites Mix" } }
      ] } }
    }
  ]
}
"""

        val mixes = source.madeForYou()

        assertEquals(listOf("Favorites Mix", "New Music Mix"), mixes.map { it.title })
        val favorites = mixes.first()
        assertEquals("pl.pm-favorites", favorites.rawId)
        assertEquals(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "pl.pm-favorites"), favorites.mediaId)
        assertEquals(MadeForYouKind.AppleMix, favorites.kind)
        assertEquals("Updated Wednesdays", favorites.subtitle)
        assertEquals("https://is1-ssl.mzstatic.com/image/600x600bb.jpg", favorites.imageUrl)
        // The recommendations are the user's own.
        val asked = requests.single { it.requestUrl!!.encodedPath == "/v1/me/recommendations" }
        assertEquals("user", asked.getHeader("Music-User-Token"))
    }

    @Test fun should_readBareMixesFromTheCatalog_when_listedWithoutAttributes() = runTest {
        routes["/v1/me/recommendations"] = """
{ "data": [ { "id": "6-1", "type": "personal-recommendation", "relationships": { "contents": { "data": [
  { "id": "pl.pm-chill", "type": "playlists", "href": "/v1/catalog/jp/playlists/pl.pm-chill" },
  { "id": "pl.pm-favorites", "type": "playlists", "attributes": { "name": "Favorites Mix" } },
  { "id": "pl.pm-gone", "type": "playlists" }
] } } } ] }
"""
        routes["/v1/catalog/jp/playlists"] = """
{ "data": [ { "id": "pl.pm-chill", "type": "playlists", "attributes": { "name": "Chill Mix" } } ] }
"""

        val mixes = source.madeForYou()

        // Apple's order; a mix the catalog doesn't return is left out.
        assertEquals(listOf("Chill Mix", "Favorites Mix"), mixes.map { it.title })
        val catalog = requests.single { it.requestUrl!!.encodedPath == "/v1/catalog/jp/playlists" }
        assertEquals("pl.pm-chill,pl.pm-gone", catalog.requestUrl!!.queryParameter("ids"))
        assertEquals("user", catalog.getHeader("Music-User-Token"))
    }

    @Test fun should_sendTheUserToken_when_openingAPersonalMix() = runTest {
        routes["/v1/catalog/jp/playlists/pl.pm-favorites"] = """
{ "data": [ { "id": "pl.pm-favorites", "type": "playlists", "attributes": { "name": "Favorites Mix" } } ] }
"""
        routes["/v1/catalog/jp/playlists/pl.pm-favorites/tracks"] = """
{ "data": [ { "id": "1765230825", "type": "songs", "attributes": {
  "name": "A song", "artistName": "Someone", "albumName": "An album", "durationInMillis": 180000,
  "playParams": { "id": "1765230825", "kind": "song" }
} } ] }
"""

        val playlist = source.getPlaylist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "pl.pm-favorites"))!!

        assertEquals("Favorites Mix", playlist.name)
        assertEquals(listOf("A song"), playlist.tracks.map { it.title })
        val reads = requests.filter { it.requestUrl!!.encodedPath.startsWith("/v1/catalog/jp/playlists/pl.pm-") }
        assertTrue(reads.isNotEmpty())
        reads.forEach { assertEquals("user", it.getHeader("Music-User-Token")) }
    }
}
