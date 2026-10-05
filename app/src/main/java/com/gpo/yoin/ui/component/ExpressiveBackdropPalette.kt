package com.gpo.yoin.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
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
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ExpressiveBackdropColors(
    val baseColor: Color,
    val accentColor: Color,
    /**
     * `true` if `baseColor` / `accentColor` came from palette extraction on
     * the source image; `false` if they are still (or stuck on) the caller-
     * supplied fallback (image not yet loaded, palette found no significant
     * swatch, or extraction is still deferred by the caller's scroll gate).
     *
     * Callers that want to *render* the extracted colors but fall back to a
     * neutral surface when extraction failed (AlbumDetail / PlaylistDetail
     * pass `null` to `ExpressivePageBackground` in that case) should gate
     * on this flag — otherwise the animated transition from fallbackAccent
     * to extracted accent lerps them indistinguishably from
     * `surfaceContainer` on specifically the "no palette resolved" path,
     * which surfaced as "a lot of albums don't look tinted".
     */
    val isResolvedFromPalette: Boolean = false,
)

private const val BackdropPaletteCacheSize = 96

/** The unfiltered read's colour count — only for covers the seed pass finds no colour in. */
private const val BackdropFallbackColorCount = 12

/**
 * How far (HSL hue degrees) the accent may sit from the base. Dark mode inks
 * with the accent and light mode with the base, so the two must read as the
 * album's one colour (the Memories accent window, 2026-10-05).
 */
internal const val BackdropAccentHueWindow = 45f

/** Below this HSL saturation a colour has no hue worth matching (greys, near-whites). */
internal const val BackdropAccentGreyBelow = 0.12f

/** With no swatch in the base's family, the accent is the toned base lifted by this much lightness. */
private const val BackdropAccentLift = 0.18f

private object ExpressiveBackdropPaletteCache {
    private val cache = LruCache<String, ExpressiveBackdropColors>(BackdropPaletteCacheSize)

    fun get(model: String): ExpressiveBackdropColors? = cache.get(model)

    fun put(model: String, colors: ExpressiveBackdropColors) {
        cache.put(model, colors)
    }
}

/**
 * The palette a card already resolved for [model], read synchronously; null
 * when it hasn't (no extraction is started). Home edit strips tint with it.
 */
internal fun cachedBackdropColors(model: String): ExpressiveBackdropColors? = ExpressiveBackdropPaletteCache.get(model)

@Composable
internal fun rememberExpressiveBackdropColors(
    model: String?,
    fallbackBaseColor: Color,
    fallbackAccentColor: Color,
    enabled: Boolean = true,
): ExpressiveBackdropColors {
    val context = LocalContext.current
    return rememberBackdropColorsLoadedBy(
        model = model,
        fallbackBaseColor = fallbackBaseColor,
        fallbackAccentColor = fallbackAccentColor,
        enabled = enabled,
        load = { coverModel -> loadBackdropColors(context, coverModel) },
    )
}

/**
 * [rememberExpressiveBackdropColors] with the cover read injected ([load]: the
 * extracted colours, or null when the cover can't be read). Tests drive the
 * deferral with it; the app always reads through [loadBackdropColors].
 *
 * [enabled] is the caller's performance gate, not a motion setting: Home closes
 * it until its first frames have settled and while the feed scrolls, so no
 * card decodes and quantises its cover during a fling (each read is a 200px
 * Coil decode plus a 16-colour Palette pass). It defers the read, never drops
 * it — it is an effect key, so the gate reopening reads the cover once.
 */
@Composable
internal fun rememberBackdropColorsLoadedBy(
    model: String?,
    fallbackBaseColor: Color,
    fallbackAccentColor: Color,
    enabled: Boolean,
    load: suspend (model: String) -> ExpressiveBackdropColors?,
): ExpressiveBackdropColors {
    val fallbackColors = remember(fallbackBaseColor, fallbackAccentColor) {
        ExpressiveBackdropColors(
            baseColor = fallbackBaseColor,
            accentColor = fallbackAccentColor,
        )
    }
    // Re-read the palette cache on every composition. `remember(model)` would
    // snapshot at first composition, which is the bug behind the "fast-scroll
    // white flash": once a previously resolved entry is composed while
    // `enabled=false` (scroll gate closed), the old code returns the snapshot
    // cachedColors (null at first frame) instead of the colors that a sibling
    // composition already produced and wrote to the LruCache.
    val cacheHit = model?.let(ExpressiveBackdropPaletteCache::get)
    // Monotonic "last resolved" state keyed on model. Persists across
    // `enabled` flips so already-colored cards keep their colors when
    // scrolling pauses palette extraction.
    var resolvedColors by remember(model) {
        mutableStateOf(cacheHit ?: fallbackColors)
    }
    if (cacheHit != null && resolvedColors !== cacheHit) {
        resolvedColors = cacheHit
    }

    // A colour is not motion (owner, 2026-10-05, as Memories reads its covers):
    // the read runs under adaptive motion pressure / reduced motion too. It used
    // to wait for MotionProfile.Full, so on battery saver, a low-RAM device or
    // while any screen reported frame pressure the cards wore the theme's
    // colours. Only the caller's scroll gate ([enabled]) still defers it; the
    // hand-off below is a colour spring, which reduced motion keeps.
    LaunchedEffect(model, enabled) {
        if (!enabled) return@LaunchedEffect
        if (model.isNullOrBlank()) return@LaunchedEffect
        if (ExpressiveBackdropPaletteCache.get(model) != null) return@LaunchedEffect
        resolvedColors = load(model)
            ?.also { colors -> ExpressiveBackdropPaletteCache.put(model, colors) }
            ?: fallbackColors
    }
    // Soften the hand-off when the palette resolves: spring the two
    // colors (effects bucket — the motion law for color/alpha) instead of
    // snapping from fallback → extracted, which is what the user perceives
    // as a "flash" on newly-loaded artwork.
    val animatedBase by animateColorAsState(
        targetValue = resolvedColors.baseColor,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "backdropBase",
    )
    val animatedAccent by animateColorAsState(
        targetValue = resolvedColors.accentColor,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "backdropAccent",
    )
    return ExpressiveBackdropColors(
        baseColor = animatedBase,
        accentColor = animatedAccent,
        isResolvedFromPalette = resolvedColors.isResolvedFromPalette,
    )
}

