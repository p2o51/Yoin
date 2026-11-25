package com.gpo.yoin.data.cache

import com.gpo.yoin.data.local.DetailCacheEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ContentRating
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The explicit / clean mark through the detail cache: kept on albums and their tracks, artists'
 * releases and playlists' tracks; album and artist rows written before it are read again for the
 * services that mark them (Apple Music, Spotify), and kept for Subsonic, which doesn't.
 */
class DetailCacheContentRatingTest {
    private val now = 1_000L

    private fun TestScope.store(dao: FakeDetailCacheDao) = DetailCacheStore(dao = dao, clock = { now }, scope = backgroundScope)

    private fun track(provider: String, id: String, rating: ContentRating?) = Track(
        id = MediaId(provider, id),
        title = "Song $id",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = 1,
        year = null,
        genre = null,
        userRating = null,
        contentRating = rating,
    )

    private fun album(provider: String, rating: ContentRating?, tracks: List<Track> = emptyList()) = Album(
        id = MediaId(provider, "al-1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = tracks.size,
        durationSec = 0,
        year = 2017,
        genre = null,
        tracks = tracks,
        contentRating = rating,
    )

    @Test
    fun should_keepTheMarks_when_anAlbumRoundTrips() = runTest {
        val store = store(FakeDetailCacheDao())
        val provider = MediaId.PROVIDER_APPLE_MUSIC
        val written = album(
            provider,
            ContentRating.Explicit,
            listOf(
                track(provider, "1", ContentRating.Explicit),
                track(provider, "2", ContentRating.Clean),
                track(provider, "3", null),
            ),
        )
        store.writeAlbum(PROFILE, written.id.toString(), written)

        val read = store.readAlbum(PROFILE, written.id.toString())!!.value()!!

        assertEquals(ContentRating.Explicit, read.contentRating)
        assertEquals(listOf(ContentRating.Explicit, ContentRating.Clean, null), read.tracks.map { it.contentRating })
    }

    @Test
    fun should_keepTheMarks_when_artistsAndPlaylistsRoundTrip() = runTest {
        val store = store(FakeDetailCacheDao())
        val provider = MediaId.PROVIDER_SPOTIFY
        val artist = ArtistDetail(
            id = MediaId(provider, "r1"),
            name = "Artist",
            albumCount = 1,
            coverArt = null,
            albums = listOf(album(provider, ContentRating.Explicit)),
        )
        val playlist = Playlist(
            id = MediaId(provider, "p1"),
            name = "Mix",
            owner = null,
            coverArt = null,
            songCount = 1,
            durationSec = 200,
            tracks = listOf(track(provider, "1", ContentRating.Explicit)),
        )
        store.writeArtist(PROFILE, artist.id.toString(), artist)
        store.writePlaylist(PROFILE, playlist.id.toString(), playlist)

        val readArtist = store.readArtist(PROFILE, artist.id.toString())!!.value()!!
        val readPlaylist = store.readPlaylist(PROFILE, playlist.id.toString())!!.value()!!

        assertEquals(ContentRating.Explicit, readArtist.albums.single().contentRating)
        assertEquals(ContentRating.Explicit, readPlaylist.tracks.single().contentRating)
    }

    @Test
    fun should_readAgain_when_anAppleOrSpotifyRowPredatesTheMark() = runTest {
        val dao = FakeDetailCacheDao()
        val store = store(dao)
        listOf(MediaId.PROVIDER_APPLE_MUSIC, MediaId.PROVIDER_SPOTIFY).forEach { provider ->
            val id = MediaId(provider, "al-1").toString()
            // What the build before the mark wrote: dated and credited, no format, no ratings.
            dao.upsert(entry("ALBUM", id, """{"id":"$id","name":"Album","dated":true,"credited":true}"""))
            val artistId = MediaId(provider, "r1").toString()
            dao.upsert(entry("ARTIST", artistId, """{"id":"$artistId","name":"Artist"}"""))

            assertNull(store.readAlbum(PROFILE, id)!!.value())
            assertNull(store.readArtist(PROFILE, artistId)!!.value())
        }
    }

    @Test
    fun should_serveASubsonicRow_when_itPredatesTheMark() = runTest {
        val dao = FakeDetailCacheDao()
        val store = store(dao)
        val id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1").toString()
        dao.upsert(entry("ALBUM", id, """{"id":"$id","name":"Album","dated":true,"credited":true}"""))
        val artistId = MediaId(MediaId.PROVIDER_SUBSONIC, "r1").toString()
        dao.upsert(entry("ARTIST", artistId, """{"id":"$artistId","name":"Artist"}"""))

        val album = store.readAlbum(PROFILE, id)!!.value()

        assertEquals("Album", album?.name)
        assertNull(album?.contentRating)
        assertEquals("Artist", store.readArtist(PROFILE, artistId)!!.value()?.name)
    }

    @Test
    fun should_stillMissARowFromBeforeReleaseDates_whateverItsProvider() = runTest {
        val dao = FakeDetailCacheDao()
        val store = store(dao)
        val id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1").toString()
        dao.upsert(entry("ALBUM", id, """{"id":"$id","name":"Album"}"""))

        assertNull(store.readAlbum(PROFILE, id)!!.value())
    }

    private fun entry(kind: String, id: String, json: String) =
        DetailCacheEntry(profileId = PROFILE, kind = kind, entityId = id, json = json, cachedAt = now, accessedAt = now)

    private companion object {
        const val PROFILE = "profile-1"
    }
}
