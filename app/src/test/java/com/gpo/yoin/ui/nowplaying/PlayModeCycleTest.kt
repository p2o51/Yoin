package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A run of quick taps all the way round the play-mode cycle shows the split once. */
class PlayModeCycleTest {

    private val cycle = PlayModeCycle(maxGapMs = 1_000L)

    @Test
    fun should_closeTheCycle_when_fourQuickTapsComeBackToTheStart() {
        assertEquals(listOf(false, false, false, true), tapRound(from = PlayMode.RepeatAll, startMs = 0L, gapMs = 400L))
    }

    @Test
    fun should_notCloseIt_when_aTapComesAfterAPause() {
        var mode = PlayMode.RepeatAll
        var now = 0L
        val closed = (1..4).map { tap ->
            now += if (tap == 3) 2_000L else 400L
            val next = mode.next()
            cycle.onTap(mode, next, now).also { mode = next }
        }

        assertFalse(closed.any { it })
    }

    @Test
    fun should_startCountingAgain_when_aCycleHasClosed() {
        assertTrue(tapRound(from = PlayMode.Shuffle, startMs = 0L, gapMs = 300L).last())

        val again = tapRound(from = PlayMode.Shuffle, startMs = 1_200L, gapMs = 300L)
        assertEquals(listOf(false, false, false, true), again)
    }

    private fun tapRound(from: PlayMode, startMs: Long, gapMs: Long): List<Boolean> {
        var mode = from
        return (1..4).map { tap ->
            val next = mode.next()
            cycle.onTap(mode, next, startMs + tap * gapMs).also { mode = next }
        }
    }
}
