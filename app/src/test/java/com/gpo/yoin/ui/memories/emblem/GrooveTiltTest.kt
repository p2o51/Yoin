package com.gpo.yoin.ui.memories.emblem

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveTiltTest {
    @Test
    fun should_clamp_to_unit_disc_when_tilt_exceeds_18_degrees() {
        val filter = GrooveTiltFilter()
        filter.onSample(betaDeg = 10.0, gammaDeg = 0.0, nowMs = 0) // holding posture becomes the baseline
        val raw = filter.onSample(betaDeg = 10.0 + 25.0, gammaDeg = -30.0, nowMs = 16)
        assertTrue("raw tilt past 18° is beyond the disc: $raw", hypot(raw.x.toDouble(), raw.y.toDouble()) > 1)

        val goal = raw.capToUnitDisc()
        assertEquals(1.0, hypot(goal.x.toDouble(), goal.y.toDouble()), 1e-6)
        val v = grooveTiltVars(raw.x.toDouble(), raw.y.toDouble())
        assertEquals(1.0, v.tm, 1e-12)
        assertEquals(1.0, hypot(v.tx, v.ty), 1e-12)
        // gamma −30 (left edge down) leans the colour right; beta +25 (top edge up) leans it up
        assertTrue(v.tx > 0 && v.ty < 0)

        // a tilt under 18° stays inside, linearly
        val small = GrooveTiltFilter().run {
            onSample(0.0, 0.0, 0)
            onSample(0.0, -9.0, 16)
        }
        assertEquals(0.5, small.x.toDouble(), 0.01)
        assertEquals(0.5, grooveTiltVars(small.x.toDouble(), small.y.toDouble()).tm, 0.01)
    }

    @Test
    fun should_return_to_rest_when_baseline_catches_up() {
        val filter = GrooveTiltFilter()
        filter.onSample(0.0, 0.0, 0)
        var out = Offset.Zero
        var t = 0L
        // hold a new posture (12° left-right, 6° front-back) for 10 s at ~60 Hz
        while (t < 10_000) {
            t += 16
            out = filter.onSample(6.0, 12.0, t)
            if (t == 16L) assertTrue("tilted at first: $out", abs(out.x) > 0.6)
        }
        assertEquals(0.0, out.x.toDouble(), 0.01)
        assertEquals(0.0, out.y.toDouble(), 0.01)

        // the follow spring settles on rest and reports it
        val spring = GrooveTiltSpring()
        repeat(10) { spring.step(Offset(0.8f, -0.4f), 0.016) }
        assertTrue(abs(spring.x) > 0.1)
        var frames = 0
        while (!spring.step(Offset.Zero, 0.016)) {
            frames++
            assertTrue("spring must come to rest", frames < 600)
        }
        assertEquals(0.0, spring.x, 0.0)
        assertEquals(0.0, spring.y, 0.0)
        assertEquals(0.0, grooveTiltVars(spring.x, spring.y).tm, 0.0)
    }

    @Test
    fun should_rebase_when_samples_resume_after_a_gap() {
        val filter = GrooveTiltFilter()
        filter.onSample(0.0, 0.0, 0)
        filter.onSample(0.0, 9.0, 16)
        val resumed = filter.onSample(20.0, 20.0, 16 + GrooveTiltFilter.RebaseAfterMs + 1)
        assertEquals(0.0, resumed.x.toDouble(), 1e-9)
        assertEquals(0.0, resumed.y.toDouble(), 1e-9)
    }

    @Test
    fun should_match_prototype_tilt_vars() {
        GrooveGolden.load("groove-tilt.json").arr("cases").map { it.jsonObject }.forEach { c ->
            val v = grooveTiltVars(c.num("x"), c.num("y"))
            val label = "(${c.num("x")}, ${c.num("y")})"
            assertEquals("$label rot", c.num("rot"), v.rot, 1e-9)
            assertEquals("$label tx", c.num("tx"), v.tx, 1e-12)
            assertEquals("$label ty", c.num("ty"), v.ty, 1e-12)
            assertEquals("$label li", c.num("li"), v.li, 1e-12)
            assertEquals("$label ta", c.num("ta"), v.ta, 1e-9)
            assertEquals("$label tm", c.num("tm"), v.tm, 1e-12)
        }
    }
}
