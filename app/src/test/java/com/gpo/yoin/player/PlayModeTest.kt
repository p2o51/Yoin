package com.gpo.yoin.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModeTest {

    @Test
    fun should_cycleThroughAllFourModes_when_nextIsCalled() {
        assertEquals(PlayMode.Shuffle, PlayMode.RepeatAll.next())
        assertEquals(PlayMode.ShuffleRepeatOne, PlayMode.Shuffle.next())
        assertEquals(PlayMode.RepeatOne, PlayMode.ShuffleRepeatOne.next())
        assertEquals(PlayMode.RepeatAll, PlayMode.RepeatOne.next())
    }

    @Test
    fun should_flipExactlyOneAxis_when_nextIsCalled() {
        PlayMode.entries.forEach { mode ->
            val next = mode.next()
            val shuffleFlipped = mode.shuffle != next.shuffle
            val repeatFlipped = mode.repeatOne != next.repeatOne
            assertTrue("$mode → $next flips one axis", shuffleFlipped xor repeatFlipped)
        }
    }

    @Test
    fun should_returnToStart_when_cycledFourTimes() {
        var mode = PlayMode.RepeatAll
        repeat(4) { mode = mode.next() }
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
    fun should_keepBothAxes_when_repeatIsOneAndShuffleIsOn() {
        assertEquals(PlayMode.ShuffleRepeatOne, PlayMode.of(Player.REPEAT_MODE_ONE, shuffle = true))
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
        assertEquals(Player.REPEAT_MODE_ONE, PlayMode.ShuffleRepeatOne.repeatMode)
        assertTrue(PlayMode.ShuffleRepeatOne.shuffle)
        assertEquals(Player.REPEAT_MODE_ONE, PlayMode.RepeatOne.repeatMode)
        assertFalse(PlayMode.RepeatOne.shuffle)
    }

    @Test
    fun should_keepRepeatAxis_when_onlyShuffleIsToggled() {
        assertEquals(PlayMode.ShuffleRepeatOne, PlayMode.RepeatOne.withShuffle(true))
        assertEquals(PlayMode.RepeatOne, PlayMode.ShuffleRepeatOne.withShuffle(false))
        assertEquals(PlayMode.Shuffle, PlayMode.RepeatAll.withShuffle(true))
        assertEquals(PlayMode.RepeatAll, PlayMode.Shuffle.withShuffle(false))
    }

    @Test
    fun should_defaultToRepeatAll_when_playbackStateIsFresh() {
        assertEquals(PlayMode.RepeatAll, PlaybackState().playMode)
    }
}
