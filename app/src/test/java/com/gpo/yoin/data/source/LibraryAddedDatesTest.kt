package com.gpo.yoin.data.source

import com.gpo.yoin.data.local.SpotifyLibraryAlbumCache
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import com.gpo.yoin.data.remote.Playlist as SubsonicPlaylist
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.data.source.spotify.toAlbum
import com.gpo.yoin.data.source.subsonic.toAlbum
import com.gpo.yoin.data.source.subsonic.toPlaylist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Each service fills libraryAddedAt, the date Library's Recently added sorts
 * by. Subsonic's addedAt stays the star time Home's Recently Added reads.
 */
class LibraryAddedDatesTest {

    @Test
    fun should_dateSubsonicAlbumByCreatedAndKeepStarTime_when_mapping() {
        val album = SubsonicAlbum(
            id = "al1",
            name = "Album",
            created = "2021-03-05T12:34:56.000Z",
            starred = "2025-01-01T00:00:00.000Z"
        ).toAlbum()

        assertEquals("2021-03-05T12:34:56.000Z", album.libraryAddedAt)
        assertEquals("2025-01-01T00:00:00.000Z", album.addedAt)
    }

    @Test
    fun should_readCreated_when_subsonicAlbumListArrivesAsJson() {
        val album = Json { ignoreUnknownKeys = true }.decodeFromString<SubsonicAlbum>(
            """{"id":"al1","name":"Album","created":"2020-02-02T02:02:02Z","songCount":3}"""
        ).toAlbum()

        assertEquals("2020-02-02T02:02:02Z", album.libraryAddedAt)
        assertNull(album.addedAt)
    }

    @Test
    fun should_dateSubsonicPlaylistByCreated_when_mapping() {
        val playlist = SubsonicPlaylist(id = "pl1", name = "Mine", created = "2022-04-04T00:00:00Z").toPlaylist()

        assertEquals("2022-04-04T00:00:00Z", playlist.libraryAddedAt)
    }

    @Test
    fun should_dateSavedSpotifyAlbumByItsAddedAt_when_readFromCache() {
        val album = SpotifyLibraryAlbumCache(
            profileId = "p",
            albumId = "al1",
            name = "Album",
            artist = "Artist",
            artistId = null,
            coverArtKey = null,
            songCount = null,
            year = null,
            isSaved = true,
            addedAt = "2023-06-07T08:09:10Z",
            cachedAt = 0L
        ).toAlbum()

        assertEquals("2023-06-07T08:09:10Z", album.libraryAddedAt)
    }

    @Test
    fun should_dateAppleLibraryAlbumAndPlaylistByDateAdded_when_mapping() {
        val album = AppleMusicSource.album(
            json("""{"id":"l.a","type":"library-albums","attributes":{"name":"A","dateAdded":"2024-01-02T03:04:05Z"}}""")
        )
        val playlist = AppleMusicSource.playlist(
            json("""{"id":"p.a","type":"library-playlists","attributes":{"name":"P","dateAdded":"2022-02-02T00:00:00Z"}}""")
        )
        val catalogPlaylist = AppleMusicSource.playlist(
            json("""{"id":"pl.c","type":"playlists","attributes":{"name":"Catalog"}}""")
        )

        assertEquals("2024-01-02T03:04:05Z", album.libraryAddedAt)
        assertEquals("2022-02-02T00:00:00Z", playlist.libraryAddedAt)
        assertNull(catalogPlaylist.libraryAddedAt)
    }

    private fun json(raw: String): JsonObject = Json.decodeFromString<JsonObject>(raw)
}
