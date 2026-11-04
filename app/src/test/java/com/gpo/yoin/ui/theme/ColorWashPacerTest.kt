package com.gpo.yoin.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet's three-pane window took ~115 ms per colour-wash step, so twelve
 * steps blocked the main thread for the whole wash. Heavy steps must leave
 * room for smooth frames; light windows must step as before.
 */
class ColorWashPacerTest {

    @Test
    fun should_stepEveryFrame_when_stepFramesFitTheBudget() {
        val pacer = ColorWashPacer()
        val frames = List(10) { ms(16L * it) }

        val allowed = frames.map { now -> pacer.onFrame(now).also { if (it) pacer.onStep(now) } }

        assertEquals(List(10) { true }, allowed)
    }

    @Test
    fun should_holdNextStep_forTwiceTheCost_when_aStepFrameIsHeavy() {
        val pacer = ColorWashPacer()
        assertTrue(pacer.onFrame(ms(0)))
        pacer.onStep(ms(0))

        // The step's frame took 115 ms: hold until 115 + 2 × 115 = 345 ms.
        assertFalse(pacer.onFrame(ms(115)))
        assertFalse(pacer.onFrame(ms(131)))
        assertFalse(pacer.onFrame(ms(344)))
        assertTrue(pacer.onFrame(ms(345)))
    }

    @Test
    fun should_notPenalise_framesWithoutAStep() {
        val pacer = ColorWashPacer()
        assertTrue(pacer.onFrame(ms(0)))
        // No step went out, so a slow frame (someone else's work) holds nothing back.
        assertTrue(pacer.onFrame(ms(200)))
    }

    @Test
    fun should_keepAHeavyWindowToAFewSteps_overATypicalWash() {
        val pacer = ColorWashPacer()
        var now = 0L
        var steps = 0
        // 720 ms wash; a frame with a step costs 115 ms, any other frame 16 ms.
        while (now < ms(720)) {
            val step = pacer.onFrame(now)
            if (step) {
                pacer.onStep(now)
                steps++
            }
            now += if (step) ms(115) else ms(16)
        }
        assertTrue("steps=$steps", steps <= 3)
    }

    private fun ms(value: Long) = value * 1_000_000L
}
