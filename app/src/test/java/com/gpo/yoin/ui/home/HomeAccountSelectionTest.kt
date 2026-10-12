package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picked row's selection on Home's account card (QA ACC-22 / P9b-1): the
 * tap's frames are the busy ones, and the selection — the segment's colour,
 * its corners, the other rows' dim — was drawn on the real clock, so the
 * frame after a 150–600 ms stall showed it already done (a one-frame pop),
 * while the close waited on a separate beat. On the card's own paced springs
 * a late frame moves it a step, and it goes on from there.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3ExpressiveApi::class)
class HomeAccountSelectionTest {

    /** Frames on demand: every awaiter of a frame gets its time. */
    private class SteppedFrames : MonotonicFrameClock {
        private val awaiters = mutableListOf<(Long) -> Unit>()

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            suspendCancellableCoroutine { continuation ->
                awaiters += { time -> continuation.resume(onFrame(time)) }
            }

        fun frame(timeNanos: Long) {
            val now = awaiters.toList()
            awaiters.clear()
            now.forEach { it(timeNanos) }
        }
    }

    /** One frame of the card: the pick's colour and corners, and another row's alpha. */
    private data class Look(val colour: Float, val corners: Float, val otherAlpha: Float)

    private val effects = YoinMotion.defaultEffectsSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive()
    )
    private val spatial = YoinMotion.defaultSpatialSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive()
    )

    @Test
    fun should_holdThePickPartWay_when_theFrameAfterTheTapIsLate() = runTest {
        val selection = HomeAccountSelection()

        val looks = framesOf(selection, target = "b", times = STALLED)
        // The same effects spring on the real clock, on the same frames.
        val real = realClock(STALLED)

        // On the real clock, the late frame already shows it done: the pop.
        assertTrue("real clock at the late frame: ${real[LATE]}", real[LATE] > SETTLED)
        // On the card's springs it is a step on: part-way, short of the beat.
        val late = looks[LATE]
        assertTrue("colour at the late frame: $late", late.colour > looks[LATE - 1].colour)
        assertTrue("colour at the late frame: $late", late.colour < SWITCH_SELECTION_BEAT)
        assertTrue("corners at the late frame: $late", late.corners > 0f && late.corners < HALF_WAY)
        assertTrue("other row at the late frame: $late", late.otherAlpha < 1f && late.otherAlpha > CLEARLY_UNDIMMED)
        // …and it goes on from there, a frame at a time, to selected.
        val after = looks.drop(LATE).take(4).map { it.colour }
        assertEquals("then: $after", after.sorted(), after)
        assertTrue("then: $after", after.zipWithNext().all { (a, b) -> b > a })
        assertEquals(Look(1f, 1f, ACCOUNT_ROW_DIMMED_ALPHA), looks.last())
    }

    @Test
    fun should_easeThePickBackOnItsRow_when_theSwitchIsOver() = runTest {
        val selection = HomeAccountSelection()
        framesOf(selection, target = "b", times = ON_TIME)

        val looks = framesOf(selection, target = null, times = ON_TIME.map { it + ON_TIME.last() + FRAME })

        // The row it picked eases back (still that row's), the others come back up.
        assertEquals("b", selection.row)
        assertTrue("on its way back: ${looks[2]}", looks[2].colour in 0f..1f && looks[2].colour < 1f)
        assertTrue("on its way back: ${looks[2]}", looks[2].otherAlpha > ACCOUNT_ROW_DIMMED_ALPHA)
        assertEquals(Look(0f, 0f, 1f), looks.last())
    }

    @Test
    fun should_startFromAPlainRow_when_anotherAccountIsPicked() = runTest {
        val selection = HomeAccountSelection()
        framesOf(selection, target = "b", times = ON_TIME)
        framesOf(selection, target = null, times = ON_TIME.map { it + ON_TIME.last() + FRAME })

        val frames = SteppedFrames()
        val job = launch(frames) { selection.follow("c", effects, spatial) }
        runCurrent()

        // The new pick, from a plain row; the last one isn't selected any more.
        assertEquals("c", selection.row)
        assertEquals(0f, selection.colourOf("c"), 0f)
        assertEquals(0f, selection.colourOf("b"), 0f)
        assertEquals(1f, selection.alphaOf("c"), 0f)
        job.cancel()
    }

    /** [selection] following [target] on frames at [times] (ms), and how each frame looks. */
    private fun TestScope.framesOf(selection: HomeAccountSelection, target: String?, times: List<Long>): List<Look> {
        val frames = SteppedFrames()
        val job = launch(frames) { selection.follow(target, effects, spatial) }
        val looks = times.map { time ->
            runCurrent()
            frames.frame(ms(time))
            runCurrent()
            Look(selection.colourOf(OTHER_OR_PICK), selection.cornersOf(OTHER_OR_PICK), selection.alphaOf("c"))
        }
        assertTrue("settled within the frames", job.isCompleted)
        return looks
    }

    /** A plain Animatable on the effects spring, on the real clock: what the segment's own spring drew. */
    private fun TestScope.realClock(times: List<Long>): List<Float> {
        val frames = SteppedFrames()
        val animatable = Animatable(0f)
        launch(frames) { animatable.animateTo(1f, effects) }
        return times.map { time ->
            runCurrent()
            frames.frame(ms(time))
            runCurrent()
            animatable.value
        }
    }

    private fun ms(millis: Long) = millis * 1_000_000L

    private companion object {
        // The pick: "b". The look of another row is read from "c".
        const val OTHER_OR_PICK = "b"
        const val FRAME = 16L

        // Two frames on time after the tap, then the main thread stalls for 600 ms.
        val STALLED = listOf(0L, 16L) + (616L..3_000L step FRAME)
        const val LATE = 2
        val ON_TIME = (0L..3_000L step FRAME).toList()

        const val SETTLED = 0.99f
        const val HALF_WAY = 0.5f

        // Still well short of dimmed.
        const val CLEARLY_UNDIMMED = ACCOUNT_ROW_DIMMED_ALPHA + 0.1f
    }
}
