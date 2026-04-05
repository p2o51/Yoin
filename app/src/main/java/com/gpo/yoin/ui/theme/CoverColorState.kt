package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Observable state holder for the current album cover bitmap.
 *
 * Provided at the top of the composition tree (via [LocalCoverColorState]) so that
 * any composable can update the cover — primarily from the playback layer — and
 * [YoinTheme] can react by extracting a palette-driven [ColorScheme].
 */
class CoverColorState {

    /** The album cover bitmap used for palette extraction. `null` = no cover / idle. */
    var coverBitmap: Bitmap? by mutableStateOf(null)
        private set

    /** Set or replace the current cover bitmap. */
    fun updateCover(bitmap: Bitmap?) {
        coverBitmap = bitmap
    }

    /** Clear the cover bitmap (e.g. playback stopped). */
    fun clearCover() {
        coverBitmap = null
    }
}

/**
 * Composition local providing [CoverColorState] to the entire tree.
 *
 * Default instance has no cover; the real instance is provided by
 * [CompositionLocalProvider] in `MainActivity`.
 */
val LocalCoverColorState = staticCompositionLocalOf { CoverColorState() }
