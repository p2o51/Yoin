package com.gpo.yoin.data.source.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyPlaylistOffsetMappingTest {

    @Test
    fun should_preserve_raw_playlist_offsets_after_filtering_unmappable_items() {
        val items = listOf(
            SpotifyPlaylistItemObject(track = null),
            SpotifyPlaylistItemObject(track = spotifyTrack(id = "track-1", name = "One")),
            SpotifyPlaylistItemObject(track = spotifyTrack(id = null, name = "Missing id")),
            SpotifyPlaylistItemObject(track = spotifyTrack(id = "track-2", name = "Two")),
        )

        val mapped = items.toTracksWithPlaylistOffsets(savedTrackIds = emptySet())

        assertEquals(listOf(1, 3), mapped.map { it.first })
        assertEquals(listOf("track-1", "track-2"), mapped.map { it.second.id.rawId })
    }

    private fun spotifyTrack(id: String?, name: String): SpotifyTrackObject =
        SpotifyTrackObject(
            id = id,
            name = name,
            artists = listOf(
                SpotifySimplifiedArtistObject(
                    id = "artist-$name",
                    name = "Artist $name",
                ),
            ),
            album = SpotifySimplifiedAlbumObject(
                id = "album-$name",
                name = "Album $name",
            ),
            durationMs = 180_000L,
            trackNumber = 1,
        )
}
