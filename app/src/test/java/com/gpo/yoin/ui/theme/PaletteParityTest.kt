package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.palette.graphics.Palette as AndroidxPalette
import com.gpo.yoin.shared.palette.Palette as SharedPalette
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The :shared Palette port (com.gpo.yoin.shared.palette, the one the iOS app runs) against
 * androidx.palette on the same bitmaps, byte for byte: every quantized swatch (rgb, population,
 * order), the dominant swatch and all six target picks, with the default filter and with no filter.
 *
 * Bitmaps: the iOS mock covers (200 × 200 renders, test resources palette/mock-covers, written by
 * iosApp's CoverPixelDumpTests) and generated images — gradients, noise, "photo-like" blobs, flat
 * and few-colour art, non-square and smaller-than-resize sizes. Robolectric's native graphics do the
 * Builder's createScaledBitmap, so the resize the shared code leaves to the caller is checked too,
 * and so is the nearest-neighbour stand-in the iOS side uses ([SharedPalette.scaleDownPixels]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaletteParityTest {

    @Test
    fun should_match_androidx_palette_when_reading_the_ios_mock_covers() {
        names().forEach { (name, bitmap) -> assertParity(name, bitmap) }
    }

    @Test
    fun should_match_androidx_palette_when_reading_generated_images() {
        generatedImages().forEach { (name, bitmap) -> assertParity(name, bitmap) }
    }

    /**
     * The iOS side decodes covers at 200 px, as Android does (CoverSeedExtractor.BitmapSizePx), and
     * scales them with [SharedPalette.scaleDownPixels]: exact against Skia at that size. (At some other
     * sizes Skia's fixed-point stepping picks the neighbouring pixel on exact boundaries.)
     */
    @Test
    fun should_match_create_scaled_bitmap_when_scaling_a_200px_decode_without_a_bitmap() {
        (names() + generatedImages()).filter { (_, b) -> b.width == 200 && b.height == 200 }.forEach { (name, bitmap) ->
            val expected = builderPixels(bitmap)
            val actual = SharedPalette.scaleDownPixels(bitmap.pixels(), bitmap.width, bitmap.height)
            assertEquals("$name: scaled pixel count", expected.size, actual.size)
            val firstDiff = expected.indices.firstOrNull { expected[it] != actual[it] }
            assertEquals("$name: first differing scaled pixel", null, firstDiff)
        }
    }

    private fun names(): List<Pair<String, Bitmap>> = (1..12).map { "al$it" to mockCover("al$it") }

    private fun assertParity(name: String, bitmap: Bitmap) {
        for (maxColors in listOf(16, 12, 24)) {
            val expected = AndroidxPalette.from(bitmap).maximumColorCount(maxColors).generate()
            val actual = SharedPalette.generate(builderPixels(bitmap), maxColors)
            assertSame("$name max=$maxColors filtered", expected, actual)

            val expectedRaw = AndroidxPalette.from(bitmap).maximumColorCount(maxColors).clearFilters().generate()
            val actualRaw = SharedPalette.generate(builderPixels(bitmap), maxColors, filters = emptyList())
            assertSame("$name max=$maxColors unfiltered", expectedRaw, actualRaw)
        }
    }

    private fun assertSame(label: String, expected: AndroidxPalette, actual: SharedPalette) {
        assertEquals("$label: swatches", expected.swatches.map { it.key() }, actual.swatches.map { it.key() })
        assertEquals("$label: dominant", expected.dominantSwatch?.key(), actual.dominantSwatch?.key())
        assertEquals("$label: lightVibrant", expected.lightVibrantSwatch?.key(), actual.lightVibrantSwatch?.key())
        assertEquals("$label: vibrant", expected.vibrantSwatch?.key(), actual.vibrantSwatch?.key())
        assertEquals("$label: darkVibrant", expected.darkVibrantSwatch?.key(), actual.darkVibrantSwatch?.key())
        assertEquals("$label: lightMuted", expected.lightMutedSwatch?.key(), actual.lightMutedSwatch?.key())
        assertEquals("$label: muted", expected.mutedSwatch?.key(), actual.mutedSwatch?.key())
        assertEquals("$label: darkMuted", expected.darkMutedSwatch?.key(), actual.darkMutedSwatch?.key())
        expected.swatches.zip(actual.swatches).forEach { (e, a) ->
            assertEquals("$label: hsl of ${e.key()}", e.hsl.toList(), a.hsl.toList())
        }
    }

    private fun AndroidxPalette.Swatch.key() = "%08X×%d".format(rgb, population)
    private fun SharedPalette.Swatch.key() = "%08X×%d".format(rgb, population)

    /** What Palette.Builder quantizes: its default-area createScaledBitmap, then getPixels. */
    private fun builderPixels(bitmap: Bitmap): IntArray {
        val size = SharedPalette.scaledSize(bitmap.width, bitmap.height)
        val scaled = if (bitmap.width * bitmap.height > SharedPalette.DEFAULT_RESIZE_BITMAP_AREA) {
            Bitmap.createScaledBitmap(bitmap, size[0], size[1], false)
        } else {
            bitmap
        }
        return scaled.pixels()
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun mockCover(name: String): Bitmap {
        val stream: InputStream = requireNotNull(javaClass.classLoader?.getResourceAsStream("palette/mock-covers/$name.png")) {
            "missing palette/mock-covers/$name.png"
        }
        val image = stream.use { ImageIO.read(it) }
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmap(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { pixel(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun argb(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun generatedImages(): List<Pair<String, Bitmap>> {
        val random = Random(51)
        val list = mutableListOf<Pair<String, Bitmap>>()
        list += "diagonal gradient 200" to bitmap(200, 200) { x, y ->
            argb(x / 199.0, y / 199.0, 0.35 + 0.3 * sin(x / 31.0))
        }
        list += "radial gradient 256" to bitmap(256, 256) { x, y ->
            val d = hypot(x - 128.0, y - 110.0) / 181.0
            argb(0.9 - 0.7 * d, 0.3 + 0.2 * d, 0.55 + 0.4 * d)
        }
        list += "hue sweep 300x180" to bitmap(300, 180) { x, y ->
            val h = x / 300.0 * 2 * PI
            argb(0.5 + 0.5 * cos(h), 0.5 + 0.5 * cos(h - 2.1), 0.5 + 0.5 * cos(h + 2.1) - y / 600.0)
        }
        list += "white noise 200" to bitmap(200, 200) { _, _ -> (0xFF shl 24) or random.nextInt(0x1000000) }
        list += "tinted noise 180x240" to bitmap(180, 240) { _, _ ->
            argb(0.2 + 0.3 * random.nextDouble(), 0.35 + 0.4 * random.nextDouble(), 0.6 + 0.3 * random.nextDouble())
        }
        // "Photo-like": a sky gradient, a few soft blobs (subject, foliage, skin tone) and sensor grain.
        val blobs = List(6) {
            doubleArrayOf(
                random.nextDouble() * 200, random.nextDouble() * 200, 25 + random.nextDouble() * 60,
                random.nextDouble(), random.nextDouble(), random.nextDouble(),
            )
        }
        for ((index, size) in listOf(200 to 200, 640 to 640, 113 to 112, 90 to 90).withIndex()) {
            val (w, h) = size
            list += "photo-like #$index ${w}x$h" to bitmap(w, h) { x, y ->
                val u = x * 200.0 / w
                val v = y * 200.0 / h
                var r = 0.25 + 0.35 * (1 - v / 200)
                var g = 0.4 + 0.3 * (1 - v / 200)
                var b = 0.7 - 0.2 * (v / 200)
                for (blob in blobs) {
                    val wgt = kotlin.math.exp(-((u - blob[0]) * (u - blob[0]) + (v - blob[1]) * (v - blob[1])) / (blob[2] * blob[2]))
                    r += (blob[3] - r) * wgt
                    g += (blob[4] - g) * wgt
                    b += (blob[5] - b) * wgt
                }
                val grain = (random.nextDouble() - 0.5) * 0.08
                argb(r + grain, g + grain, b + grain)
            }
        }
        list += "skin and earth 200" to bitmap(200, 200) { x, y ->
            argb(0.78 + 0.1 * sin(x / 17.0), 0.55 + 0.08 * cos(y / 13.0), 0.42 + 0.05 * sin((x + y) / 9.0))
        }
        list += "near black and white 200" to bitmap(200, 200) { x, _ ->
            if (x < 100) argb(0.02, 0.02, 0.03) else argb(0.98, 0.97, 0.99)
        }
        list += "four flat colours 200" to bitmap(200, 200) { x, y ->
            when ((x / 100) + 2 * (y / 100)) {
                0 -> 0xFF7048C8.toInt()
                1 -> 0xFFF0B858.toInt()
                2 -> 0xFF289878.toInt()
                else -> 0xFFD03870.toInt()
            }
        }
        list += "stripes 64" to bitmap(64, 64) { x, _ -> if ((x / 4) % 2 == 0) 0xFF3858D0.toInt() else 0xFFC04028.toInt() }
        list += "single colour 200" to bitmap(200, 200) { _, _ -> 0xFF55606E.toInt() }
        return list
    }
}
