package com.gpo.yoin.ui.experience

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The most a steady clock moves in one frame: 1/24 s, the cap Home edit
 * mode's sway clock uses too. Frames on time — 60 or 120 Hz, a dropped frame
 * or two — never come near it.
 */
internal const val STEADY_FRAME_MAX_STEP_NANOS: Long = 1_000_000_000L / 24

/**
 * A frame clock over [outer] that never jumps: each frame moves it by the
 * real time since the last one, at most [maxStepNanos]. Every waiter in one
 * frame reads the same time, so animations sharing it stay in step.
 */
internal class SteadyFrameClock(
    private val outer: MonotonicFrameClock,
    private val maxStepNanos: Long
) : MonotonicFrameClock {
    private var lastFrameNanos = Long.MIN_VALUE
    private var steadyNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        outer.withFrameNanos { frameTimeNanos -> onFrame(steadyTimeAt(frameTimeNanos)) }

    /** This clock's time for the outer clock's frame at [frameTimeNanos]. */
    internal fun steadyTimeAt(frameTimeNanos: Long): Long {
        steadyNanos = if (lastFrameNanos == Long.MIN_VALUE) {
            frameTimeNanos
        } else {
            steadyNanos + (frameTimeNanos - lastFrameNanos).coerceIn(0L, maxStepNanos)
        }
        lastFrameNanos = frameTimeNanos
        return steadyNanos
    }
}

/**
 * Runs [block]'s animations on a [SteadyFrameClock]. A frame the main thread
 * delivers late — a whole page re-laying out as a column opens under the
 * same tap — moves them on by at most [maxStepNanos], so a short motion
 * still spans several drawn frames instead of landing between two of them
 * (a hard cut). On time, nothing changes: the springs are the same.
 */
internal suspend fun <R> withSteadyFrames(
    maxStepNanos: Long = STEADY_FRAME_MAX_STEP_NANOS,
    block: suspend CoroutineScope.() -> R
): R {
    val outer = currentCoroutineContext()[MonotonicFrameClock] ?: return coroutineScope(block)
    return withContext(SteadyFrameClock(outer, maxStepNanos), block)
}
