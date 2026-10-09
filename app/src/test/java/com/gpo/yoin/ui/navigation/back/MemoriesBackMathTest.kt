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
    fun should_preview_the_diary_with_the_eased_pose_when_back_is_at_the_diary_level() {
        // The diary level previews the page's AOSP pose; p itself is not scrubbed.
        assertEquals(0f, MemoriesBackMath.diaryPreview(progress = 0f), 1e-6f)
        assertEquals(1f, MemoriesBackMath.diaryPreview(progress = 1f), 1e-6f)
        assertEquals(ease(0.3f), MemoriesBackMath.diaryPreview(progress = 0.3f), 1e-6f)
        // Every progress step moves the pose (no cap).
        var last = 0f
        for (step in 1..10) {
            val pose = MemoriesBackMath.diaryPreview(progress = step / 10f)
            assertTrue("progress ${step / 10f}", pose > last)
            last = pose
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
