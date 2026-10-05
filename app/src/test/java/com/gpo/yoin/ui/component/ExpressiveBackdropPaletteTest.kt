package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Home's backdrop colours read a cover the way Now Playing does (owner, 2026-10-05: a blue cover read
 * ochre / khaki where Now Playing was right). The covers are synthetic: a navy sleeve with a face on it,
 * the QA server's violet sleeve with a saturated peach block, and an all-sepia sleeve.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveBackdropPaletteTest {

    private fun hsl(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }

    private fun hue(color: Color): Float = hsl(color)[0]

    private fun hueDistance(a: Color, b: Color): Float {
        val d = abs(hue(a) - hue(b)) % 360f
        return minOf(d, 360f - d)
    }

    private fun hslArgb(hueDegrees: Float, saturation: Float, lightness: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(hueDegrees, saturation, lightness))

    private fun cover(size: Int = CoverSeedExtractor.BitmapSizePx, pixel: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(size * size) { index -> pixel(index % size, index / size) }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    /** A dark navy sleeve (too dark to be "vibrant") with a lit face filling its middle. */
    private fun navyCoverWithFace(size: Int = CoverSeedExtractor.BitmapSizePx): Bitmap = cover(size) { x, y ->
        val t = y / size.toFloat()
        val dx = (x - size * 0.5f) / (size * 0.26f)
        val dy = (y - size * 0.48f) / (size * 0.34f)
        if (dx * dx + dy * dy <= 1f) {
            // skin: hue ~25°, mid saturation, lit from the top
            hslArgb(22f + 6f * t, 0.45f, 0.66f - 0.10f * t)
        } else {
            hslArgb(218f + 8f * t, 0.55f, 0.10f + 0.14f * t)
        }
    }

    /** The night-QA server's sleeve: a violet-blue gradient with a fully saturated peach block across it. */
    private fun violetCoverWithPeachBlock(size: Int = CoverSeedExtractor.BitmapSizePx): Bitmap = cover(size) { x, y ->
        val t = y / size.toFloat()
        val c = size / 2
        val r = size / 3
        if (x in (c - r)..(c + r) && y in (c - r / 3)..(c + r / 3)) {
            android.graphics.Color.rgb(255, 196, 120)
        } else {
            hslArgb((0.70f + 0.10f * t) * 360f % 360f, 0.70f, 0.30f + 0.30f * t)
        }
    }

    /** A sepia photograph: every pixel in Palette's skin-tone band, so the filtered pass keeps nothing. */
    private fun sepiaCover(size: Int = CoverSeedExtractor.BitmapSizePx): Bitmap = cover(size) { x, y ->
        hslArgb(20f + 4f * (x / size.toFloat()), 0.42f, 0.30f + 0.45f * (y / size.toFloat()))
    }

    @Test
    fun should_derive_a_blue_family_base_when_a_blue_cover_has_skin_tone_accents() {
        val cover = navyCoverWithFace()
        // the cover reproduces the bug: the old unfiltered 12-colour read made the face the base
        val old = Palette.from(cover).maximumColorCount(12).clearFilters().generate().vibrantSwatch
        assertNotNull(old)
        assertTrue("old base hue ${hue(Color(old!!.rgb))} is skin", hue(Color(old.rgb)) in 10f..40f)

        val colors = extractBackdropColors(cover)!!
        assertTrue(colors.isResolvedFromPalette)
        val baseHue = hue(colors.baseColor)
        assertTrue("base hue $baseHue is blue", baseHue in 200f..250f)
        assertTrue(
            "accent hue ${hue(colors.accentColor)} stays in the base's family",
            hueDistance(colors.baseColor, colors.accentColor) <= BackdropAccentHueWindow,
        )
    }

    @Test
    fun should_tone_now_playings_seed_when_the_cover_has_one() = runTest {
        for (cover in listOf(navyCoverWithFace(), violetCoverWithPeachBlock())) {
            val seed = CoverSeedExtractor.extractSeedArgb(cover)
            assertNotNull(seed)
            // one cover, one colour: the backdrop base is the playback theme's seed, toned
            assertEquals(toneBackdropBase(Color(seed!!)).toArgb(), extractBackdropColors(cover)!!.baseColor.toArgb())
        }
    }

    @Test
    fun should_keep_the_accent_in_the_base_family_when_a_saturated_peach_block_survives_the_filter() {
        val cover = violetCoverWithPeachBlock()
        // fully saturated, the peach clears Palette's skin-tone filter and is the light-vibrant swatch
        val lightVibrant = CoverSeedExtractor.palette(cover).lightVibrantSwatch
        assertNotNull(lightVibrant)
        assertTrue(hue(Color(lightVibrant!!.rgb)) in 20f..45f)

        val colors = extractBackdropColors(cover)!!
        val baseHue = hue(colors.baseColor)
        assertTrue("base hue $baseHue is violet-blue", baseHue in 240f..300f)
        // dark mode inks with the accent: it must not turn the violet cover's rating peach
        assertTrue(
            "accent hue ${hue(colors.accentColor)} stays in the base's family",
            hueDistance(colors.baseColor, colors.accentColor) <= BackdropAccentHueWindow,
        )
    }

    @Test
    fun should_still_tint_from_the_cover_when_now_playing_finds_no_seed() = runTest {
        val cover = sepiaCover()
        assertNull(CoverSeedExtractor.extractSeedArgb(cover))

        val colors = extractBackdropColors(cover)
        assertNotNull(colors)
        assertTrue(colors!!.isResolvedFromPalette)
        val baseHue = hue(colors.baseColor)
        assertTrue("base hue $baseHue is the sepia's", baseHue in 15f..45f)
    }

    @Test
    fun should_take_the_first_candidate_in_the_seed_family_when_choosing_the_accent() {
        val blue = Color(0xFF183888)
        val tan = Color(0xFFE0B880)
        val lavender = Color(0xFF8888E0)
        val colors = backdropColorsFrom(blue, listOf(tan, lavender))
        assertEquals(toneBackdropBase(blue).toArgb(), colors.baseColor.toArgb())
        // the tan comes first but sits ~180° away: the lavender is the accent
        assertTrue(hueDistance(colors.accentColor, lavender) <= 2f)
    }

    @Test
    fun should_lift_the_base_when_no_candidate_is_in_the_seed_family() {
        val blue = Color(0xFF183888)
        val colors = backdropColorsFrom(blue, listOf(Color(0xFFE0B880)))
        assertTrue(hueDistance(colors.baseColor, colors.accentColor) <= 2f)
        assertTrue(hsl(colors.accentColor)[2] > hsl(colors.baseColor)[2])
    }

    @Test
    fun should_lift_the_base_when_the_first_family_candidate_is_the_seed_itself() {
        val blue = Color(0xFF183888)
        val lifted = backdropColorsFrom(blue, emptyList())
        assertEquals(lifted.accentColor.toArgb(), backdropColorsFrom(blue, listOf(blue)).accentColor.toArgb())
    }

    @Test
    fun should_accept_any_candidate_when_the_seed_is_grey() {
        assertTrue(inBackdropHueFamily(Color(0xFF6E6E6E), Color(0xFFE2C27A)))
        assertTrue(inBackdropHueFamily(Color(0xFFE2C27A), Color(0xFF6E6E6E)))
        // red and magenta wrap around 0°/360°
        assertTrue(inBackdropHueFamily(Color(0xFFD02040), Color(0xFFD03020)))
    }
}
