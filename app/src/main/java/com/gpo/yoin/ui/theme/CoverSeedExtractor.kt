package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
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
     */
    fun palette(bitmap: Bitmap): Palette = Palette.from(bitmap)
        .maximumColorCount(MaxColors)
        .generate()

    /** The album's colour in [palette]: vivid first, then dominant, then muted. */
    fun seedSwatch(palette: Palette): Palette.Swatch? = palette.vibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch
}
