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
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/*
 * The album spectrum (专辑色谱, owner 2026-10-09): when the cover leaves view
 * it breaks into vertical strips that sort themselves into bands, one band per
 * colour of the cover, each as wide as that colour's share of it. Every colour
 * keeps its hue and chroma but has its lightness squeezed into a band white
 * text always reads on (OKLab L 0.30–0.46, ≥ 7:1) — no blur, no scrim.
 */

/** Strips the cover is cut into (and the bar is made of). */
internal const val AlbumSpectrumColumns = 28

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
private const val Clusters = 5
private const val KMeansRounds = 10

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
internal fun fallbackAlbumSpectrum(colors: List<Color>, columns: Int = AlbumSpectrumColumns): AlbumSpectrum {
    val locked = colors.ifEmpty { listOf(Color.Gray) }.map { lockTone(it.toArgb()) }
    return AlbumSpectrum(
        color = List(columns) { x -> Color(locked[x * locked.size / columns]) },
        target = IntArray(columns) { it },
    )
}

/**
 * The spectrum of an [n]×[n] ARGB cover sample. Pure, deterministic: the same
 * pixels always give the same bands.
 */
internal fun computeAlbumSpectrum(pixels: IntArray, n: Int): AlbumSpectrum {
    require(pixels.size == n * n)
    val px = Array(pixels.size) { rgbToOklab(pixels[it]) }

    // Farthest-point seeds, then a few rounds of k-means.
    val centres = mutableListOf(px[px.size / 2])
    while (centres.size < Clusters) {
        var best = 0f
        var bestIndex = 0
        px.forEachIndexed { i, p ->
            val d = centres.minOf { distance(p, it) }
            if (d > best) {
                best = d
                bestIndex = i
            }
        }
        if (best < 0.002f) break
        centres += px[bestIndex]
    }
    val assign = IntArray(px.size)
    repeat(KMeansRounds) {
        px.forEachIndexed { i, p -> assign[i] = centres.indices.minBy { distance(p, centres[it]) } }
        centres.indices.forEach { c ->
            val members = px.indices.filter { assign[it] == c }
            if (members.isNotEmpty()) {
                centres[c] = FloatArray(3) { k -> members.sumOf { px[it][k].toDouble() }.toFloat() / members.size }
            }
        }
    }

    // Colours by share; a small colour survives only if it is clearly unlike the big ones.
    val kept = mutableListOf<Cluster>()
    centres.indices
        .map { c -> Cluster(centres[c], assign.count { it == c }.toFloat() / px.size) }
        .filter { it.share > 0.01f }
        .sortedByDescending { it.share }
        .forEach { cluster ->
            if (kept.isEmpty() || cluster.share >= 0.05f || kept.minOf { distance(cluster.lab, it.lab) } > 0.03f) {
                kept += cluster
            }
        }
    // A cover of one colour still gets two bands: the same hue, darker and lighter.
    if (kept.size == 1) {
        val only = kept.single()
        kept[0] = Cluster(floatArrayOf(only.lab[0] - 0.12f, only.lab[1], only.lab[2]), only.share / 2)
        kept += Cluster(floatArrayOf(only.lab[0] + 0.12f, only.lab[1], only.lab[2]), only.share / 2)
    }

    // Bands in hue order (neutrals first, dark to light); widths by share, at least two strips each.
    val order = kept.indices.sortedBy { kept[it].sortKey }
    val total = kept.sumOf { it.share.toDouble() }.toFloat()
    val raw = order.map { kept[it].share / total * n }.toFloatArray()
    val counts = raw.map { maxOf(2, floor(it).toInt()) }.toIntArray()
    while (counts.sum() < n) {
        val i = raw.indices.maxBy { raw[it] - counts[it] }
        counts[i]++
        raw[i] = -1f
    }
    while (counts.sum() > n) counts[counts.indices.maxBy { counts[it] }]--
    val slots = IntArray(n).also { s ->
        var at = 0
        order.forEachIndexed { j, cluster -> repeat(counts[j]) { s[at++] = cluster } }
    }

    // Each source column goes to the free slot whose colour it is nearest, keeping left-to-right order
    // as a tie-break; the columns that look most like a band choose first.
    val columnLab = Array(n) { x ->
        FloatArray(3) { k -> (0 until n).sumOf { y -> px[y * n + x][k].toDouble() }.toFloat() / n }
    }
    val free = (0 until n).toMutableList()
    val target = IntArray(n)
    val columnCluster = IntArray(n)
    (0 until n)
        .sortedBy { x -> kept.minOf { distance(columnLab[x], it.lab) } }
        .forEach { x ->
            val slot = free.minBy { s -> distance(columnLab[x], kept[slots[s]].lab) + 0.0004f * abs(s - x) }
            free.remove(slot)
            target[x] = slot
            columnCluster[x] = slots[slot]
        }
    val locked = kept.map { Color(lockTone(oklabToArgb(it.lab))) }
    return AlbumSpectrum(color = List(n) { locked[columnCluster[it]] }, target = target)
}

