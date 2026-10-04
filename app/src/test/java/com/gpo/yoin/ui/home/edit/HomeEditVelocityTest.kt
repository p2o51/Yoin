package com.gpo.yoin.ui.home.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeEditVelocityTest {

    private fun tracker() = HomeEditVelocity(maxVelocity = 8000f)

    @Test
    fun should_return0_when_lastSampleStale() {
        val velocity = tracker()
        velocity.add(0L, 0f)
        velocity.add(50L, 100f)
        assertEquals(2000f, velocity.velocity(nowMs = 60L), .01f)
        // The finger rested 61ms before lifting: no fling replay.
        assertEquals(0f, velocity.velocity(nowMs = 111L), 0f)
    }

    @Test
    fun should_clampTo8000_when_1600dpIn100ms() {
        val velocity = tracker()
        velocity.add(0L, 0f)
        velocity.add(100L, 1600f)
        assertEquals(8000f, velocity.velocity(nowMs = 100L), 0f)
        velocity.reset()
        velocity.add(0L, 1600f)
        velocity.add(100L, 0f)
        assertEquals(-8000f, velocity.velocity(nowMs = 100L), 0f)
    }

    @Test
    fun should_measureLast100ms_when_samplesSpanLonger() {
        val velocity = tracker()
        velocity.add(0L, 0f)
        velocity.add(100L, 500f)
        velocity.add(150L, 500f)
        velocity.add(200L, 600f)
        // The window keeps 100..200: 100px over 100ms.
        assertEquals(1000f, velocity.velocity(nowMs = 200L), .01f)
    }

    @Test
    fun should_return0_when_tooFewOrTooCloseSamples() {
        val velocity = tracker()
        assertEquals(0f, velocity.velocity(nowMs = 0L), 0f)
        velocity.add(0L, 0f)
        assertEquals(0f, velocity.velocity(nowMs = 0L), 0f)
        velocity.add(4L, 40f)
        assertEquals(0f, velocity.velocity(nowMs = 4L), 0f)
    }
}
