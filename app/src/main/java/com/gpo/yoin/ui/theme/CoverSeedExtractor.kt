package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import com.gpo.yoin.shared.palette.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a single expressive seed color from album artwork.
 *
 * The selection logic intentionally stays close to the previous implementation:
 * prefer a vivid swatch, otherwise fall back to the dominant or muted swatch.
 *
 * This is the one place that decides "the album's colour": the playback theme
 * seeds from it, and Memories reads the same swatch off the same Palette pass
 * ([palette] + [seedSwatch]) so a cover never reads as two different colours.
 *
 * The Palette pass is the :shared port (the code the iOS app runs too),
 * byte-identical to androidx.palette (PaletteParityTest). Decoding and the
 * Builder's resize stay here, on Android bitmaps.
 */
object CoverSeedExtractor {
    private const val MaxColors = 16

    /** The edge, in px, of the bitmap the cover is decoded at for the seed (the playback theme's request). */
    const val BitmapSizePx = 200

    suspend fun extractSeedArgb(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null

        return withContext(Dispatchers.Default) {
            seedSwatch(palette(bitmap))?.rgb
        }
    }

    /**
     * The seed's Palette pass: [MaxColors] colours with Palette's default
     * filter (no near-black, near-white or skin-tone "I line" swatches). Blocking.
     * [maximumColorCount] / [filters] (empty = `clearFilters()`) are for other passes over a cover.
     */
    fun palette(
        bitmap: Bitmap,
        maximumColorCount: Int = MaxColors,
        filters: List<Palette.Filter> = listOf(Palette.DEFAULT_FILTER),
    ): Palette = Palette.generate(palettePixels(bitmap), maximumColorCount, filters)

    /** The album's colour in [palette]: vivid first, then dominant, then muted. */
    fun seedSwatch(palette: Palette): Palette.Swatch? = palette.vibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch

    /**
     * The pixels `Palette.from(bitmap).generate()` quantizes: the Builder's
     * default-area resize ([Palette.DEFAULT_RESIZE_BITMAP_AREA],
     * `createScaledBitmap(…, filter = false)`), then `getPixels`.
     */
    internal fun palettePixels(bitmap: Bitmap): IntArray {
        val scaled = if (bitmap.width * bitmap.height > Palette.DEFAULT_RESIZE_BITMAP_AREA) {
            val size = Palette.scaledSize(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, size[0], size[1], false)
        } else {
            bitmap
        }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        return pixels
    }
}
