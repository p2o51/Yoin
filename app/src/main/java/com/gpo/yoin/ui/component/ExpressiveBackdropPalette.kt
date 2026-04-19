package com.gpo.yoin.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ExpressiveBackdropColors(
    val baseColor: Color,
    val accentColor: Color,
    /**
     * `true` if `baseColor` / `accentColor` came from palette extraction on
     * the source image; `false` if they are still (or stuck on) the caller-
     * supplied fallback (image not yet loaded, palette found no significant
     * swatch, or motion profile disabled extraction).
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

internal const val ExpressiveBackdropArtworkScale = 0.8f

private const val BackdropPaletteCacheSize = 96
private const val BackdropPaletteRequestSize = 96
private const val BackdropPaletteColorCount = 12

private object ExpressiveBackdropPaletteCache {
    private val cache = LruCache<String, ExpressiveBackdropColors>(BackdropPaletteCacheSize)

    fun get(model: String): ExpressiveBackdropColors? = cache.get(model)

    fun put(model: String, colors: ExpressiveBackdropColors) {
        cache.put(model, colors)
    }
}

private object ExpressiveBackdropImageLoader {
    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .build()
                .also { loader = it }
        }
    }
}

@Composable
internal fun rememberExpressiveBackdropColors(
    model: String?,
    fallbackBaseColor: Color,
    fallbackAccentColor: Color,
    enabled: Boolean = true,
): ExpressiveBackdropColors {
    val context = LocalContext.current
    val motionProfile = LocalMotionProfile.current
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

    LaunchedEffect(model, enabled) {
        if (!enabled) return@LaunchedEffect
        if (model.isNullOrBlank()) return@LaunchedEffect
        if (ExpressiveBackdropPaletteCache.get(model) != null) return@LaunchedEffect
        if (motionProfile == MotionProfile.AdaptiveReduced) return@LaunchedEffect
        resolvedColors = loadBackdropColors(
            context = context,
            model = model,
            fallbackColors = fallbackColors,
        )
    }
    // Soften the hand-off when the palette resolves: tween the two
    // colors instead of snapping from fallback → extracted, which is
    // what the user perceives as a "flash" on newly-loaded artwork.
    val animatedBase by animateColorAsState(
        targetValue = resolvedColors.baseColor,
        animationSpec = tween(durationMillis = 380),
        label = "backdropBase",
    )
    val animatedAccent by animateColorAsState(
        targetValue = resolvedColors.accentColor,
        animationSpec = tween(durationMillis = 380),
        label = "backdropAccent",
    )
    return ExpressiveBackdropColors(
        baseColor = animatedBase,
        accentColor = animatedAccent,
        isResolvedFromPalette = resolvedColors.isResolvedFromPalette,
    )
}

private suspend fun loadBackdropColors(
    context: Context,
    model: String,
    fallbackColors: ExpressiveBackdropColors,
): ExpressiveBackdropColors {
    val extractedColors = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(BackdropPaletteRequestSize, BackdropPaletteRequestSize))
            .allowHardware(false)
            .build()
        val result = ExpressiveBackdropImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap()
        bitmap?.let(::extractBackdropColors)
    }

    extractedColors?.let {
        ExpressiveBackdropPaletteCache.put(model, it)
        return it
    }
    return fallbackColors
}

private fun extractBackdropColors(bitmap: Bitmap): ExpressiveBackdropColors? {
    val palette = Palette.from(bitmap)
        .maximumColorCount(BackdropPaletteColorCount)
        .clearFilters()
        .generate()

    val dominantSwatch = palette.vibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch
        ?: palette.darkVibrantSwatch
        ?: return null
    val accentSwatch = palette.lightVibrantSwatch
        ?: palette.vibrantSwatch
        ?: palette.lightMutedSwatch
        ?: palette.mutedSwatch
        ?: dominantSwatch

    val baseColor = toneBackdropBase(Color(dominantSwatch.rgb))
    val accentSource = if (accentSwatch.rgb == dominantSwatch.rgb) {
        lighten(baseColor, 0.18f)
    } else {
        Color(accentSwatch.rgb)
    }

    return ExpressiveBackdropColors(
        baseColor = baseColor,
        accentColor = toneBackdropAccent(accentSource),
        isResolvedFromPalette = true,
    )
}

private fun toneBackdropBase(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.82f).coerceIn(0.18f, 0.78f)
    hsl[2] = (hsl[2] * 0.72f + 0.12f).coerceIn(0.24f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun toneBackdropAccent(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.08f).coerceIn(0.24f, 0.92f)
    hsl[2] = (hsl[2] + 0.14f).coerceIn(0.36f, 0.82f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun lighten(color: Color, amount: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}
