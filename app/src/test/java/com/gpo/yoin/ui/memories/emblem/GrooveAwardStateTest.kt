package com.gpo.yoin.ui.memories.emblem

import com.gpo.yoin.testutil.runFrameClockTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One emblem's award state across a flip of the user's reduced motion (QA B2): the state is kept and only
 * restyled, so a card still due its award keeps waiting and later plays the whole ceremony.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrooveAwardStateTest {

    private fun award(
        scope: CoroutineScope,
        reduced: Boolean,
        surface: GrooveSurface = GrooveSurface.Cover,
        size: Double = 96.0,
    ) = GrooveAwardState(GrooveSamples.M1, size, surface, reduced, haptics = null, scope = scope, tag = "test")

    @Test
    fun should_keep_card_waiting_when_reduced_motion_flips_before_its_award() = runFrameClockTest { scope ->
        val state = award(scope, reduced = true)
        state.setPending(true)
        assertEquals("reduced: the grooves wait undeveloped", 0f, state.cutAlpha(0), 0f)

        state.updateReducedMotion(false)

        assertFalse(state.reducedMotion)
        // still waiting, now as the ceremony's frame 0: the grooves are there, nothing cut, no tilt colour
        assertEquals(1f, state.cutAlpha(0), 0f)
        assertTrue(state.cutProgress(0) < 0.01f)
        assertEquals(0f, state.tintAlpha, 0f)
        assertFalse(state.isAnimating)
    }

    @Test
    fun should_play_whole_ceremony_when_card_waited_through_a_flip() = runFrameClockTest { scope ->
        val script = grooveScriptFor(GrooveSamples.M1, 96.0)
        assertNotNull(script)
        assertTrue(script!!.durationS > 1.0)
        val state = award(scope, reduced = true)
        state.setPending(true)
        state.updateReducedMotion(false)

        state.play()
        advanceTimeBy(600)
        runCurrent()
        assertTrue("a 200ms develop would be over; the ceremony is still cutting", state.isAnimating)

        advanceTimeBy(((script.durationS + 2.0) * 1000).toLong())
        runCurrent()
        assertFalse(state.isAnimating)
        assertEquals(1f, state.cutProgress(0), 1e-3f)
        assertEquals(1f, state.tintAlpha, 0f)
    }

    @Test
    fun should_restyle_nod_end_pose_when_flag_flips_mid_run() = runFrameClockTest { scope ->
        val state = award(scope, reduced = false, surface = GrooveSurface.Bar, size = 48.0)
        state.setPending(true)
        assertEquals(GrooveAwardState.BarWaitScale, state.emblemScale, 1e-3f)
        state.nod()
        advanceTimeBy(32)
        runCurrent()
        assertTrue(state.isAnimating)

        state.updateReducedMotion(true)
        // the running nod finishes in its own mode: no jump
        assertTrue(state.isAnimating)
        advanceTimeBy(3_000)
        runCurrent()

        assertFalse(state.isAnimating)
        // its end pose (full size, still uncut) is redrawn for reduced motion: undeveloped, not cut away
        assertEquals(1f, state.emblemScale, 1e-3f)
        assertEquals(0f, state.cutAlpha(0), 0f)
        assertEquals(1f, state.cutProgress(0), 0f)
    }

    @Test
    fun should_leave_finished_emblem_alone_when_flag_flips() = runFrameClockTest { scope ->
        val state = award(scope, reduced = false)
        state.setPending(true)
        state.reset()

        state.updateReducedMotion(true)

        assertEquals(1f, state.cutAlpha(0), 0f)
        assertEquals(1f, state.cutProgress(0), 0f)
        assertEquals(1f, state.tintAlpha, 0f)
        assertEquals(1f, state.labelAlpha, 0f)
    }
}
