package com.gpo.yoin.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-05 device QA (问题 5): seeking makes Media3 buffer, isPlaying dips
 * false for a moment and the PLAY/PAUSE label flashed PLAY. The control reads
 * [PlaybackState.playWhenReady] instead.
 */
class PlayWhenReadyTest {

    private fun media3(
        playWhenReady: Boolean,
        state: Int,
        suppression: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    ) = playWhenReadyOf(playWhenReady, state, suppression)

    @Test
    fun should_keepMeaningToPlay_when_media3BuffersASeek() {
        assertTrue(media3(playWhenReady = true, state = Player.STATE_BUFFERING))
        assertTrue(media3(playWhenReady = true, state = Player.STATE_READY))
    }

    @Test
    fun should_showPlay_when_pausedEndedIdleOrSuppressed() {
        assertFalse(media3(playWhenReady = false, state = Player.STATE_BUFFERING))
        assertFalse(media3(playWhenReady = true, state = Player.STATE_ENDED))
        assertFalse(media3(playWhenReady = true, state = Player.STATE_IDLE))
        assertFalse(
            media3(
                playWhenReady = true,
                state = Player.STATE_READY,
                suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            ),
        )
    }

    @Test
    fun should_followIsPlaying_when_aBackendHasNoBufferingDistinction() {
        // Spotify App Remote and every hand-built state: no playWhenReady of their own.
        assertTrue(PlaybackState(isPlaying = true).playWhenReady)
        assertFalse(PlaybackState(isPlaying = false).playWhenReady)
        assertFalse(PlaybackState().playWhenReady)
    }
}
