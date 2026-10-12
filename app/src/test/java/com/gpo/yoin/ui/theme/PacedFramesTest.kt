package com.gpo.yoin.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Springs that must not skip (QA P9a-2 / P9b-1 / P9b-2: the account card's
 * close and Home's feed fade showed up settled after a 150 ms – 1 s frame):
 * on paced frames a late frame moves them at most a step, and they carry on
 * from there, on the same YoinMotion spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class PacedFramesTest {

    /** Frames at the given times (ns), one per awaiter round. */
    private class FramesAt(times: List<Long>) : MonotonicFrameClock {
        private val left = ArrayDeque(times)

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            onFrame(left.removeFirstOrNull() ?: error("no frames left"))
    }

    @Test
    fun should_moveAtMostAStep_when_aFrameIsLate() = runTest {
        val clock = PacedFrameClock(FramesAt(listOf(ms(1_000), ms(1_016), ms(1_316), ms(1_332))))

        val times = List(4) { clock.withFrameNanos { it } }

        val step = PACED_FRAME_MAX_STEP_NANOS
        assertEquals(listOf(ms(1_000), ms(1_016), ms(1_016) + step, ms(1_032) + step), times)
    }

    @Test
    fun should_keepFramesAsTheyAre_when_theyComeOnTime() = runTest {
        val onTime = List(8) { ms(16L * it) }
        val clock = PacedFrameClock(FramesAt(onTime))

        assertEquals(onTime, List(onTime.size) { clock.withFrameNanos { it } })
    }

    @Test
    fun should_giveOneTime_when_aFrameIsAwaitedTwice() = runTest {
        val clock = PacedFrameClock(FramesAt(listOf(ms(5), ms(5), ms(21))))

        assertEquals(listOf(ms(5), ms(5), ms(21)), List(3) { clock.withFrameNanos { it } })
    }

    @Test
    fun should_notShowASpringSettled_when_theFrameAfterItsStartIsLate() = runTest {
        // Two frames on time, then the main thread stalls for 600 ms.
        val frames = listOf(0L, 16L) + (616L..5_000L step 16L)

        val real = valuesOn(FramesAt(frames.map(::ms)))
        val paced = valuesOn(PacedFrameClock(FramesAt(frames.map(::ms))))

        // On the real clock the late frame already shows it (nearly) home: a cut.
        assertTrue("real clock at the late frame: ${real[2]}", real[2] > SETTLED)
        // Paced, it is a step further on, and goes on from there.
        assertTrue("paced at the late frame: ${paced[2]}", paced[2] < HALF_WAY)
        assertTrue("paced, then: ${paced.drop(2).take(4)}", paced[3] > paced[2] && paced[4] > paced[3])
        assertEquals(1f, paced.last(), 0f)
    }

    @Test
    fun should_paceOnce_when_nested() = runTest {
        val clock = FramesAt(listOf(ms(0), ms(16), ms(616)))

        val times = withContext(clock) {
            withPacedFrames {
                withPacedFrames { List(3) { frame() } }
            }
        }

        assertEquals(listOf(ms(0), ms(16), ms(16) + PACED_FRAME_MAX_STEP_NANOS), times)
    }

    private suspend fun frame(): Long = withFrameNanos { it }

    /** The spatial spring from 0 to 1 on [clock], frame by frame. */
    private suspend fun valuesOn(clock: MonotonicFrameClock): List<Float> {
        val values = mutableListOf<Float>()
        val animatable = Animatable(0f)
        withContext(clock) {
            animatable.animateTo(1f, spatial) { values += value }
        }
        return values
    }

    private val spatial = YoinMotion.defaultSpatialSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive()
    )

    private fun ms(millis: Long) = millis * 1_000_000L

    private companion object {
        const val SETTLED = 0.95f
        const val HALF_WAY = 0.5f
    }
}

/** The composable must retain the running spring's velocity when a switch quickly reverses. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class PacedStateRedirectTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_matchSpringRedirect_when_targetReversesBeforeTheFeedFadeSettles() {
        var target by mutableFloatStateOf(0f)
        lateinit var paced: State<Float>
        lateinit var reference: State<Float>
        val spatial = YoinMotion.defaultSpatialSpec<Float>(
            role = YoinMotionRole.Standard,
            expressiveScheme = MotionScheme.expressive()
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            paced = animateFloatAsPacedState(target, spatial)
            reference = animateFloatAsState(target, spatial)
        }
        rule.runOnIdle { target = 1f }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        val turn = rule.runOnIdle { paced.value }
        assertTrue("reverse a moving spring, not one at rest ($turn)", turn > 0.05f && turn < 0.8f)

        // A failed account switch restores Content while Loading's departure
        // is still moving. On punctual frames pacing changes nothing: every
        // redirected position must match the ordinary spring, including its
        // remaining forward momentum before it turns back.
        rule.runOnIdle { target = 0f }
        val differences = mutableListOf<Float>()
        repeat(20) {
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { differences += abs(paced.value - reference.value) }
        }
        assertTrue("redirect lost velocity: $differences", differences.max() < 0.001f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0f, paced.value, 0f)
    }
}
