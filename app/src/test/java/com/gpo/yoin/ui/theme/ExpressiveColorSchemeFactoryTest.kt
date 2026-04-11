package com.gpo.yoin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import contrast.Contrast
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveColorSchemeFactoryTest {
    @Test
    fun should_keep_primary_near_seed_hue_and_rotate_accents_for_red_seed() {
        val seedArgb = Hct.from(0.0, 64.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        assertHueNear(hueOf(colors.primary), hueOf(seedArgb), tolerance = 8.0)
        assertHueInRange(hueOf(colors.secondary), 190.0, 210.0)
        assertHueInRange(hueOf(colors.tertiary), 185.0, 205.0)
    }

    @Test
    fun should_keep_primary_near_seed_hue_and_rotate_accents_for_blue_seed() {
        val seedArgb = Hct.from(240.0, 64.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        assertHueNear(hueOf(colors.primary), hueOf(seedArgb), tolerance = 8.0)
        assertHueInRange(hueOf(colors.secondary), 328.0, 346.0)
        assertHueInRange(hueOf(colors.tertiary), 333.0, 350.0)
    }

    @Test
    fun should_support_light_mode_with_seed_driven_expressive_scheme() {
        val seedArgb = Hct.from(24.0, 64.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(
            seedArgb = seedArgb,
            isDark = false,
        )

        assertHueNear(hueOf(colors.primary), hueOf(seedArgb), tolerance = 8.0)
        assertTrue("Expected a light surface tone", toneOf(colors.surface) >= 95.0)
        assertTrue(contrastRatio(colors.onSurface, colors.surface) >= 4.5)
    }

    @Test
    fun should_keep_yellow_surfaces_deep_and_primary_distinct_in_dark_mode() {
        val seedArgb = 0xFFFFFF00.toInt()
        assertTrue(Hct.isYellow(hueOf(seedArgb)))
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        val surfaceTone = toneOf(colors.surface)
        assertTrue("Expected surface tone <= 5.0 but was $surfaceTone", surfaceTone <= 5.0)
        assertHueNear(hueOf(colors.primary), hueOf(seedArgb), tolerance = 12.0)
        assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 6.0)
    }

    @Test
    fun should_cap_cyan_primary_tone_in_dark_mode() {
        val seedArgb = 0xFF00FFFF.toInt()
        assertTrue(Hct.isCyan(hueOf(seedArgb)))
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        assertHueNear(hueOf(colors.primary), hueOf(seedArgb), tolerance = 12.0)
        assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 6.0)
    }

    @Test
    fun should_preserve_visible_primary_chroma_for_low_chroma_seed() {
        val seedArgb = Hct.from(240.0, 4.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        assertTrue(Hct.fromInt(colors.primary.toArgb()).chroma >= 18.0)
    }

    @Test
    fun should_follow_2025_dark_surface_hierarchy() {
        val seedArgb = Hct.from(0.0, 64.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        val surface = toneOf(colors.surface)
        val low = toneOf(colors.surfaceContainerLow)
        val container = toneOf(colors.surfaceContainer)
        val high = toneOf(colors.surfaceContainerHigh)
        val highest = toneOf(colors.surfaceContainerHighest)

        assertTrue(surface < low)
        assertTrue(low < container)
        assertTrue(container < high)
        assertTrue(high < highest)

        assertEquals(4.0, surface, 1.0)
        assertEquals(6.0, low, 1.0)
        assertEquals(9.0, container, 1.0)
        assertEquals(12.0, high, 1.0)
        assertEquals(15.0, highest, 1.0)
    }

    @Test
    fun should_meet_baseline_contrast_targets_for_key_pairs() {
        val seedArgb = Hct.from(0.0, 64.0, 50.0).toInt()
        val colors = ExpressiveColorSchemeFactory.fromSeed(seedArgb)

        assertTrue(contrastRatio(colors.onSurface, colors.surface) >= 10.9)
        assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 5.9)
        assertTrue(contrastRatio(colors.onSecondaryContainer, colors.secondaryContainer) >= 5.9)
    }

    private fun hueOf(color: Color): Double = Hct.fromInt(color.toArgb()).hue

    private fun hueOf(argb: Int): Double = Hct.fromInt(argb).hue

    private fun toneOf(color: Color): Double = Hct.fromInt(color.toArgb()).tone

    private fun contrastRatio(foreground: Color, background: Color): Double {
        return Contrast.ratioOfTones(toneOf(foreground), toneOf(background))
    }

    private fun assertHueNear(actual: Double, expected: Double, tolerance: Double) {
        val delta = circularHueDistance(actual, expected)
        assertTrue(
            "Expected hue $actual to be within $tolerance of $expected (delta=$delta)",
            delta <= tolerance,
        )
    }

    private fun assertHueInRange(actual: Double, min: Double, max: Double) {
        assertTrue("Expected hue $actual to be in [$min, $max]", actual in min..max)
    }

    private fun circularHueDistance(a: Double, b: Double): Double {
        val diff = kotlin.math.abs(a - b) % 360.0
        return minOf(diff, 360.0 - diff)
    }
}
