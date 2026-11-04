package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.nowplaying.CoverField
import com.gpo.yoin.ui.nowplaying.buildCoverField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playback colors belong to the app session, not to each incoming window. */
class PlaybackThemeState(private val scope: CoroutineScope) {
    val sharedScheme = SharedColorScheme()
    private var palette by mutableStateOf<PlaybackPalette?>(null)
    private var request: ArtworkRequest? = null

    /**
     * The cover's colours by position and its main hues (Now Playing's rest
     * wash, song-change flow and focus field). Not animated: those effects run
     * their own motion from one cover's field to the next.
     */
    var coverField by mutableStateOf<CoverField?>(null)
        private set
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
                val bitmap = loadBitmap(model)
                val seed = CoverSeedExtractor.extractSeedArgb(bitmap)
                val field = bitmap?.let { readCoverField(it) }
                if (field != null) coverField = field
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
                coverField = null
            }
        }
    }
}

private suspend fun readCoverField(bitmap: Bitmap): CoverField? = withContext(Dispatchers.Default) {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return@withContext null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    buildCoverField(pixels, w, h)
}

private data class ArtworkRequest(val model: String?, val clearWhenMissing: Boolean)
private data class PlaybackPalette(val seed: Int, val light: ColorScheme, val dark: ColorScheme)
