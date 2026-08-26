package com.gpo.yoin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CoverSchemeRequestSize = 128

/**
 * Builds a Material [ColorScheme] (MCU `SchemeExpressive`, SPEC_2025) seeded
 * from an arbitrary cover URL — i.e. the album you're VIEWING, not the one
 * playing. This is the Material-dynamic-color path (`primary` / `secondary` /
 * their containers + on-colors), as opposed to the raw two-swatch palette in
 * [com.gpo.yoin.ui.component] which reads "off" when used as theme color.
 *
 * Returns null until the bitmap resolves (or if it yields no usable seed), so
 * callers fall back to the app theme. Mirrors the playback-scoped seeding in
 * [YoinTheme] but for a one-off, non-playing cover.
 */
@Composable
fun rememberCoverColorScheme(model: String?): ColorScheme? {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    var scheme by remember(model, isDark) { mutableStateOf<ColorScheme?>(null) }
    LaunchedEffect(model, isDark) {
        if (model.isNullOrBlank()) {
            scheme = null
            return@LaunchedEffect
        }
        val seed = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(Size(CoverSchemeRequestSize, CoverSchemeRequestSize))
                .allowHardware(false)
                .build()
            // App-wide singleton (YoinApplication is the factory) — the seed
            // pixel read stays safe via the per-request allowHardware(false).
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            CoverSeedExtractor.extractSeedArgb(bitmap)
        }
        scheme = seed?.let { ExpressiveColorSchemeFactory.fromSeed(it, isDark) }
    }
    return scheme
}
