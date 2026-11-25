package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.shared.palette.Palette
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

    /**
     * The previous / next songs' `primaryContainer`s, framing Now Playing's
     * room at the top and bottom edges (RoomAmbient). Null where there is
     * no neighbour or its cover hasn't been read yet.
     */
    var neighbours by mutableStateOf(CoverNeighbours())
        private set
    private var neighbourRequest: Pair<String?, String?>? = null
    private var neighbourLoading: Job? = null
    private val artworkCache = object : LinkedHashMap<String, CachedArtworkTheme>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedArtworkTheme>?) = size > 32
    }
    private val neighbourCache = object : LinkedHashMap<String, NeighbourTint>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NeighbourTint>?) = size > 8
    }

    private fun cacheKey(model: String): String = SubsonicCacheKey.of(model) ?: model

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
        if (model != null) {
            val cached = artworkCache[cacheKey(model)]
            if (cached != null) {
                if (cached.field != null) coverField = cached.field
                if (cached.palette != null && palette?.seed != cached.palette.seed) {
                    palette = cached.palette
                }
                return
            }
        }
        loading = scope.launch {
            if (model != null) {
                val bitmap = loadBitmap(model)
                // One Palette pass: the theme's seed and the focus room's swatches.
                val swatchPalette = bitmap?.let { withContext(Dispatchers.Default) { CoverSeedExtractor.palette(it) } }
                val seed = swatchPalette?.let { CoverSeedExtractor.seedSwatch(it)?.rgb }
                val field = bitmap?.let { readCoverField(it, swatchPalette?.let(::swatchesOf) ?: IntArray(0)) }
                val current = palette
                val built = seed?.let {
                    if (current?.seed == it) {
                        current
                    } else {
                        // The two scheme builds off the main thread too, like the Palette pass and the field.
                        withContext(Dispatchers.Default) {
                            PlaybackPalette(
                                seed = it,
                                light = ExpressiveColorSchemeFactory.fromSeed(it, isDark = false),
                                dark = ExpressiveColorSchemeFactory.fromSeed(it, isDark = true),
                            )
                        }
                    }
                }
                if (field != null || built != null) {
                    artworkCache[cacheKey(model)] = CachedArtworkTheme(palette = built, field = field)
                }
                if (field != null) coverField = field
                if (built != null && palette?.seed != built.seed) {
                    palette = built
                }
            } else if (clearWhenMissing) {
                // Keep the current palette through brief empty-queue handoffs.
                delay(220)
                palette = null
                coverField = null
            }
        }
    }

    /**
     * Reads the neighbours' covers after the current one's colours are in
     * (they never hold up the song change), from a small cache first so a
     * skip reuses the cover that just played.
     */
    fun updateNeighbours(
        previous: String?,
        next: String?,
        loadBitmap: suspend (String) -> Bitmap?,
    ) {
        val key = previous to next
        if (neighbourRequest == key) return
        neighbourRequest = key
        neighbourLoading?.cancel()
        neighbourLoading = scope.launch {
            // All cached (a skip): straight in. Otherwise the old frame stays
            // until the new covers are read, so the edges cross over once.
            val cachedPrevious = previous?.let { neighbourCache[cacheKey(it)] }
            val cachedNext = next?.let { neighbourCache[cacheKey(it)] }
            if ((previous == null || cachedPrevious != null) && (next == null || cachedNext != null)) {
                neighbours = CoverNeighbours(previous = cachedPrevious, next = cachedNext)
                return@launch
            }
            loading?.join()
            suspend fun tintOf(model: String?): NeighbourTint? {
                if (model == null) return null
                val k = cacheKey(model)
                neighbourCache[k]?.let { return it }
                artworkCache[k]?.palette?.let { pal ->
                    val tint = NeighbourTint(
                        light = pal.light.primaryContainer,
                        dark = pal.dark.primaryContainer,
                    )
                    neighbourCache[k] = tint
                    return tint
                }
                val seed = CoverSeedExtractor.extractSeedArgb(loadBitmap(model)) ?: return null
                val tint = withContext(Dispatchers.Default) {
                    NeighbourTint(
                        light = ExpressiveColorSchemeFactory.fromSeed(seed, isDark = false).primaryContainer,
                        dark = ExpressiveColorSchemeFactory.fromSeed(seed, isDark = true).primaryContainer,
                    )
                }
                neighbourCache[k] = tint
                return tint
            }
            val p = tintOf(previous)
            val n = tintOf(next)
            neighbours = CoverNeighbours(previous = p, next = n)
        }
    }
}

/** A neighbour song's `primaryContainer`, in both modes. */
@Immutable
data class NeighbourTint(val light: Color, val dark: Color) {
    fun of(dark: Boolean): Color = if (dark) this.dark else light
}

@Immutable
data class CoverNeighbours(val previous: NeighbourTint? = null, val next: NeighbourTint? = null)

/** Palette's swatches in order of importance, the ones it found. */
private fun swatchesOf(palette: Palette): IntArray = listOfNotNull(
    palette.vibrantSwatch,
    palette.mutedSwatch,
    palette.darkVibrantSwatch,
    palette.lightVibrantSwatch,
    palette.darkMutedSwatch,
    palette.lightMutedSwatch,
    palette.dominantSwatch,
).map { it.rgb }.distinct().toIntArray()

private suspend fun readCoverField(bitmap: Bitmap, swatches: IntArray): CoverField? = withContext(Dispatchers.Default) {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return@withContext null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    buildCoverField(pixels, w, h, swatches)
}

private data class ArtworkRequest(val model: String?, val clearWhenMissing: Boolean)
private data class PlaybackPalette(val seed: Int, val light: ColorScheme, val dark: ColorScheme)
private data class CachedArtworkTheme(val palette: PlaybackPalette?, val field: CoverField?)
