package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicKit can leave a song buffering at 0:00 without an error (an asset task that threw inside an unread
 * Future). The player turns 30 s of buffering without any progress into a playback error.
 */
class MusicKitStallWatchTest {

    private val watch = MusicKitStallWatch(stallMs = 30_000)

    private fun tick(
        atMs: Long,
        wantsPlay: Boolean = true,
        buffering: Boolean = true,
        item: Long? = 1,
        positionMs: Long = 0,
        bufferedMs: Long = 0,
    ) = watch.observe(atMs, wantsPlay, buffering, item, positionMs, bufferedMs)

    @Test
    fun should_reportStall_when_bufferingWithoutProgressFor30s() {
        assertFalse(tick(0))
        assertFalse(tick(29_000))
        assertTrue(tick(30_000))
        // Reported once; the clock starts over.
        assertFalse(tick(31_000))
    }

    @Test
    fun should_notReportStall_when_positionAdvances() {
        for (second in 0L..60L) {
            assertFalse(tick(second * 1_000, positionMs = second * 1_000))
        }
    }

    @Test
    fun should_restartClock_when_bufferedPositionGrows() {
        assertFalse(tick(0))
        assertFalse(tick(20_000, bufferedMs = 5_000))
        assertFalse(tick(49_000, bufferedMs = 5_000))
        assertTrue(tick(50_000, bufferedMs = 5_000))
    }

    @Test
    fun should_restartClock_when_itemChanges() {
        assertFalse(tick(0, item = 1))
        assertFalse(tick(25_000, item = 2))
        assertFalse(tick(54_000, item = 2))
        assertTrue(tick(55_000, item = 2))
    }

    @Test
    fun should_notReportStall_when_pausedOrNotBuffering() {
        assertFalse(tick(0))
        assertFalse(tick(20_000, wantsPlay = false))
        assertFalse(tick(40_000))
        assertFalse(tick(60_000, buffering = false))
        assertFalse(tick(80_000))
        assertTrue(tick(110_000))
    }
}
