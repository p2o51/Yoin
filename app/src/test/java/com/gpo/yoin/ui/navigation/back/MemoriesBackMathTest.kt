package com.gpo.yoin.ui.navigation.back

import com.gpo.yoin.ui.theme.YoinMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesBackMathTest {

    /** 112dp on a 915dp-tall phone, the prototype's reference screen (QB ≈ 0.121). */
    private val trigger = 112f / 915f

    private fun ease(progress: Float) = YoinMotion.backGestureEasing.transform(progress)

    @Test
    fun should_map_full_back_progress_to_dismiss_trigger_travel() {
        assertEquals(0f, MemoriesBackMath.cardFraction(q0 = 0f, triggerFraction = trigger, progress = 0f), 1e-6f)
        assertEquals(trigger, MemoriesBackMath.cardFraction(q0 = 0f, triggerFraction = trigger, progress = 1f), 1e-6f)
        // Eased, not linear: the M3 back curve is eager early.
        val atThreeTenths = MemoriesBackMath.cardFraction(q0 = 0f, triggerFraction = trigger, progress = 0.3f)
        assertEquals(trigger * ease(0.3f), atThreeTenths, 1e-6f)
        assertTrue(atThreeTenths > trigger * 0.3f)
        // Full progress parks the page exactly at the finger's commit distance.
        assertEquals(112f, MemoriesBackMath.cardFraction(0f, trigger, 1f) * 915f, 0.01f)
    }

    @Test
    fun should_start_card_preview_from_current_fraction() {
        // A return spring caught mid-flight: the scrub starts where the page is.
        val q0 = 0.05f
        assertEquals(q0, MemoriesBackMath.cardFraction(q0, trigger, progress = 0f), 1e-6f)
        assertEquals(trigger, MemoriesBackMath.cardFraction(q0, trigger, progress = 1f), 1e-6f)
        val mid = MemoriesBackMath.cardFraction(q0, trigger, progress = 0.5f)
        assertEquals(q0 + (trigger - q0) * ease(0.5f), mid, 1e-6f)
        // Already past the trigger pose (a spring mid-flight): held, never pulled back down.
        assertEquals(0.4f, MemoriesBackMath.cardFraction(0.4f, trigger, progress = 0.7f), 1e-6f)
    }

    @Test
    fun should_scrub_diary_from_p0_when_back_starts_mid_spring() {
        // An open spring caught at 0.8: the scrub starts there, not at 1.
        val p0 = 0.8f
        assertEquals(p0, MemoriesBackMath.diaryFraction(p0, progress = 0f), 1e-6f)
        assertEquals(0f, MemoriesBackMath.diaryFraction(p0, progress = 1f), 1e-6f)
        assertEquals(p0 * (1f - ease(0.3f)), MemoriesBackMath.diaryFraction(p0, progress = 0.3f), 1e-6f)
        // Full range, no cap: every progress step moves p.
        var last = p0
        for (step in 1..10) {
            val p = MemoriesBackMath.diaryFraction(p0, progress = step / 10f)
            assertTrue("progress ${step / 10f}", p < last)
            last = p
        }
    }

    @Test
    fun should_give_full_corners_when_q_reaches_the_driving_threshold() {
        val height = 915f
        val max = 28f
        assertEquals(0f, MemoriesBackMath.cornerRadius(0f, 112f, height, max))
        assertEquals(max, MemoriesBackMath.cornerRadius(112f / height, 112f, height, max), 1e-4f)
        // The bar's 56dp rule rounds twice as early.
        assertEquals(max, MemoriesBackMath.cornerRadius(56f / height, 56f, height, max), 1e-4f)
        assertTrue(MemoriesBackMath.cornerRadius(56f / height, 112f, height, max) < max)
        // Smoothstep: half the threshold is half the radius.
        assertEquals(max / 2f, MemoriesBackMath.cornerRadius(56f / height, 112f, height, max), 1e-4f)
    }
}
