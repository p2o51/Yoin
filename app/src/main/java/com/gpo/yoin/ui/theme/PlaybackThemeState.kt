package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Playback colors belong to the app session, not to each incoming window. */
class PlaybackThemeState(private val scope: CoroutineScope) {
    val transition = ColorSchemeTransition(scope)
    private var palette by mutableStateOf<PlaybackPalette?>(null)
    private var request: ArtworkRequest? = null
    private var loading: Job? = null

    fun colorScheme(darkTheme: Boolean): ColorScheme? =
        palette?.let { if (darkTheme) it.dark else it.light }

    fun updateArtwork(
        model: String?,
        clearWhenMissing: Boolean,
        loadBitmap: suspend (String) -> Bitmap?,
    ) {
        val next = ArtworkRequest(model, clearWhenMissing)
        if (request == next) return
        request = next
        loading?.cancel()
        loading = scope.launch {
            if (model != null) {
                val seed = CoverSeedExtractor.extractSeedArgb(loadBitmap(model))
                if (seed != null && palette?.seed != seed) {
                    palette = PlaybackPalette(
                        seed = seed,
                        light = ExpressiveColorSchemeFactory.fromSeed(seed, isDark = false),
                        dark = ExpressiveColorSchemeFactory.fromSeed(seed, isDark = true),
                    )
                }
            } else if (clearWhenMissing) {
                // Keep the current palette through brief empty-queue handoffs.
                delay(220)
                palette = null
            }
        }
    }
}

private data class ArtworkRequest(val model: String?, val clearWhenMissing: Boolean)
private data class PlaybackPalette(val seed: Int, val light: ColorScheme, val dark: ColorScheme)
