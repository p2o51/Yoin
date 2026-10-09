package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        reply(libraryAlbum("l.1", catalogId = "900"))
        reply(storefront())
        reply(catalogAlbum("900"))
        reply(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to "11"))
        reply(catalogSongs("10", "11", "12"))

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

        assertEquals("/v1/me/library/albums/l.1", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/v1/me/storefront", server.takeRequest().requestUrl!!.encodedPath)
        val catalog = server.takeRequest()
        assertEquals("/v1/catalog/jp/albums/900", catalog.requestUrl!!.encodedPath)
        assertEquals("artists,library", catalog.requestUrl!!.queryParameter("include"))
        assertEquals("user", catalog.getHeader("Music-User-Token"))
        val libraryTracks = server.takeRequest()
        assertEquals("/v1/me/library/albums/l.1/tracks", libraryTracks.requestUrl!!.encodedPath)
        assertEquals("catalog", libraryTracks.requestUrl!!.queryParameter("include"))
        assertEquals("/v1/catalog/jp/albums/900/tracks", server.takeRequest().requestUrl!!.encodedPath)
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
        reply(storefront())
        reply(catalogAlbum("900", libraryId = "l.9"))
        reply(librarySongs("i.5" to "11"))
        reply(catalogSongs("10", "11"))

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        val byId = album.tracks.associateBy { it.id.rawId }
        assertNull(byId.getValue("10").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        assertEquals("i.5", byId.getValue("11").extras[AppleMusicSong.EXTRA_LIBRARY_ID])
        server.takeRequest()
        server.takeRequest()
        assertEquals("/v1/me/library/albums/l.9/tracks", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun should_openCatalogAlbumUnmarked_when_libraryLookupFails() = runTest {
        reply(storefront())
        server.enqueue(MockResponse().setResponseCode(500))
        reply(catalogAlbum("900"))
        reply(catalogSongs("10", "11"))

        val album = source.getAlbum(MediaId("applemusic", "900"))!!

        assertEquals(listOf("10", "11"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        server.takeRequest()
        assertEquals("artists,library", server.takeRequest().requestUrl!!.queryParameter("include"))
        assertEquals("artists", server.takeRequest().requestUrl!!.queryParameter("include"))
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
        reply(storefront())
        reply(catalogAlbum("900", libraryId = "l.9"))
        server.enqueue(MockResponse().setResponseCode(404))
        reply(catalogSongs("10", "11"))

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
        reply(libraryAlbum("l.1", catalogId = "900"))
        reply(storefront())
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        reply(librarySongs("i.1" to "10", "i.3" to null))

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "library:l.1"), album.id)
        assertEquals(listOf("10", "library:i.3"), album.tracks.map { it.id.rawId })
    }

    @Test fun should_keepLibraryTracklistDeduplicated_when_albumHasNoCatalogMatch() = runTest {
        reply(libraryAlbum("l.1", catalogId = null))
        reply(librarySongs("i.1" to "10", "i.2" to "10", "i.3" to null))

        val album = source.getAlbum(MediaId("applemusic", "library:l.1"))!!

        assertEquals(MediaId("applemusic", "library:l.1"), album.id)
        assertEquals(listOf("10", "library:i.3"), album.tracks.map { it.id.rawId })
        assertTrue(album.tracks.none { it.extras.containsKey(AppleMusicSong.EXTRA_LIBRARY_CHECKED) })
        assertEquals(2, server.requestCount)
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

    private fun storefront() = """{"data":[{"id":"jp"}]}"""

    private fun libraryAlbum(id: String, catalogId: String?) = """
{"data":[{"id":"$id","type":"library-albums","attributes":{"name":"Album","trackCount":3},
 "relationships":{"catalog":{"data":[${catalogId?.let { """{"id":"$it","type":"albums"}""" } ?: ""}]}}}]}
"""

    private fun catalogAlbum(id: String, libraryId: String? = null) = """
{"data":[{"id":"$id","type":"albums","attributes":{"name":"Album","artistName":"Artist","trackCount":3},
 "relationships":{
   "artists":{"data":[{"id":"7","type":"artists"}]},
   "library":{"data":[${libraryId?.let { """{"id":"$it","type":"library-albums"}""" } ?: ""}]}
 }}]}
"""

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
