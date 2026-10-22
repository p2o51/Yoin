package com.gpo.yoin.debug

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MixedPlaylistLocalStopTest {
    @Test
    fun should_clearPlayIntentBeforeStop_when_waitingForTheLocalHandoffBarrier() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.playWhenReady = true
            player.stop()
            assertTrue(player.playWhenReady)

            player.stopForMixedHandoff()

            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        } finally {
            player.release()
        }
    }
}
