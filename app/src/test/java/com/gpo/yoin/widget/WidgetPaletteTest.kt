package com.gpo.yoin.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPaletteTest {
    @Test
    fun should_keepDarkStripTextReadable_when_coverIsMissing() {
        val palette = WidgetPalette.Fallback

        assertTrue("the old base ink sinks into the dark fallback", contrast(palette.base, palette.bgDark) < 3.0)
        assertTrue("score and small last-heard text stay readable", contrast(palette.inkDark, palette.bgDark) >= 4.5)
    }

    @Test
    fun should_keepDarkStripTextReadableAndInTheCoverHue_when_coverSeedVaries() {
        val seeds = listOf(
            0xFF000070, // deep blue: the former foreground had about 1.2:1 contrast
            0xFFFF0000,
            0xFF00FF00,
            0xFF0000FF,
            0xFF00FFFF,
            0xFFFF00FF,
            0xFFFFFF00,
            0xFFE8D9A0, // pale cover, held down into the light-page ink window
            0xFFFAFAFA,
            0xFF0A0A14,
        )

        seeds.forEach { seed ->
            val memory = MemoryPalette.fromBackdrop(Color(seed), Color.White)
            val palette = WidgetPalette(memory.base.toArgb(), memory.accent.toArgb(), memory.deep.toArgb(), memory.soft.toArgb())

            assertTrue("small text contrast for seed ${seed.toString(16)}", contrast(palette.inkDark, palette.bgDark) >= 4.5)
            val baseHue = hue(palette.base)
            val inkHue = hue(palette.inkDark)
            if (baseHue != null && inkHue != null) {
                val distance = abs(baseHue - inkHue)
                assertTrue("the text keeps the cover's hue", minOf(distance, 360f - distance) <= 5f)
            }
        }
    }

    @Test
    fun should_preserveExistingPaletteTones_when_androidGraphicsIsAbsent() {
        val palette = WidgetPalette.Fallback

        assertEquals(0xFF1B1627.toInt(), palette.bgDark)
        assertEquals(0xFF645D75.toInt(), palette.subLight)
        assertEquals(0xFFACA5B9.toInt(), palette.subDark)
        assertEquals(palette.soft, palette.bgLight)
        assertEquals(palette.deep, palette.inkLight)
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val a = Color(foreground).luminance().toDouble()
        val b = Color(background).luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** HSL hue only; an achromatic colour has no hue to preserve. */
    private fun hue(argb: Int): Float? {
        val r = ((argb ushr 16) and 0xFF).toFloat()
        val g = ((argb ushr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return null
        val sector = when (max) {
            r -> (g - b) / delta
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return (sector * 60f + 360f) % 360f
    }
}