private class Cluster(val lab: FloatArray, val share: Float) {
    private val chroma = hypot(lab[1], lab[2])
    val sortKey: Float = if (chroma < 0.04f) -10f + lab[0] else atan2(lab[2], lab[1])
}

private fun distance(a: FloatArray, b: FloatArray): Float {
    val d0 = a[0] - b[0]
    val d1 = a[1] - b[1]
    val d2 = a[2] - b[2]
    return d0 * d0 + d1 * d1 + d2 * d2
}

// ---------------------------------------------------------------------------
// OKLab and the tone lock.
// ---------------------------------------------------------------------------

/** OKLab lightness band every spectrum colour is squeezed into (white text ≥ 7:1 on all of it). */
internal const val SpectrumLightnessMin = 0.30f
internal const val SpectrumLightnessMax = 0.46f
private const val SpectrumChromaBoost = 1.15f

/** White text on a spectrum band never drops below this (WCAG contrast ratio). */
internal const val SpectrumMinContrast = 7f
private const val SpectrumLightnessStep = 0.005f

/**
 * Keep hue and chroma, put lightness in [SpectrumLightnessMin]..[SpectrumLightnessMax]; ARGB in and out.
 * Gamut mapping and 8-bit rounding can leave a saturated light colour just short of [SpectrumMinContrast]
 * (e.g. 0xFFB5FFBE came out at 6.98:1), so the result is checked and stepped darker until it holds.
 */
internal fun lockTone(argb: Int): Int {
    val lab = rgbToOklab(argb)
    var l = SpectrumLightnessMin + (SpectrumLightnessMax - SpectrumLightnessMin) * lab[0].coerceIn(0f, 1f)
    val a = lab[1] * SpectrumChromaBoost
    val b = lab[2] * SpectrumChromaBoost
    var out = oklabToArgb(floatArrayOf(l, a, b))
    while (contrastWithWhite(out) < SpectrumMinContrast && l > SpectrumLightnessMin) {
        l = (l - SpectrumLightnessStep).coerceAtLeast(SpectrumLightnessMin)
        out = oklabToArgb(floatArrayOf(l, a, b))
    }
    return out
}

/** WCAG contrast of white on [argb]. */
private fun contrastWithWhite(argb: Int): Float {
    val luminance = 0.2126f * toLinear((argb shr 16) and 0xFF) +
        0.7152f * toLinear((argb shr 8) and 0xFF) +
        0.0722f * toLinear(argb and 0xFF)
    return 1.05f / (luminance + 0.05f)
}

private fun toLinear(channel: Int): Float {
    val v = channel / 255f
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

private fun toSrgb(linear: Float): Int {
    val v = linear.coerceIn(0f, 1f)
    val s = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
}

internal fun rgbToOklab(argb: Int): FloatArray {
    val r = toLinear((argb shr 16) and 0xFF)
    val g = toLinear((argb shr 8) and 0xFF)
    val b = toLinear(argb and 0xFF)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun oklabToLinear(lab: FloatArray): FloatArray {
    val l = (lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]).pow(3)
    val m = (lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]).pow(3)
    val s = (lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]).pow(3)
    return floatArrayOf(
        4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
    )
}

/** OKLab → sRGB, pulling chroma in until the colour fits the gamut. */
internal fun oklabToArgb(lab: FloatArray): Int {
    var a = lab[1]
    var b = lab[2]
    repeat(24) {
        val lin = oklabToLinear(floatArrayOf(lab[0], a, b))
        if (lin.all { it in -0.0005f..1.0005f }) {
            return (0xFF shl 24) or (toSrgb(lin[0]) shl 16) or (toSrgb(lin[1]) shl 8) or toSrgb(lin[2])
        }
        a *= 0.9f
        b *= 0.9f
    }
    val grey = oklabToLinear(floatArrayOf(lab[0], 0f, 0f))
    return (0xFF shl 24) or (toSrgb(grey[0]) shl 16) or (toSrgb(grey[1]) shl 8) or toSrgb(grey[2])
}
