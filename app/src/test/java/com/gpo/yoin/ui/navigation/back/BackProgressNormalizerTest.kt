package com.gpo.yoin.ui.navigation.back

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackProgressNormalizerTest {

    @Test
    fun should_return_zero_for_zero_progress() {
        assertEquals(0f, normalizeBackProgress(0f), 0.0001f)
    }

    @Test
    fun should_return_one_for_full_progress() {
        assertEquals(1f, normalizeBackProgress(1f), 0.0001f)
    }

    @Test
    fun should_apply_existing_curve_shape_for_mid_progress() {
        val rawProgress = 0.5f
        val expected = rawProgress.pow(BackMotionTokens.PreviewCurveExponent)

        assertEquals(expected, normalizeBackProgress(rawProgress), 0.0001f)
    }

    @Test
    fun should_increase_monotonically_across_progress_range() {
        val low = normalizeBackProgress(0.25f)
        val mid = normalizeBackProgress(0.5f)
        val high = normalizeBackProgress(0.75f)

        assertTrue(low in 0f..1f)
        assertTrue(mid in 0f..1f)
        assertTrue(high in 0f..1f)
        assertTrue(low < mid)
        assertTrue(mid < high)
    }
}
