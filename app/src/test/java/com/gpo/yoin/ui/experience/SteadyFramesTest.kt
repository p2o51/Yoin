package com.gpo.yoin.ui.experience

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** [SteadyFrameClock]: frames on time pass through; a late one moves it at most one step. */
class SteadyFramesTest {

    /** The outer clock: every frame happens at [now]. */
    private class FixedFrameClock : MonotonicFrameClock {
        var now = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(now)
    }

    private val outer = FixedFrameClock()
    private val steady = SteadyFrameClock(outer, maxStepNanos = STEP)

    private suspend fun frameAt(nanos: Long): Long {
        outer.now = nanos
        return steady.withFrameNanos { it }
    }

    @Test
    fun should_passFrameTimesThrough_when_everyFrameIsOnTime() = runTest {
        val start = 5_000 * MS
        assertEquals(start, frameAt(start))
        assertEquals(start + 16 * MS, frameAt(start + 16 * MS))
        assertEquals(start + 49 * MS, frameAt(start + 49 * MS))
    }

    @Test
    fun should_moveAtMostOneStep_when_aFrameComesLate() = runTest {
        frameAt(0L)
        assertEquals(16 * MS, frameAt(16 * MS))
        // A 115 ms frame, then another: one step each.
        assertEquals(16 * MS + STEP, frameAt(131 * MS))
        assertEquals(16 * MS + 2 * STEP, frameAt(246 * MS))
        // Back on time: real frame times again, from where it got to.
        assertEquals(32 * MS + 2 * STEP, frameAt(262 * MS))
    }

    @Test
    fun should_giveEveryWaiterTheSameTime_when_theyShareAFrame() = runTest {
        frameAt(0L)
        val first = frameAt(200 * MS)
        val second = frameAt(200 * MS)
        assertEquals(STEP, first)
        assertEquals(first, second)
    }

    @Test
    fun should_capTheFramesOfItsAnimations_when_runWithSteadyFrames() = runTest {
        outer.now = 0L
        val times = mutableListOf<Long>()
        withContext(outer) {
            withSteadyFrames(maxStepNanos = STEP) {
                times += withFrameNanos { it }
                outer.now = 500 * MS
                times += withFrameNanos { it }
            }
        }
        assertEquals(listOf(0L, STEP), times)
    }

    private companion object {
        const val MS = 1_000_000L
        const val STEP = STEADY_FRAME_MAX_STEP_NANOS
    }
}
