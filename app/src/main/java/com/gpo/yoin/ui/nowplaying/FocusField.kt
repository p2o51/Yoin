package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Focus (tap the cover) paints the whole room in the cover's colours
 * (owner 2026-11-02, prototype "Yoin 渐变实验室", round 4): a full-screen
 * gradient of a few control points, each with its own colour, reach and
 * slow path. No blur — the colours are interpolated, not smeared.
 *
 *  - six points around the cover take their colour from the matching spot
 *    on the cover's rim (that spot slowly orbits, so colour creeps along it);
 *  - eight points around the room take turns over the cover's main hues,
 *    neighbours never the same, with a little of the matching corner mixed in;
 *  - one point in the lower corner holds the second hue;
 *  - one "wanderer" carries the cover's most vivid colour round the room.
 *
 * Chroma is lifted and lightness pinned into a band the screen's text reads
 * on (pale in light theme, deep in dark). On entry every point starts packed
 * into the cover and opens out with the focus spring.
 *
 * Pure Kotlin (ARGB ints, no android.*), JVM-tested in FocusFieldTest.
 */

/** The cover, reduced to what the focus field needs. */
@Immutable
class CoverField(
    /** [GridSize]² ARGB samples, row-major from the cover's top-left. */
    val grid: IntArray,
    /** The cover's main hues, most present first; always [PaletteSize] entries. */
    val palette: IntArray,
    /** The cover's most vivid colour. */
    val accent: Int,
) {
    /** The cover's colour at ([u], [v]) in 0..1, nearest sample. */
    fun at(u: Float, v: Float): Int {
        val x = (u * GridSize).toInt().coerceIn(0, GridSize - 1)
        val y = (v * GridSize).toInt().coerceIn(0, GridSize - 1)
        return grid[y * GridSize + x]
    }

    companion object {
        const val GridSize = 24
        const val PaletteSize = 4
    }
}

/**
 * Builds a [CoverField] from [pixels] (ARGB, [width] × [height]). Null when
 * there is nothing to read.
 */
