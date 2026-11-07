package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitStartPlayTest {
    private val start = MusicKitStartPlay(retryMs = 1_000, maxRetries = 2)

    @Test
    fun should_resendPlay_when_musicKitStaysPausedAfterLoading() {
        start.sent(0)
        assertFalse(start.observe(500, wanted = true, playing = false, loading = false))
        assertTrue(start.observe(1_000, wanted = true, playing = false, loading = false))
        assertFalse(start.observe(1_500, wanted = true, playing = false, loading = false))
        assertTrue(start.observe(2_000, wanted = true, playing = false, loading = false))
        // Gives up after maxRetries, so a song MusicKit really won't start isn't nagged forever.
        assertFalse(start.observe(3_000, wanted = true, playing = false, loading = false))
        assertFalse(start.pending)
    }

    @Test
    fun should_wait_while_loading() {
        start.sent(0)
        assertFalse(start.observe(5_000, wanted = true, playing = false, loading = true))
        assertTrue(start.observe(5_100, wanted = true, playing = false, loading = false))
    }

    @Test
    fun should_stop_once_playing_so_a_later_focus_pause_stays() {
        start.sent(0)
        assertFalse(start.observe(200, wanted = true, playing = true, loading = false))
        assertFalse(start.pending)
        assertFalse(start.observe(5_000, wanted = true, playing = false, loading = false))
    }

    @Test
    fun should_stop_when_user_paused() {
        start.sent(0)
        assertFalse(start.observe(2_000, wanted = false, playing = false, loading = false))
        assertFalse(start.pending)
    }

    @Test
    fun should_doNothing_when_nothingWasSent() {
        assertFalse(start.observe(10_000, wanted = true, playing = false, loading = false))
    }
}
