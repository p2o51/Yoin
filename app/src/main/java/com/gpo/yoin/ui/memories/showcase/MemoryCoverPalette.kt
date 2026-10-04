package com.gpo.yoin.ui.memories.showcase

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * A memory's colour, read off its cover the way Now Playing reads it (owner, 2026-10-05: "a blue cover shows
 * earth-yellow in Memories while Now Playing is right"). The playback theme seeds from
 * CoverSeedExtractor — the 200px decode, a 16-colour Palette with the default filter, vibrant → dominant →
 * muted — and so does this: same URL, same decode, same pass, same swatch. The palette is then the usual
 * direct lerp ([MemoryPalette.fromBackdrop]), never fromSeed.
 *
 * What it replaced read the cover through the backdrop extractor: a 12-colour pass with clearFilters(), which
 * let the cover's skin-tone / earth band (Palette's "I line") through — a blue cover with a tan ring got a tan
 * accent, and the accent tints the emblem's rim and label ring and a third of the aurora — and it skipped
 * extraction under adaptive pressure, never retrying, which left every card on the theme's colours. A colour is
 * not motion: this extraction always runs.
 */

/** The two colours Memories takes from a cover: the seed (the album's colour) and its lighter companion. */
@Immutable
internal data class MemoryCoverColors(val seed: Color, val accent: Color)

/** How far (HSL hue degrees) the accent may sit from the seed: the album reads as one colour. */
internal const val MemoryAccentHueWindow = 45f

/** Below this HSL saturation a colour has no hue worth matching (greys, near-whites). */
internal const val MemoryAccentGreyBelow = 0.12f

/** The accent's HSL lightness window: the prototype's accents sit at .66–.76 (light, never pastel-white). */
internal const val MemoryAccentMinLightness = 0.6
internal const val MemoryAccentMaxLightness = 0.78

/** With no swatch near the seed's hue, the accent is the seed lifted toward white by this much. */
internal const val MemoryAccentLift = 0.45

/**
 * The accent for [seed]: the first of [candidates] (the pass's light / vivid swatches, best first) within
 * [MemoryAccentHueWindow] of the seed's hue — any of them when either is a grey — else the seed lifted toward
 * white; then held in the accent lightness window.
 */
internal fun memoryAccentFor(seed: Color, candidates: List<Color>): Color {
    val picked = candidates.firstOrNull { candidate ->
        candidate.toArgb() != seed.toArgb() && sameHueFamily(seed, candidate)
    } ?: mixSrgb(seed, Color.White, MemoryAccentLift)
    return holdLightness(picked, MemoryAccentMinLightness, MemoryAccentMaxLightness)
}

/** The colours of a generated [palette]: the playback theme's seed swatch and the accent beside it. */
internal fun memoryCoverColors(palette: Palette): MemoryCoverColors? {
    val seed = CoverSeedExtractor.seedSwatch(palette)?.let { Color(it.rgb) } ?: return null
    val candidates = listOfNotNull(
        palette.lightVibrantSwatch,
        palette.lightMutedSwatch,
        palette.vibrantSwatch,
        palette.mutedSwatch,
    ).map { swatch -> Color(swatch.rgb) }
    return MemoryCoverColors(seed = seed, accent = memoryAccentFor(seed, candidates))
}

private fun sameHueFamily(a: Color, b: Color): Boolean {
    val ha = hsl(a)
    val hb = hsl(b)
    if (ha[1] < MemoryAccentGreyBelow || hb[1] < MemoryAccentGreyBelow) return true
    val d = abs(ha[0] - hb[0]) % 360f
    return minOf(d, 360f - d) <= MemoryAccentHueWindow
}

private fun hsl(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }

private object MemoryCoverColorCache {
    private val cache = LruCache<String, MemoryCoverColors>(64)

    fun get(model: String): MemoryCoverColors? = cache.get(model)

    fun put(model: String, colors: MemoryCoverColors) {
        cache.put(model, colors)
    }
}

/** Decodes [model] as the playback theme does and reads its colours; null when the cover can't be loaded. */
private suspend fun loadMemoryCoverColors(context: Context, model: String): MemoryCoverColors? = try {
    withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(CoverSeedExtractor.BitmapSizePx, CoverSeedExtractor.BitmapSizePx))
            .allowHardware(false)
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        bitmap?.let { memoryCoverColors(CoverSeedExtractor.palette(it)) }
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}?.also { colors -> MemoryCoverColorCache.put(model, colors) }

/**
 * The palette of one memory: the fixture's (previews, the harness), else its cover's ([MemoryEntry.paletteCoverUrl],
 * the playback theme's URL for it). Until the cover is read the theme's primary stands in, with an accent of its
 * own family; the hand-off springs on the effects spring, seed and accent alike.
 */
@Composable
internal fun rememberMemoryPalette(memory: MemoryEntry, fixture: MemoryPalette?): MemoryPalette {
    if (fixture != null) return fixture
    val context = LocalContext.current
    val model = memory.paletteCoverUrl ?: memory.coverArtUrl
    val primary = MaterialTheme.colorScheme.primary
    val placeholder = remember(primary) { MemoryCoverColors(primary, memoryAccentFor(primary, emptyList())) }
    // re-read every composition: a sibling card (or a reopened deck) may have read the same cover since
    val cached = model?.let(MemoryCoverColorCache::get)
    var loaded by remember(model) { mutableStateOf<MemoryCoverColors?>(null) }
    LaunchedEffect(model) {
        if (model.isNullOrBlank() || MemoryCoverColorCache.get(model) != null) return@LaunchedEffect
        loaded = loadMemoryCoverColors(context, model)
    }
    val target = cached ?: loaded ?: placeholder
    val spec = YoinMotion.defaultEffectsSpec<Color>(role = YoinMotionRole.Standard)
    val seed by animateColorAsState(target.seed, spec, label = "memoryPaletteSeed")
    val accent by animateColorAsState(target.accent, spec, label = "memoryPaletteAccent")
    return remember(seed, accent) { MemoryPalette.fromBackdrop(seed, accent) }
}
