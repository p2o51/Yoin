package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spotify's "playing from" reports against the context a Yoin play set: only a different album
 * or playlist Spotify can name replaces it; no context, an empty or blank uri or one Yoin can't
 * name leaves it alone. Playback adopted from Spotify keeps following Spotify.
 */
class SpotifyContextSyncTest {

    private val album = ActivityContext.Album(
        albumId = MediaId.spotify("al1").toString(),
        albumName = "TUNE & PLAY",
        artistName = "TUIDE",
        coverArtId = "https://i.scdn.co/image/al1"
    )
    private val artist = ActivityContext.Artist(artistId = MediaId.spotify("ar1").toString(), artistName = "TUIDE")

    @Test
    fun should_keepYoinsAlbum_when_spotifyReportsNoContext() {
        assertNull(fromYoin(album, null))
        assertNull(fromYoin(album, report(null)))
    }

    @Test
    fun should_keepYoinsAlbum_when_spotifyReportsAnEmptyOrBlankUri() {
        assertNull(fromYoin(album, report("", "")))
        assertNull(fromYoin(album, report("  ", "x")))
        assertNull(fromYoin(album, report("spotify:album:", "x")))
    }

    @Test
    fun should_keepYoinsContext_when_spotifyReportsAContextYoinCannotName() {
        val unnamed = listOf("spotify:artist:ar1", "spotify:user:me:collection", "spotify:track:t1", "spotify:x")

        unnamed.forEach { uri -> assertNull(uri, fromYoin(album, report(uri, "Radio"))) }
    }

    @Test
    fun should_keepYoinsAlbum_when_spotifyReportsTheSameAlbum() {
        // Spotify's title is bare; Yoin's context keeps its artist and cover.
        assertNull(fromYoin(album, report("spotify:album:al1", "")))
    }

    @Test
    fun should_followSpotify_when_itMovesToAnotherAlbumOrPlaylist() {
        assertEquals(
            ActivityContext.Album(albumId = "spotify:al2", albumName = "Other"),
            fromYoin(album, report("spotify:album:al2", "Other"))
        )
        assertEquals(
            ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Mix"),
            fromYoin(album, report("spotify:playlist:pl1", "Mix"))
        )
    }

    @Test
    fun should_keepYoinsArtist_when_spotifyReportsNothingItCanNameForTheUrisStart() {
        assertNull(fromYoin(artist, null))
        assertNull(fromYoin(artist, report("", null)))
        assertNull(fromYoin(artist, report("spotify:artist:ar1", "TUIDE")))
    }

    @Test
    fun should_clearAnAdoptedContext_when_spotifyReportsNothingItCanName() {
        val adopted = ActivityContext.Album(albumId = "spotify:al1", albumName = "TUNE & PLAY")

        assertEquals(ActivityContext.None, fromSpotify(adopted, null))
        assertEquals(ActivityContext.None, fromSpotify(adopted, report("", "")))
        assertEquals(ActivityContext.None, fromSpotify(adopted, report("spotify:artist:ar1", "Radio")))
        assertNull(fromSpotify(ActivityContext.None, null))
    }

    @Test
    fun should_nameTheAlbumOrPlaylist_when_derivingSpotifysContext() {
        assertEquals(
            ActivityContext.Album(albumId = "spotify:al1", albumName = "TUNE & PLAY"),
            deriveSpotifyActivityContext(report("spotify:album:al1", "TUNE & PLAY"))
        )
        assertEquals(
            ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = ""),
            deriveSpotifyActivityContext(report("spotify:playlist:pl1", "  "))
        )
        assertNull(deriveSpotifyActivityContext(report(SPOTIFY_LIKED_SONGS_CONTEXT_URI, "Liked Songs")))
    }

    private fun report(uri: String?, title: String? = null) = SpotifyPlaybackContext(uri = uri, title = title)

    /** [current] was set by a Yoin play. */
    private fun fromYoin(current: ActivityContext, reported: SpotifyPlaybackContext?) =
        reconcileSpotifyContext(current, startedInYoin = true, reported = reported)

    /** [current] was read from Spotify (adopted playback). */
    private fun fromSpotify(current: ActivityContext, reported: SpotifyPlaybackContext?) =
        reconcileSpotifyContext(current, startedInYoin = false, reported = reported)
}
