package com.gpo.yoin.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Springs that must not skip.
 *
 * A spring advances by the time between the frames it is given. When the
 * main thread stalls — a feed composing in one frame, an account's source
 * going out to every screen — the next frame comes 150 ms to a second late,
 * and a spring on the real clock shows up there already settled: one frame
 * of the card, the next of Home with the card gone. A hard cut, however
 * good its spring.
 *
 * Paced, the stall only holds the spring where it was: after it, the
 * spring carries on from there, a frame's worth at a time, and every step of
 * its curve is drawn — the same YoinMotion spec, just never more than
 * [PACED_FRAME_MAX_STEP_NANOS] of it between two frames. For the transitions
 * that land on known busy frames (Home's account card, Home's feed through a
 * switch); everything else keeps the real clock.
 */

/**
 * Runs [block] on a frame clock that never moves more than
 * [PACED_FRAME_MAX_STEP_NANOS] between two frames: animations it starts (an
 * [Animatable], `withFrameNanos`) are held, not skipped, by a late frame.
 * Nested calls share the outer pacing.
 */
suspend fun <R> withPacedFrames(block: suspend CoroutineScope.() -> R): R {
    val clock = coroutineContext[MonotonicFrameClock]
    if (clock == null || clock is PacedFrameClock) return coroutineScope(block)
    return withContext(PacedFrameClock(clock), block)
}

/**
 * [targetValue] on [animationSpec], paced ([withPacedFrames]): the
 * `animateFloatAsState` of a value whose change lands on busy frames. It
 * starts at its first target, and a new target carries on from where the
 * last one left off, velocity and all.
 */
@Composable
fun animateFloatAsPacedState(targetValue: Float, animationSpec: AnimationSpec<Float>): State<Float> {
    val animatable = remember { Animatable(targetValue) }
    val spec by rememberUpdatedState(animationSpec)
    val target by rememberUpdatedState(targetValue)
    LaunchedEffect(animatable) {
        // Keep one paced timeline across redirects. Let animateTo interrupt
        // the old animation itself, as animateFloatAsState does: it captures
        // the running velocity before the previous animation is cancelled.
        // Cancelling this effect on each target would reset that velocity.
        withPacedFrames {
            snapshotFlow { target }.collect { next ->
                launch {
                    if (next != animatable.targetValue) animatable.animateTo(next, spec)
                }
            }
        }
    }
    return animatable.asState()
}

/**
 * [real]'s frames, with time that moves at most [maxStepNanos] from one
 * frame to the next. Every awaiter of one frame gets the same time.
 */
internal class PacedFrameClock(
    private val real: MonotonicFrameClock,
    private val maxStepNanos: Long = PACED_FRAME_MAX_STEP_NANOS
) : MonotonicFrameClock {
    private var lastReal = NO_FRAME
    private var lastPaced = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        real.withFrameNanos { frameTimeNanos -> onFrame(pace(frameTimeNanos)) }

    private fun pace(frameTimeNanos: Long): Long = synchronized(this) {
        // The same frame again (another awaiter of it) keeps its time.
        if (lastReal == NO_FRAME || frameTimeNanos > lastReal) {
            lastPaced = if (lastReal == NO_FRAME) {
                frameTimeNanos
            } else {
                lastPaced + (frameTimeNanos - lastReal).coerceAtMost(maxStepNanos)
            }
            lastReal = frameTimeNanos
        }
        lastPaced
    }

    private companion object {
        const val NO_FRAME = Long.MIN_VALUE
    }
}

/**
 * The most a paced spring moves between two frames: two frames at 60 Hz.
 * A frame or two dropped still reads at speed; a longer stall holds it.
 */
const val PACED_FRAME_MAX_STEP_NANOS = 33_333_333L
