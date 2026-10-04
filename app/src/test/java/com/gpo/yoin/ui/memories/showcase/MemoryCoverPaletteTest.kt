package com.gpo.yoin.ui.memories.showcase

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Memories reads a cover's colour the way Now Playing does (owner, 2026-10-05: a blue cover read earth-yellow in
 * Memories). The cover here is the QA server's "Blue Hour Sessions": a blue gradient with a tan ring and dot,
 * the tan sitting in Palette's skin-tone band that the default filter drops.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryCoverPaletteTest {

    private fun hsl(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }

    private fun hueDistance(a: Color, b: Color): Float {
        val d = abs(hsl(a)[0] - hsl(b)[0]) % 360f
        return minOf(d, 360f - d)
    }

    /** hls(h, l, s) as Python's colorsys builds it, to 8-bit ARGB. */
    private fun hls(h: Float, l: Float, s: Float): Int = ColorUtils.HSLToColor(floatArrayOf(h * 360f, s, l))

    private fun blueCoverWithTanRing(size: Int = CoverSeedExtractor.BitmapSizePx): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until size) {
            val t = y / size.toFloat()
            paint.color = hls((0.60f + 0.08f * t) % 1f, 0.25f + 0.35f * t, 0.65f)
            canvas.drawLine(0f, y.toFloat(), size.toFloat(), y.toFloat(), paint)
        }
        val tan = hls(0.10f, 0.7f, 0.6f)
        val c = size / 2f
        val r = size / 3f
        paint.color = tan
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, size / 40f)
        canvas.drawCircle(c, c, r, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(c, c, r / 4f, paint)
        return bitmap
    }

    @Test
    fun should_read_the_same_seed_as_now_playing_when_cover_has_a_tan_ring() = runTest {
        val cover = blueCoverWithTanRing()
        val nowPlaying = CoverSeedExtractor.extractSeedArgb(cover)
        val memories = memoryCoverColors(CoverSeedExtractor.palette(cover))

        assertNotNull(nowPlaying)
        assertNotNull(memories)
        // one cover, one colour: Memories' seed is the playback theme's seed
        assertEquals(nowPlaying, memories!!.seed.toArgb())
        val seedHue = hsl(memories.seed)[0]
        assertTrue("seed hue $seedHue is blue", seedHue in 200f..260f)
    }

    @Test
    fun should_keep_accent_in_the_seed_family_when_cover_has_a_tan_ring() {
        val memories = memoryCoverColors(CoverSeedExtractor.palette(blueCoverWithTanRing()))!!
        // the tan ring (hue ~36°) never becomes the accent: it would tint the emblem's rim and the aurora
        assertTrue(hueDistance(memories.seed, memories.accent) <= MemoryAccentHueWindow)
        val palette = MemoryPalette.fromBackdrop(memories.seed, memories.accent)
        val baseHue = hsl(palette.base)[0]
        assertTrue("base hue $baseHue is blue", baseHue in 200f..260f)
        val accentHue = hsl(palette.accent)[0]
        assertTrue("accent hue $accentHue is not earth-yellow", accentHue !in 20f..70f)
    }

    @Test
    fun should_pick_a_light_swatch_of_the_seed_family_for_the_accent() {
        val blue = Color(0xFF183888)
        val tan = Color(0xFFE0B880)
        val lavender = Color(0xFF8888E0)
        // the tan comes first but sits ~190° away: the lavender is the accent
        assertEquals(lavender.toArgb(), memoryAccentFor(blue, listOf(tan, lavender)).toArgb())
    }

    @Test
    fun should_lift_the_seed_when_no_swatch_is_in_its_family() {
        val blue = Color(0xFF183888)
        val accent = memoryAccentFor(blue, listOf(Color(0xFFE0B880)))
        assertTrue(hueDistance(blue, accent) <= MemoryAccentHueWindow)
        val lightness = hsl(accent)[2]
        assertTrue(lightness >= MemoryAccentMinLightness.toFloat() - 0.01f)
        assertTrue(lightness <= MemoryAccentMaxLightness.toFloat() + 0.01f)
    }

    @Test
    fun should_accept_any_swatch_when_the_seed_is_grey() {
        val grey = Color(0xFF6E6E6E)
        val gold = Color(0xFFE2C27A)
        assertEquals(gold.toArgb(), memoryAccentFor(grey, listOf(gold)).toArgb())
    }
}
