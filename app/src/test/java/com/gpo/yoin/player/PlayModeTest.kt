package com.gpo.yoin.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModeTest {

    @Test
    fun should_cycleRepeatAllShuffleRepeatOne_when_nextIsCalled() {
        assertEquals(PlayMode.Shuffle, PlayMode.RepeatAll.next())
        assertEquals(PlayMode.RepeatOne, PlayMode.Shuffle.next())
        assertEquals(PlayMode.RepeatAll, PlayMode.RepeatOne.next())
    }

    @Test
    fun should_returnToStart_when_cycledThreeTimes() {
        var mode = PlayMode.RepeatAll
        repeat(3) { mode = mode.next() }
        assertEquals(PlayMode.RepeatAll, mode)
    }

    @Test
    fun should_readRepeatAll_when_repeatIsOff() {
        assertEquals(PlayMode.RepeatAll, PlayMode.of(Player.REPEAT_MODE_OFF, shuffle = false))
    }

    @Test
    fun should_readRepeatAll_when_repeatIsAllAndShuffleIsOff() {
        assertEquals(PlayMode.RepeatAll, PlayMode.of(Player.REPEAT_MODE_ALL, shuffle = false))
    }

    @Test
    fun should_readShuffle_when_repeatIsAllAndShuffleIsOn() {
        assertEquals(PlayMode.Shuffle, PlayMode.of(Player.REPEAT_MODE_ALL, shuffle = true))
    }

    @Test
    fun should_readShuffle_when_repeatIsOffAndShuffleIsOn() {
        assertEquals(PlayMode.Shuffle, PlayMode.of(Player.REPEAT_MODE_OFF, shuffle = true))
    }

    @Test
    fun should_readRepeatOne_when_repeatIsOneEvenWithShuffleOn() {
        assertEquals(PlayMode.RepeatOne, PlayMode.of(Player.REPEAT_MODE_ONE, shuffle = true))
        assertEquals(PlayMode.RepeatOne, PlayMode.of(Player.REPEAT_MODE_ONE, shuffle = false))
    }

    @Test
    fun should_roundTrip_when_modeIsWrittenAndReadBack() {
        PlayMode.entries.forEach { mode ->
            assertEquals(mode, PlayMode.of(mode.repeatMode, mode.shuffle))
        }
    }

    @Test
    fun should_writeBothRepeatAndShuffle_when_modeIsApplied() {
        assertEquals(Player.REPEAT_MODE_ALL, PlayMode.RepeatAll.repeatMode)
        assertFalse(PlayMode.RepeatAll.shuffle)
        assertEquals(Player.REPEAT_MODE_ALL, PlayMode.Shuffle.repeatMode)
        assertTrue(PlayMode.Shuffle.shuffle)
        assertEquals(Player.REPEAT_MODE_ONE, PlayMode.RepeatOne.repeatMode)
        assertFalse(PlayMode.RepeatOne.shuffle)
    }

    @Test
    fun should_defaultToRepeatAll_when_playbackStateIsFresh() {
        assertEquals(PlayMode.RepeatAll, PlaybackState().playMode)
    }
}
