package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.SpotifyStartAttempt
import com.gpo.yoin.player.spotifyStartAttempts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A play started in Memories (a diary row or a note) carries the memory's album or playlist as
 * full ids: Spotify then starts the album / playlist itself, and Now Playing's "Playing from"
 * label opens the right page. The memory's own entityId is the raw id.
 */
class MemoryPlaybackContextTest {

    @Test
    fun should_startTheSpotifyAlbumAtTheTappedTrack_when_aRowIsTappedInAnAlbumMemory() {
        val songs = (1..5).map { spotifyTrack("t$it", artistRawId = "ar1") }
        val memory = spotifyMemory(MemoryEntityType.ALBUM, entityId = "al1", songs = songs)

        val attempts = startAttempts(memory, startIndex = 1)

        val expected = SpotifyStartAttempt.Context(contextUri = "spotify:album:al1", offsetUri = "spotify:track:t2")
        assertEquals(expected, attempts.first())
    }

    @Test
    fun should_startTheSpotifyPlaylistAtTheTappedTrack_when_aRowIsTappedInAPlaylistMemory() {
        val songs = (1..4).map { spotifyTrack("p$it") }
        val memory = spotifyMemory(MemoryEntityType.PLAYLIST, entityId = "pl1", songs = songs)

        val byUri = startAttempts(memory, startIndex = 2)
        // The memory plays getPlaylist's whole track list, so a proven raw offset lines up too.
        val byPosition = startAttempts(memory, startIndex = 2) { id, visible ->
            visible.takeIf { id == MediaId.spotify("pl1") }
        }

        val playlist = "spotify:playlist:pl1"
        assertEquals(SpotifyStartAttempt.Context(contextUri = playlist, offsetUri = "spotify:track:p3"), byUri.first())
        assertEquals(SpotifyStartAttempt.Context(contextUri = playlist, offsetPosition = 2), byPosition.first())
    }

    @Test
    fun should_carryIdsTheNowPlayingLabelCanOpen_when_anAlbumMemoryPlays() {
        val songs = listOf(spotifyTrack("t1", artistRawId = "ar1"))
        val memory = spotifyMemory(MemoryEntityType.ALBUM, entityId = "al1", songs = songs)

        val context = memory.toPlaybackActivityContext() as ActivityContext.Album

        assertEquals(MediaId.spotify("al1"), MediaId.parse(context.albumId))
        assertEquals(MediaId.spotify("ar1"), MediaId.parse(context.artistId!!))
        assertEquals("Memory", context.albumName)
        assertEquals("Artist", context.artistName)
    }

    @Test
    fun should_carryAFullPlaylistId_when_aSubsonicPlaylistMemoryPlays() {
        val memory = memory(MemoryEntityType.PLAYLIST, "42", MediaId.PROVIDER_SUBSONIC, songs = emptyList())

        val context = memory.toPlaybackActivityContext() as ActivityContext.Playlist

        assertEquals(MediaId.subsonic("42"), MediaId.parse(context.playlistId))
    }

    @Test
    fun should_keepTheRawIdAsTheCoverKey_when_aSubsonicAlbumMemoryHasNoTrackArt() {
        val memory = memory(MemoryEntityType.ALBUM, "al-9", MediaId.PROVIDER_SUBSONIC, songs = emptyList())

        val context = memory.toPlaybackActivityContext() as ActivityContext.Album

        assertEquals("subsonic:al-9", context.albumId)
        // A cover storage key, not an entity id: Subsonic serves album art by the raw id.
        assertEquals("al-9", context.coverArtId)
    }

    @Test
    fun should_playWithoutAContext_when_theMemoryIsASong() {
        val memory = spotifyMemory(MemoryEntityType.SONG, entityId = "t1", songs = listOf(spotifyTrack("t1")))

        assertEquals(ActivityContext.None, memory.toPlaybackActivityContext())
    }

    private fun startAttempts(
        memory: MemoryEntry,
        startIndex: Int,
        playlistOffset: (MediaId, Int) -> Int? = { _, _ -> null }
    ): List<SpotifyStartAttempt> = spotifyStartAttempts(
        activityContext = memory.toPlaybackActivityContext(),
        tracks = memory.playbackSongs,
        startIndex = startIndex,
        shuffled = false,
        playlistOffset = playlistOffset
    )

    private fun spotifyMemory(type: MemoryEntityType, entityId: String, songs: List<Track>) =
        memory(type, entityId, MediaId.PROVIDER_SPOTIFY, songs)

    private fun memory(type: MemoryEntityType, entityId: String, provider: String, songs: List<Track>) = MemoryEntry(
        stableId = "memory:$provider:$entityId",
        sourceActivityId = 1L,
        entityType = type,
        entityId = entityId,
        entityProvider = provider,
        title = "Memory",
        supportingText = "Artist",
        metaText = null,
        coverArtUrl = null,
        timestamp = 0L,
        scoreText = "N/A",
        scoreSupportingText = null,
        footerText = null,
        playbackSongs = songs,
        tracks = emptyList()
    )

    private fun spotifyTrack(rawId: String, artistRawId: String? = null) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = "Artist",
        artistId = artistRawId?.let(MediaId::spotify),
        album = "Memory",
        albumId = MediaId.spotify("al1"),
        coverArt = CoverRef.Url("https://i.scdn.co/image/$rawId"),
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