/**
 * Decodes [model] the way the playback theme does (the same request size, so
 * the same Coil memory-cache entry and the same pixels) and reads its colours.
 */
private suspend fun loadBackdropColors(context: Context, model: String): ExpressiveBackdropColors? =
    withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(CoverSeedExtractor.BitmapSizePx, CoverSeedExtractor.BitmapSizePx))
            .allowHardware(false)
            .build()
        // App-wide singleton (YoinApplication is the factory) — palette pixel
        // reads stay safe because allowHardware(false) is set per-request.
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap()
        bitmap?.let(::extractBackdropColors)
    }

/**
 * A cover's backdrop colours, read off the cover the way Now Playing reads it
 * (owner, 2026-10-05: a blue cover showed earth-yellow where Now Playing was
 * right). The base is the playback theme's seed — [CoverSeedExtractor]'s
 * 16-colour pass with Palette's default filter (no near-black, near-white or
 * skin-tone "I line" swatches), vibrant → dominant → muted — toned for a
 * backdrop. The accent is the first light / vivid swatch of the same pass in
 * the base's hue family, else the base lifted.
 *
 * What it replaced read a 12-colour pass with `clearFilters()`, which let a
 * cover's skin / ochre band through: a blue cover with a tan ring got a tan
 * accent (dark-mode ratings and inks), and a navy cover with a face on it a
 * tan base. That pass survives only for covers the seed pass finds nothing in
 * (an all-sepia or tan cover, or only near-black and near-white), where Now
 * Playing has no colour either and the card should still wear its cover.
 */
internal fun extractBackdropColors(bitmap: Bitmap): ExpressiveBackdropColors? {
    val palette = CoverSeedExtractor.palette(bitmap)
    CoverSeedExtractor.seedSwatch(palette)?.let { seed ->
        return backdropColorsFrom(Color(seed.rgb), palette.accentCandidates())
    }
    val unfiltered = Palette.from(bitmap)
        .maximumColorCount(BackdropFallbackColorCount)
        .clearFilters()
        .generate()
    val swatch = unfiltered.vibrantSwatch
        ?: unfiltered.dominantSwatch
        ?: unfiltered.mutedSwatch
        ?: unfiltered.darkVibrantSwatch
        ?: return null
    return backdropColorsFrom(Color(swatch.rgb), unfiltered.accentCandidates())
}

/** The accent's candidates, best first: light / vivid swatches before muted ones. */
private fun Palette.accentCandidates(): List<Color> = listOfNotNull(
    lightVibrantSwatch,
    vibrantSwatch,
    lightMutedSwatch,
    mutedSwatch,
).map { swatch -> Color(swatch.rgb) }

/**
 * The backdrop colours for a cover whose colour is [seed]: the toned seed as
 * the base; as the accent the first of [accentCandidates] in the seed's hue
 * family ([BackdropAccentHueWindow]), toned — or, when that is the seed itself
 * or none qualifies, the base lifted by [BackdropAccentLift].
 */
internal fun backdropColorsFrom(seed: Color, accentCandidates: List<Color>): ExpressiveBackdropColors {
    val baseColor = toneBackdropBase(seed)
    val picked = accentCandidates.firstOrNull { candidate -> inBackdropHueFamily(seed, candidate) }
    val accentSource = if (picked == null || picked.toArgb() == seed.toArgb()) {
        lighten(baseColor, BackdropAccentLift)
    } else {
        picked
    }
    return ExpressiveBackdropColors(
        baseColor = baseColor,
        accentColor = toneBackdropAccent(accentSource),
        isResolvedFromPalette = true,
    )
}

/** Whether [candidate] reads as [seed]'s colour: within the hue window, or either is a grey. */
internal fun inBackdropHueFamily(seed: Color, candidate: Color): Boolean {
    val a = hsl(seed)
    val b = hsl(candidate)
    if (a[1] < BackdropAccentGreyBelow || b[1] < BackdropAccentGreyBelow) return true
    val distance = abs(a[0] - b[0]) % 360f
    return minOf(distance, 360f - distance) <= BackdropAccentHueWindow
}

internal fun toneBackdropBase(color: Color): Color {
    val hsl = hsl(color)
    hsl[1] = (hsl[1] * 0.82f).coerceIn(0.18f, 0.78f)
    hsl[2] = (hsl[2] * 0.72f + 0.12f).coerceIn(0.24f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun toneBackdropAccent(color: Color): Color {
    val hsl = hsl(color)
    hsl[1] = (hsl[1] * 1.08f).coerceIn(0.24f, 0.92f)
    hsl[2] = (hsl[2] + 0.14f).coerceIn(0.36f, 0.82f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun lighten(color: Color, amount: Float): Color {
    val hsl = hsl(color)
    hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun hsl(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
