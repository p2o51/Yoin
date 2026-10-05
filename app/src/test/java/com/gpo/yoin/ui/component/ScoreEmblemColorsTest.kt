package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.ColorUtils
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveNeutrals
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.emblem.grooveColors
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.memoryAccentFor
import com.gpo.yoin.ui.memories.showcase.memoryCoverColors
import com.gpo.yoin.ui.memories.showcase.rememberMemoryCoverColors
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The score emblem reads a cover's colours on exactly Memories' path (= Now Playing's seed swatch plus Memories'
 * accent), so the album page and Memories draw one cover as one emblem.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreEmblemColorsTest {

    @get:Rule
    val rule = createComposeRule()

    /** hls(h, l, s) to 8-bit ARGB. */
    private fun hls(h: Float, l: Float, s: Float): Int = ColorUtils.HSLToColor(floatArrayOf(h * 360f, s, l))

    /** A vertical gradient over [hueFrom]…[hueTo], with an optional ring of [ring] colour. */
    private fun cover(hueFrom: Float, hueTo: Float, saturation: Float, ring: Int? = null): Bitmap {
        val size = CoverSeedExtractor.BitmapSizePx
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until size) {
            val t = y / size.toFloat()
            paint.color = hls((hueFrom + (hueTo - hueFrom) * t + 1f) % 1f, 0.25f + 0.35f * t, saturation)
            canvas.drawLine(0f, y.toFloat(), size.toFloat(), y.toFloat(), paint)
        }
        if (ring != null) {
            paint.color = ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 40f
            canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
        }
        return bitmap
    }

    private val covers = listOf(
        "blue with a tan ring" to cover(0.60f, 0.68f, 0.65f, ring = hls(0.10f, 0.7f, 0.6f)),
        "red to orange" to cover(0.98f, 0.06f, 0.7f),
        "near grey" to cover(0.55f, 0.58f, 0.08f),
    )

    private fun neutrals(dark: Boolean) = GrooveNeutrals(
        outline = Color(0xFF79747E),
        surface = if (dark) Color(0xFF141218) else Color(0xFFFEF7FF),
        onSurfaceVariant = Color(0xFF49454F),
        tiltHigh = Color.White,
        tiltLow = Color(0xFF1D1B20),
        dark = dark,
    )

    @Test
    fun should_match_memory_cover_colors_when_given_the_same_palette() {
        covers.forEach { (name, bitmap) ->
            val palette = CoverSeedExtractor.palette(bitmap)
            val memories = memoryCoverColors(palette)
            val emblem = scoreEmblemColorsOf(palette)
            assertNotNull(name, memories)
            assertEquals(name, ScoreEmblemColors(base = memories!!.seed, accent = memories.accent), emblem)
        }
    }

    @Test
    fun should_draw_memories_groove_colours_when_given_the_same_palette() {
        covers.forEach { (name, bitmap) ->
            val palette = CoverSeedExtractor.palette(bitmap)
            val memories = memoryCoverColors(palette)!!
            val memoryPalette = MemoryPalette.fromBackdrop(memories.seed, memories.accent)
            val emblemPalette = scoreEmblemColorsOf(palette)!!.toMemoryPalette()
            assertEquals(name, memoryPalette, emblemPalette)
            for (dark in listOf(false, true)) {
                for (kind in GrooveKind.entries) {
                    for (surface in GrooveSurface.entries) {
                        assertEquals(
                            "$name · $kind · dark=$dark · $surface",
                            grooveColors(memoryPalette, kind, dark, surface, neutrals(dark)),
                            grooveColors(emblemPalette, kind, dark, surface, neutrals(dark)),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun should_share_memories_placeholder_when_cover_url_is_null() {
        val primary = Color(0xFF6750A4)
        var emblem: ScoreEmblemColors? = null
        var memories: ScoreEmblemColors? = null
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = primary)) {
                emblem = rememberScoreEmblemColors(null)
                memories = rememberMemoryCoverColors(null).toScoreEmblemColors()
            }
        }
        rule.waitForIdle()
        assertEquals(memories, emblem)
        assertEquals(ScoreEmblemColors(primary, memoryAccentFor(primary, emptyList())), emblem)
    }
}
