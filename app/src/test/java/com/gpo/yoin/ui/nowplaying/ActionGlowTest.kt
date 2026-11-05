package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gpo.yoin.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionGlowTest {
    @Test
    fun should_lightShuffle_when_roundKeyTurnsShuffleOn() {
        assertEquals(ActionGlowKind.ShuffleOn, playModeGlowKind(PlayMode.RepeatAll, PlayMode.RepeatAll.next()))
    }

    @Test
    fun should_lightEachRepeatState_when_repeatSegmentSteps() {
        assertEquals(ActionGlowKind.RepeatOne, playModeGlowKind(PlayMode.Shuffle, PlayMode.Shuffle.nextRepeat()))
        assertEquals(ActionGlowKind.RepeatOff, playModeGlowKind(PlayMode.RepeatOne, PlayMode.RepeatOne.nextRepeat()))
        assertEquals(ActionGlowKind.RepeatAll, playModeGlowKind(PlayMode.RepeatOff, PlayMode.RepeatOff.nextRepeat()))
    }

    @Test
    fun should_lightShuffleOff_when_shuffleSegmentTurnsItOff() {
        assertEquals(ActionGlowKind.ShuffleOff, playModeGlowKind(PlayMode.Shuffle, PlayMode.Shuffle.withShuffle(false)))
    }

    @Test
    fun should_lightNothing_when_modeUnchanged() {
        assertNull(playModeGlowKind(PlayMode.RepeatOne, PlayMode.RepeatOne))
    }

    @Test
    fun should_drain_when_ratingCleared() {
        assertEquals(ActionGlowKind.RatingClear, ratingGlowKind(0f))
        assertEquals(ActionGlowKind.RatingClear, ratingGlowKind(null))
        assertEquals(ActionGlowKind.RatingCommit, ratingGlowKind(7.5f))
    }

    @Test
    fun should_runLonger_when_ratingIsFullMarks() {
        assertEquals(1300, ActionGlowKind.RatingCommit.durationFor(8f))
        assertEquals(1700, ActionGlowKind.RatingCommit.durationFor(10f))
        assertEquals(ActionGlowKind.FavoriteOn.durationMs, ActionGlowKind.FavoriteOn.durationFor(10f))
    }

    @Test
    fun should_placeRatingFromBottom_when_barIsVertical() {
        val bar = Rect(100f, 0f, 150f, 200f)
        assertEquals(Offset(125f, 200f), ratingPoint(bar, 0f, vertical = true, rtl = false))
        assertEquals(Offset(125f, 100f), ratingPoint(bar, 5f, vertical = true, rtl = false))
        assertEquals(Offset(125f, 0f), ratingPoint(bar, 10f, vertical = true, rtl = false))
    }

    @Test
    fun should_placeRatingFromStart_when_barIsHorizontal() {
        val bar = Rect(0f, 0f, 200f, 40f)
        assertEquals(Offset(50f, 20f), ratingPoint(bar, 2.5f, vertical = false, rtl = false))
        assertEquals(Offset(150f, 20f), ratingPoint(bar, 2.5f, vertical = false, rtl = true))
    }
}