fun buildCoverField(pixels: IntArray, width: Int, height: Int): CoverField? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val n = CoverField.GridSize
    val grid = IntArray(n * n)
    // Box-average each cell, so a small detail can't hijack a whole region.
    for (gy in 0 until n) {
        val y0 = gy * height / n
        val y1 = max(y0 + 1, (gy + 1) * height / n)
        for (gx in 0 until n) {
            val x0 = gx * width / n
            val x1 = max(x0 + 1, (gx + 1) * width / n)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0
            for (y in y0 until min(y1, height)) {
                for (x in x0 until min(x1, width)) {
                    val p = pixels[y * width + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
            }
            grid[gy * n + gx] = if (count == 0) {
                0xFF000000.toInt()
            } else {
                argb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
            }
        }
    }
    // Hue buckets weighted by chroma: the main hues, and the most vivid cell.
    val bucketWeight = FloatArray(HueBuckets)
    val bucketR = FloatArray(HueBuckets)
    val bucketG = FloatArray(HueBuckets)
    val bucketB = FloatArray(HueBuckets)
    var accent = grid[0]
    var bestChroma = -1f
    val hsl = FloatArray(3)
    for (c in grid) {
        argbToHsl(c, hsl)
        val chroma = hsl[1] * (1f - abs(2f * hsl[2] - 1f))
        if (chroma > bestChroma) {
            bestChroma = chroma
            accent = c
        }
        val weight = 0.15f + chroma
        val k = ((hsl[0] / (360f / HueBuckets)).toInt()).coerceIn(0, HueBuckets - 1)
        bucketWeight[k] += weight
        bucketR[k] += ((c shr 16) and 0xFF) * weight
        bucketG[k] += ((c shr 8) and 0xFF) * weight
        bucketB[k] += (c and 0xFF) * weight
    }
    val order = (0 until HueBuckets).filter { bucketWeight[it] > 0f }.sortedByDescending { bucketWeight[it] }
    val palette = IntArray(CoverField.PaletteSize) { i ->
        val k = order[min(i, order.lastIndex)]
        val w = bucketWeight[k]
        argb((bucketR[k] / w).roundToInt(), (bucketG[k] / w).roundToInt(), (bucketB[k] / w).roundToInt())
    }
    return CoverField(grid, palette, accent)
}

/** One control point of the field, in the field's own px. */
data class FieldPoint(val x: Float, val y: Float, val color: Int, val reach: Float, val weight: Float)

/**
 * The control points for one frame.
 *
 * [coverCenterX]/[coverCenterY]/[coverSize]: the cover as laid out in focus
 * (px); [width]/[height]: the field; [spread] 0 = every point packed into the
 * cover, 1 = opened out; [timeMs] drives the slow paths (hold it to freeze).
 */
fun focusFieldPoints(
    field: CoverField,
    dark: Boolean,
    width: Float,
    height: Float,
    coverCenterX: Float,
    coverCenterY: Float,
    coverSize: Float,
    spread: Float,
    timeMs: Float,
): List<FieldPoint> {
    val s = spread.coerceIn(0f, 1f)
    val open = 0.3f + 0.7f * s
    fun px(x: Float) = coverCenterX + (x - coverCenterX) * open
    fun py(y: Float) = coverCenterY + (y - coverCenterY) * open
    val points = ArrayList<FieldPoint>(16)
    // Around the cover: tight, nearly still; their sampling spots orbit the rim.
    for (i in 0 until 6) {
        val a = i / 6f * TAU + 0.4f
        val ua = a + timeMs / 16_000f
        val color = toneForRoom(field.at(0.5f + 0.4f * cos(ua), 0.5f + 0.4f * sin(ua)), dark)
        points += FieldPoint(
            x = px(coverCenterX + cos(a) * coverSize * 0.48f),
            y = py(coverCenterY + sin(a) * coverSize * 0.48f),
            color = color,
            reach = coverSize * 0.3f * open,
            weight = 1f,
        )
    }
    // Around the room: broad, slow drift; neighbours take different main hues.
    RoomAnchors.forEachIndexed { i, (fx, fy) ->
        val dx = sin(timeMs / (5_200f + i * 640f) + i) * coverSize * 0.09f
        val dy = cos(timeMs / (6_100f + i * 530f) + i * 1.7f) * coverSize * 0.1f
        val hue = field.palette[RoomHueOrder[i]]
        val near = field.at(fx.coerceIn(0.06f, 0.94f), fy.coerceIn(0.06f, 0.94f))
        points += FieldPoint(
            x = px(fx * width + dx),
            y = py(fy * height + dy),
            color = toneForRoom(mix(hue, near, 0.3f), dark, if (i % 2 == 1) -0.04f else 0.03f),
            reach = coverSize * 0.69f * open,
            weight = 0.9f,
        )
    }
    // The lower corner holds the second hue.
    points += FieldPoint(
        x = px(width * 0.23f + sin(timeMs / 8_800f) * coverSize * 0.12f),
        y = py(height * 0.81f + sin(timeMs / 6_100f + 1f) * coverSize * 0.28f),
        color = toneForRoom(field.palette[1], dark, if (dark) 0.06f else 0f),
        reach = coverSize * 0.34f * open,
        weight = 1.4f,
    )
    // The wanderer: the cover's most vivid colour, roaming.
    points += FieldPoint(
        x = px(width * (0.5f + 0.35f * sin(timeMs / 7_300f + 0.6f))),
        y = py(height * (0.52f + 0.33f * sin(timeMs / 9_700f + 2.2f))),
        color = toneForRoom(field.accent, dark, if (dark) 0.12f else -0.02f),
        reach = coverSize * 0.37f * open,
        weight = 2.2f,
    )
    return points
}

/** The wanderer's colour (the last point), for the dots around the room's edge. */
fun List<FieldPoint>.wandererColor(): Int = last().color

/**
 * Paints [points] into [out] ([gridWidth] × [gridHeight] ARGB), each cell the
 * Gaussian-weighted mix of the points at that cell's centre in a field of
 * [width] × [height] px. A faint [base] keeps cells far from every point
 * from going undefined.
 */
fun renderFocusField(
    points: List<FieldPoint>,
    base: Int,
    width: Float,
    height: Float,
    gridWidth: Int,
    gridHeight: Int,
    out: IntArray,
) {
    val cellW = width / gridWidth
    val cellH = height / gridHeight
    val n = points.size
    val xs = FloatArray(n) { points[it].x }
    val ys = FloatArray(n) { points[it].y }
    val inv = FloatArray(n) { 1f / (2f * points[it].reach * points[it].reach) }
    val ws = FloatArray(n) { points[it].weight }
    val rs = FloatArray(n) { ((points[it].color shr 16) and 0xFF).toFloat() }
    val gs = FloatArray(n) { ((points[it].color shr 8) and 0xFF).toFloat() }
    val bs = FloatArray(n) { (points[it].color and 0xFF).toFloat() }
    val baseR = ((base shr 16) and 0xFF) * BaseWeight
    val baseG = ((base shr 8) and 0xFF) * BaseWeight
    val baseB = (base and 0xFF) * BaseWeight
    // The Gaussian splits into x and y factors: one exp per point per column
    // and per row instead of per cell, which keeps a 30 fps field cheap.
    val ex = FloatArray(n * gridWidth)
    val ey = FloatArray(n * gridHeight)
    for (k in 0 until n) {
        for (i in 0 until gridWidth) {
            val dx = (i + 0.5f) * cellW - xs[k]
            ex[k * gridWidth + i] = exp(-dx * dx * inv[k])
        }
        for (j in 0 until gridHeight) {
            val dy = (j + 0.5f) * cellH - ys[k]
            ey[k * gridHeight + j] = ws[k] * exp(-dy * dy * inv[k])
        }
    }
    for (j in 0 until gridHeight) {
        for (i in 0 until gridWidth) {
            var r = baseR
            var g = baseG
            var b = baseB
            var w = BaseWeight
            for (k in 0 until n) {
                val wt = ey[k * gridHeight + j] * ex[k * gridWidth + i]
                r += rs[k] * wt
                g += gs[k] * wt
                b += bs[k] * wt
                w += wt
            }
            out[j * gridWidth + i] = argb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        }
    }
}

/** Keep the hue, lift the chroma, pin the lightness into the band text reads on. */
fun toneForRoom(color: Int, dark: Boolean, bias: Float = 0f): Int {
    val hsl = FloatArray(3)
    argbToHsl(color, hsl)
    val s = min(1f, hsl[1] * 1.4f + 0.1f)
    val l = ((if (dark) 0.13f + hsl[2] * 0.42f else 0.70f + hsl[2] * 0.24f) + bias).coerceIn(0f, 1f)
    return hslToArgb(hsl[0], s, l)
}

internal fun argbToHsl(color: Int, out: FloatArray) {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    var h = 0f
    var s = 0f
    if (mx != mn) {
        val d = mx - mn
        s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
    }
    out[0] = h
    out[1] = s
    out[2] = l
}

internal fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val a = s * min(l, 1f - l)
    fun f(n: Int): Float {
        val k = (n + h / 30f) % 12f
        return l - a * max(-1f, min(min(k - 3f, 9f - k), 1f))
    }
    return argb((f(0) * 255f).roundToInt(), (f(8) * 255f).roundToInt(), (f(4) * 255f).roundToInt())
}

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t).roundToInt()
    return argb(ch(16), ch(8), ch(0))
}

private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

private const val HueBuckets = 12
private const val BaseWeight = 0.02f
private const val TAU = (2.0 * Math.PI).toFloat()

/** Room points as fractions of the field: corners, edge midpoints, a touch outside. */
private val RoomAnchors = listOf(
    0f to 0f, 0.5f to -0.04f, 1f to 0f, 1.04f to 0.42f,
    1f to 0.98f, 0.5f to 1.04f, 0f to 0.98f, -0.04f to 0.42f,
)

/** Which main hue each room point takes: neighbours differ. */
private val RoomHueOrder = intArrayOf(0, 1, 0, 2, 3, 2, 1, 3)
