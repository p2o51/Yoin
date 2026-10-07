package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.spotify.SpotifyDevice
import com.gpo.yoin.data.source.spotify.pickLocalSpotifyDevice
import com.gpo.yoin.data.source.spotify.startPlaybackBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStartPlanTest {

    private fun track(rawId: String, provider: String = MediaId.PROVIDER_SPOTIFY) = Track(
        id = MediaId(provider, rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    private val album = ActivityContext.Album(albumId = "spotify:al1", albumName = "Album")
    private val playlist = ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Playlist")
    private val tracks = (1..6).map { track("t$it") }

    private fun attempts(
        context: ActivityContext,
        list: List<Track> = tracks,
        start: Int,
        shuffled: Boolean = false,
        playlistOffset: (MediaId, Int) -> Int? = { _, _ -> null },
    ) = spotifyStartAttempts(context, list, start, shuffled, playlistOffset)

    @Test
    fun should_startTheAlbumAtTheTappedTracksUri_when_aTrackIsTappedInAnAlbum() {
        val result = attempts(album, start = 3)

        assertEquals(
            SpotifyStartAttempt.Context(contextUri = "spotify:album:al1", offsetUri = "spotify:track:t4"),
            result.first(),
        )
    }

    @Test
    fun should_neverUseAPositionForAnAlbum_when_theListIsShuffled() {
        val shuffled = tracks.reversed()

        val result = attempts(album, list = shuffled, start = 0, shuffled = true)

        assertEquals(
            SpotifyStartAttempt.Context(contextUri = "spotify:album:al1", offsetUri = "spotify:track:t6"),
            result.first(),
        )
    }

    @Test
    fun should_useTheProvenRawOffset_when_aPlaylistTrackIsTapped() {
        val result = attempts(playlist, start = 2, playlistOffset = { _, visible -> visible + 5 })

        assertEquals(
            SpotifyStartAttempt.Context(contextUri = "spotify:playlist:pl1", offsetPosition = 7),
            result.first(),
        )
    }

    @Test
    fun should_fallBackToTheUri_when_thePlaylistOffsetIsUnknownOrTheListIsShuffled() {
        val unknown = attempts(playlist, start = 2)
        val shuffled = attempts(playlist, start = 2, shuffled = true, playlistOffset = { _, _ -> 9 })

        val expected = SpotifyStartAttempt.Context(contextUri = "spotify:playlist:pl1", offsetUri = "spotify:track:t3")
        assertEquals(expected, unknown.first())
        assertEquals(expected, shuffled.first())
    }

    @Test
    fun should_tryLikedSongsThenThePlainList_when_playingFromLikedSongs() {
        val result = attempts(ActivityContext.LikedSongs(), start = 1)

        assertEquals(SpotifyStartAttempt.LikedSongs(offsetUri = "spotify:track:t2"), result[0])
        assertEquals(SpotifyStartAttempt.Tracks(uris = tracks.map { "spotify:track:${it.id.rawId}" }, offsetPosition = 1), result[1])
    }

    @Test
    fun should_playThePlainListWithoutTouchingTheQueue_when_thereIsNoContext() {
        val result = attempts(ActivityContext.None, start = 4)

        assertEquals(listOf(SpotifyStartAttempt.Tracks(uris = tracks.map { "spotify:track:${it.id.rawId}" }, offsetPosition = 4)), result)
    }

    @Test
    fun should_windowALongListAroundTheStart_when_itExceedsTheUriLimit() {
        val long = (0 until 250).map { track("x$it") }

        val result = attempts(ActivityContext.None, list = long, start = 120).single() as SpotifyStartAttempt.Tracks

        assertEquals(SPOTIFY_START_MAX_URIS, result.uris.size)
        assertEquals("spotify:track:x100", result.uris.first())
        assertEquals("spotify:track:x120", result.uris[result.offsetPosition])
    }

    @Test
    fun should_returnNothing_when_theStartTrackIsNotASpotifyTrack() {
        val mixed = listOf(track("s1", provider = MediaId.PROVIDER_SUBSONIC))

        assertTrue(attempts(album, list = mixed, start = 0).isEmpty())
    }

    @Test
    fun should_nameTheStartTrackByUri_when_buildingAContextBody() {
        val body = startPlaybackBody(
            contextUri = "spotify:album:al1",
            uris = null,
            offsetPosition = null,
            offsetUri = "spotify:track:t4",
        )

        assertEquals("""{"context_uri":"spotify:album:al1","offset":{"uri":"spotify:track:t4"}}""", body)
    }

    @Test
    fun should_sendAUrisListWithAPosition_when_buildingAPlainListBody() {
        val body = startPlaybackBody(
            contextUri = null,
            uris = listOf("spotify:track:a", "spotify:track:b"),
            offsetPosition = 1,
            offsetUri = null,
        )

        assertEquals("""{"uris":["spotify:track:a","spotify:track:b"],"offset":{"position":1}}""", body)
    }

    @Test
    fun should_pickThisPhonesDevice_when_anotherDeviceIsActive() {
        val devices = listOf(
            SpotifyDevice(id = "fold", name = "SM-F971Q", type = "Smartphone", isActive = true),
            SpotifyDevice(id = "tab", name = "Pixel Tablet", type = "Tablet"),
        )

        assertEquals("tab", pickLocalSpotifyDevice(devices, listOf("", "Pixel Tablet"))?.id)
    }

    @Test
    fun should_preferTheUserSetNameAndIgnoreDevicesWithoutAnId_when_matching() {
        val devices = listOf(
            SpotifyDevice(id = null, name = "Gpo's Tablet", type = "Tablet"),
            SpotifyDevice(id = "named", name = "gpo's tablet", type = "Tablet"),
        )

        assertEquals("named", pickLocalSpotifyDevice(devices, listOf("Gpo's Tablet", "Pixel Tablet"))?.id)
        assertNull(pickLocalSpotifyDevice(devices, listOf("Pixel 9")))
    }
}
