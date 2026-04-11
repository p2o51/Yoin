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
 */
object CoverSeedExtractor {
    private const val MaxColors = 16

    suspend fun extractSeedArgb(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null

        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap)
                .maximumColorCount(MaxColors)
                .generate()

            val seedSwatch = palette.vibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch

            seedSwatch?.rgb
        }
    }
}
