package com.gpo.yoin.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
    val cachedColors = remember(model) {
        model?.let(ExpressiveBackdropPaletteCache::get)
    }
    if (!enabled) {
        return cachedColors ?: fallbackColors
    }
    if (motionProfile == MotionProfile.AdaptiveReduced && cachedColors == null) {
        return fallbackColors
    }
    val colors by produceState(
        initialValue = cachedColors ?: fallbackColors,
        key1 = model,
        key2 = fallbackBaseColor,
        key3 = fallbackAccentColor,
    ) {
        if (model.isNullOrBlank()) {
            value = fallbackColors
            return@produceState
        }

        cachedColors?.let {
            value = it
            return@produceState
        }

        value = loadBackdropColors(
            context = context,
            model = model,
            fallbackColors = fallbackColors,
        )
    }
    return colors
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
