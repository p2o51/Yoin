package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full-window player's corners grow with its travel (owner 2026-11-25: 往下拉的时候不要漏破绽): square at
 * rest, the screen's radius once it has travelled the ramp, and never a step in between — the first pixels
 * of a drag, a cancelled drag springing home and the end of a landing all change the radius a little.
 */
class PlayerCornerRadiusTest {

    private val ramp = 28f * 2.625f
    private val full = 48f * 2.625f

    private fun radius(travel: Float) = playerCornerRadiusPx(travel, ramp, full)

    @Test
    fun should_beSquare_when_thePlayerIsAtRest() {
        assertEquals(0f, radius(0f), 0f)
        // A pixel of settle is not a corner: the resting player stays unclipped.
        assertEquals(0f, radius(1f), 0f)
    }

    @Test
    fun should_takeTheScreensRadius_when_itHasTravelledTheRamp() {
        assertEquals(full, radius(ramp), 0.001f)
        assertEquals(full, radius(ramp * 20f), 0.001f)
    }

    @Test
    fun should_growWithoutAStep_when_dragged() {
        var previous = 0f
        var travel = 0f
        while (travel <= ramp * 1.5f) {
            val r = radius(travel)
            assertTrue("never shrinks as it travels: $travel", r >= previous)
            // At most a few px of radius per px of travel: no pop at the start or the end.
            assertTrue("step at $travel: ${r - previous}", r - previous <= full * 0.05f)
            previous = r
            travel += 1f
        }
    }

    @Test
    fun should_beOnItsWay_when_halfwayUpTheRamp() {
        val half = radius(ramp / 2f)
        assertTrue("halfway: $half", half > 0.25f * full && half < 0.9f * full)
    }
}
