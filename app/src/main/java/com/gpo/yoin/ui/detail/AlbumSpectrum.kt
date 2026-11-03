package com.gpo.yoin.ui.detail

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import com.gpo.yoin.shared.detail.AlbumSpectrumBands
import kotlinx.coroutines.withContext
import kotlin.math.floor
import com.gpo.yoin.shared.detail.AlbumSpectrumColumns as SharedAlbumSpectrumColumns
import com.gpo.yoin.shared.detail.computeAlbumSpectrum as sharedComputeAlbumSpectrum
import com.gpo.yoin.shared.detail.contrastWithWhite as sharedContrastWithWhite
import com.gpo.yoin.shared.detail.fallbackAlbumSpectrum as sharedFallbackAlbumSpectrum
import com.gpo.yoin.shared.detail.lockTone as sharedLockTone

/*
 * The album spectrum (专辑色谱, owner 2026-10-09): when the cover leaves view
 * it breaks into vertical strips that sort themselves into bands, one band per
 * colour of the cover, each as wide as that colour's share of it. Every colour
 * keeps its hue and chroma but has its lightness squeezed into a band white
 * text always reads on (OKLab L 0.30–0.46, ≥ 7:1) — no blur, no scrim.
 *
 * The clustering and the tone lock live in :shared (com.gpo.yoin.shared.detail,
 * the same code the iOS app runs); this file loads the cover and turns the
 * ARGB bands into Compose colours.
 */

/** Strips the cover is cut into (and the bar is made of). */
internal const val AlbumSpectrumColumns = SharedAlbumSpectrumColumns

/**
 * One cover's spectrum: for each source column `x` of the cover, the bar slot
 * it travels to ([target]) and the tone-locked colour it becomes ([color]).
 * Slots are contiguous per colour, so the bar reads as bands.
 */
@Immutable
internal class AlbumSpectrum(
    val color: List<Color>,
    val target: IntArray,
) {
    val columns: Int get() = color.size

    /** The colour sitting at bar slot [slot]. */
    fun colorAtSlot(slot: Int): Color = color[target.indexOf(slot.coerceIn(0, columns - 1)).coerceAtLeast(0)]

    /** The colour at a fraction (0–1) along the bar. */
    fun colorAt(fraction: Float): Color = colorAtSlot(floor(fraction * columns).toInt())
}

/** A loaded cover for the bar: the spectrum, plus the bitmap its strips are cut from. */
@Immutable
internal class AlbumSpectrumSource(
    val spectrum: AlbumSpectrum,
    val image: ImageBitmap?,
)

private const val SpectrumRequestSize = 256

/**
 * Loads [model] off the main thread and builds its spectrum. Until it resolves
 * (and when there is no cover) the spectrum comes from [fallback] — the theme's
 * roles — so the bar always has colour.
 */
@Composable
internal fun rememberAlbumSpectrumSource(model: String?, fallback: List<Color>): AlbumSpectrumSource {
    val context = LocalContext.current
    val fallbackSource = remember(fallback) { AlbumSpectrumSource(fallbackAlbumSpectrum(fallback), image = null) }
    var loaded by remember(model) { mutableStateOf<AlbumSpectrumSource?>(null) }
    LaunchedEffect(model) {
        if (model.isNullOrBlank()) return@LaunchedEffect
        loaded = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(Size(SpectrumRequestSize, SpectrumRequestSize))
                .allowHardware(false)
                .build()
            val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                ?.image?.toBitmap() ?: return@withContext null
            val n = AlbumSpectrumColumns
            val small = bitmap.scale(n, n)
            val pixels = IntArray(n * n).also { small.getPixels(it, 0, n, 0, 0, n, n) }
            AlbumSpectrumSource(
                spectrum = computeAlbumSpectrum(pixels, n),
                image = bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap(),
            )
        }
    }
    return loaded ?: fallbackSource
}

/** A spectrum made of [colors] in equal bands, each column staying where it is. */
internal fun fallbackAlbumSpectrum(colors: List<Color>, columns: Int = AlbumSpectrumColumns): AlbumSpectrum =
    sharedFallbackAlbumSpectrum(colors.map { it.toArgb() }.toIntArray(), columns).toCompose()

/**
 * The spectrum of an [n]×[n] ARGB cover sample. Pure, deterministic: the same
 * pixels always give the same bands.
 */
internal fun computeAlbumSpectrum(pixels: IntArray, n: Int): AlbumSpectrum =
    sharedComputeAlbumSpectrum(pixels, n).toCompose()

private fun AlbumSpectrumBands.toCompose() = AlbumSpectrum(color = color.map { Color(it) }, target = target)

/**
 * Keep hue and chroma, put lightness in the spectrum's OKLab band (white text ≥ 7:1); ARGB in and out.
 */
internal fun lockTone(argb: Int): Int = sharedLockTone(argb)

/** WCAG contrast of white on [argb]. */
internal fun contrastWithWhite(argb: Int): Float = sharedContrastWithWhite(argb)
