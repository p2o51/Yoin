package com.gpo.yoin.debug

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.SettableFuture
import com.spotify.protocol.types.Artist
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SpotifyForegroundProbePlayerTest {
    @Test
    fun should_stayIdle_when_onlyTheConnectionIsEstablished() {
        val player = SpotifyForegroundProbePlayer { SettableFuture.create<Unit>() }
        player.connectionChanged(true)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.isPlaying)
        assertFalse(player.playWhenReady)
        assertNull(player.currentMediaItem)
        player.release()
    }

    @Test
    fun should_waitForRealObservation_when_playCommandIsPendingOrAcknowledged() {
        val ack = SettableFuture.create<Unit>()
        val actions = mutableListOf<SpotifyProbeAction>()
        val player = SpotifyForegroundProbePlayer {
            actions += it
            ack
        }
        player.connectionChanged(true)
        player.observe(sample(paused = true, speed = 0f))
        player.play()
        assertEquals(listOf("resume"), actions.map { it.name })
        assertFalse(player.isPlaying)
        assertFalse(player.playWhenReady)
        ack.set(Unit)
        assertFalse(player.isPlaying)
        player.observe(sample(paused = false, speed = 1f))
        assertTrue(player.isPlaying)
        player.release()
    }

    @Test
    fun should_reportBufferingWithoutProgress_when_nonPausedSpeedIsZero() {
        val player = SpotifyForegroundProbePlayer { SettableFuture.create<Unit>() }
        player.connectionChanged(true)
        player.observe(sample(paused = false, speed = 0f))
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        assertFalse(player.isPlaying)
        assertEquals(12_000L, player.currentPosition)
        player.release()
    }

    @Test
    fun should_preserveNullTrackAsEmpty_when_spotifyReportsATransientNull() {
        val player = SpotifyForegroundProbePlayer { SettableFuture.create<Unit>() }
        player.connectionChanged(true)
        player.observe(sample(paused = false, speed = 1f))
        player.observe(PlayerState(null, false, 0f, 0, null, null))
        assertNull(player.currentMediaItem)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
        player.release()
    }

    @Test
    fun should_removePlayingState_when_connectionIsLost() {
        val player = SpotifyForegroundProbePlayer { SettableFuture.create<Unit>() }
        player.connectionChanged(true)
        player.observe(sample(paused = false, speed = 1f))
        assertTrue(player.isPlaying)
        player.connectionChanged(false)
        assertNull(player.currentMediaItem)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.isPlaying)
        player.release()
    }

    private fun sample(paused: Boolean, speed: Float) = PlayerState(
        Track(
            Artist("Artist", "spotify:artist:a"), listOf(Artist("Artist", "spotify:artist:a")),
            null, 200_000L, "Track", "spotify:track:t1", null, false, false
        ),
        paused,
        speed,
        12_000L,
        null,
        null
    )
}
