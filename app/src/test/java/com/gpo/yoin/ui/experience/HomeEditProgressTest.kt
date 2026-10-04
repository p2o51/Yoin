package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.gpo.yoin.testutil.runFrameClockTest
import com.gpo.yoin.ui.home.edit.homeEditStageSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeEditProgressTest {

    private val spec = homeEditStageSpec(reduced = false)

    @Test
    fun should_snapSynchronously_when_snapTo() = runFrameClockTest { scope ->
        val progress = HomeEditProgress()
        progress.animateTo(scope, 1f, spec)
        advanceTimeBy(60)
        runCurrent()
        assertTrue(progress.value > 0f)
        assertTrue(progress.isAnimating)

        progress.snapTo(0f)
        // Same call, no frame needed.
        assertEquals(0f, progress.value, 0f)
        assertEquals(0f, progress.velocity, 0f)
        assertEquals(0f, progress.target, 0f)
        assertFalse(progress.isAnimating)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(0f, progress.value, 0f)
    }

    @Test
    fun should_keepVelocity_when_retargetedMidFlight() = runFrameClockTest { scope ->
        val progress = HomeEditProgress()
        progress.animateTo(scope, 1f, spec)
        advanceTimeBy(60)
        runCurrent()
        val value = progress.value
        val velocity = progress.velocity
        assertTrue(velocity > 1f)

        progress.animateTo(scope, 0f, spec)
        assertEquals(velocity, progress.velocity, 0f)
        assertEquals(0f, progress.target, 0f)
        // Two frames: the restart frame (play time 0), then one 16ms step.
        advanceTimeBy(33)
        runCurrent()
        val expected = TargetBasedAnimation(spec, Float.VectorConverter, value, 0f, velocity)
            .getValueFromNanos(16_000_000L)
        assertEquals(expected, progress.value, 1e-4f)
        // Still moving up, carried by the velocity it had.
        assertTrue(progress.value > value)
    }

    @Test
    fun should_landOnTarget_when_animationSettles() = runFrameClockTest { scope ->
        val progress = HomeEditProgress()
        progress.animateTo(scope, 1f, spec)
        val settled = async { progress.awaitSettled() }
        advanceUntilIdle()
        assertTrue(settled.isCompleted)
        assertEquals(1f, progress.value, 0f)
        assertEquals(0f, progress.velocity, 0f)
        assertFalse(progress.isAnimating)
    }

    @Test
    fun should_awaitTheRetarget_when_superseded() = runFrameClockTest { scope ->
        val progress = HomeEditProgress()
        progress.animateTo(scope, 1f, spec)
        val settled = async { progress.awaitSettled() }
        advanceTimeBy(60)
        runCurrent()
        progress.animateTo(scope, 0f, spec)
        runCurrent()
        assertFalse(settled.isCompleted)
        advanceUntilIdle()
        assertTrue(settled.isCompleted)
        assertEquals(0f, progress.value, 0f)
    }
}
