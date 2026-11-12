package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.ui.home.spotifyElsewhereVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyElsewhereTest {

    private fun reply(
        playing: Boolean = true,
        type: String? = "track",
        images: List<SpotifyImageObject> = emptyList(),
    ) = SpotifyPlaybackStateResponse(
        isPlaying = playing,
        item = SpotifyTrackObject(
            name = "Pink + White",
            artists = listOf(
                SpotifySimplifiedArtistObject(name = "Frank Ocean"),
                SpotifySimplifiedArtistObject(name = "Beyoncé"),
            ),
            album = SpotifySimplifiedAlbumObject(name = "Blonde", images = images),
            type = type,
        ),
    )

    @Test
    fun `a track reads as the account's, artists joined`() {
        val elsewhere = reply().toElsewhere("p1")!!
        assertEquals("p1", elsewhere.profileId)
        assertEquals("Pink + White", elsewhere.trackTitle)
        assertEquals("Frank Ocean, Beyoncé", elsewhere.artistName)
        assertTrue(elsewhere.isPlaying)
    }

    @Test
    fun `an episode or an empty reply shows nothing`() {
        assertNull(reply(type = "episode").toElsewhere("p1"))
        assertNull(SpotifyPlaybackStateResponse(isPlaying = true, item = null).toElsewhere("p1"))
    }

    @Test
    fun `the pill takes the smallest cover still sharp at its size`() {
        val images = listOf(
            SpotifyImageObject("640", 640, 640),
            SpotifyImageObject("300", 300, 300),
            SpotifyImageObject("64", 64, 64),
        )
        assertEquals("300", reply(images = images).toElsewhere("p1")!!.coverUrl)
        assertEquals("64", reply(images = listOf(SpotifyImageObject("64", 64, 64))).toElsewhere("p1")!!.coverUrl)
    }

    @Test
    fun `a playing account wins over a paused one`() {
        val paused = reply(playing = false).toElsewhere("paused")!!
        val playing = reply(playing = true).toElsewhere("playing")!!
        assertEquals("playing", pickSpotifyElsewhere(listOf(paused, playing))!!.profileId)
        assertEquals("paused", pickSpotifyElsewhere(listOf(paused))!!.profileId)
        assertNull(pickSpotifyElsewhere(emptyList()))
    }

    @Test
    fun `the pill stays down while Yoin plays`() {
        val elsewhere = reply().toElsewhere("p1")
        assertTrue(spotifyElsewhereVisible(elsewhere, yoinPlaying = false))
        assertFalse(spotifyElsewhereVisible(elsewhere, yoinPlaying = true))
        assertFalse(spotifyElsewhereVisible(null, yoinPlaying = false))
    }
}
