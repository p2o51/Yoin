package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMappersTest {

    @Test
    fun should_namespace_mapped_entities_with_spotify_provider() {
        val album = SpotifyAlbumObject(
            id = "album-1",
            name = "Album",
            images = listOf(SpotifyImageObject(url = "https://cdn.example/album.jpg")),
            artists = listOf(SpotifySimplifiedArtistObject(id = "artist-1", name = "Artist")),
            totalTracks = 9,
            releaseDate = "2024-03-01",
        )
        val track = SpotifyTrackObject(
            id = "track-1",
            name = "Track",
            album = album.toSimplifiedAlbum(),
            artists = listOf(SpotifySimplifiedArtistObject(id = "artist-1", name = "Artist")),
            durationMs = 185_000,
            trackNumber = 2,
        )

        val mappedTrack = track.toTrack(savedTrackIds = setOf("track-1"))
        val mappedAlbum = album.toAlbum(
            savedAlbumIds = setOf("album-1"),
            savedTrackIds = setOf("track-1"),
            tracksOverride = listOf(track),
        )

        assertEquals(MediaId.spotify("track-1"), mappedTrack.id)
        assertEquals(MediaId.spotify("artist-1"), mappedTrack.artistId)
        assertEquals(MediaId.spotify("album-1"), mappedTrack.albumId)
        assertEquals(CoverRef.Url("https://cdn.example/album.jpg"), mappedTrack.coverArt)
        assertTrue(mappedTrack.isStarred)

        assertEquals(MediaId.spotify("album-1"), mappedAlbum.id)
        assertEquals(MediaId.spotify("artist-1"), mappedAlbum.artistId)
        assertEquals(9, mappedAlbum.songCount)
        assertEquals(185, mappedAlbum.durationSec)
        assertTrue(mappedAlbum.isStarred)
    }

    @Test
    fun should_group_artists_into_alpha_indices() {
        val indices = listOf(
            SpotifyArtistObject(id = "b-1", name = "blur"),
            SpotifyArtistObject(id = "a-1", name = "Aphex Twin"),
            SpotifyArtistObject(id = "num-1", name = "2814"),
        ).map { artist -> artist.toArtist() }.toArtistIndices()

        assertEquals(listOf("#", "A", "B"), indices.map { it.name })
        assertEquals(MediaId.spotify("num-1"), indices.first().artists.first().id)
        assertEquals(MediaId.spotify("a-1"), indices[1].artists.first().id)
        assertEquals(MediaId.spotify("b-1"), indices[2].artists.first().id)
    }
}
