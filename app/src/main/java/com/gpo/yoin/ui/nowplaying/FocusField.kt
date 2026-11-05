package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Immutable
import hct.Hct
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

/**
 * Dark focus's room from Now Playing's dark scheme: the hues of [primary],
 * [secondary] and [tertiary], held dark enough for white lyrics on top but
 * pushed to full colour, so focus reads richer than the page it came from
 * (owner 2026-11-04: "聚焦之后反而变得死气沉沉了"). The 2025 expressive
 * scheme's own dark containers sit at tone 75-93, far too bright for white
 * text; its surfaces are grey. The fourth colour is a deep blend of the
 * primary and tertiary hues instead of a surface, and the wanderer is the
 * brightest point, a lit primary moving through the room.
 */
fun darkRoomOf(primary: Int, secondary: Int, tertiary: Int): RoomPalette {
    val p = Hct.fromInt(primary)
    val s = Hct.fromInt(secondary)
    val t = Hct.fromInt(tertiary)
    fun at(hue: Double, chroma: Double, tone: Double): Int = Hct.from(hue, chroma, tone).toInt()
    val between = midHue(p.hue, t.hue)
    return RoomPalette(
        colors = intArrayOf(
            at(p.hue, max(p.chroma, DarkRoomMinChroma), 33.0),
            at(s.hue, max(s.chroma, DarkRoomMinChroma * 0.8), 23.0),
            at(t.hue, max(t.chroma, DarkRoomMinChroma), 33.0),
            at(between, DarkRoomMinChroma, 12.0),
        ),
        accent = at(p.hue, max(p.chroma, DarkRoomMinChroma * 1.6), 49.0),
    )
}

/** The hue halfway round the shorter way from [a] to [b], in degrees. */
private fun midHue(a: Double, b: Double): Double {
    val d = ((b - a + 540.0) % 360.0) - 180.0
    return (a + d / 2.0 + 360.0) % 360.0
}

// Focus tuned 2026-11-05 (owner, prototype "Yoin 聚焦新意"): tones a step
// apart (33 / 23 / 12, wanderer 49) and more chroma, still ≥ 8:1 under
// white (≥ 4.5:1 for the wanderer).
private const val DarkRoomMinChroma = 48.0

/**
 * A ready-made room palette: the theme's own colours (ARGB), used as they
 * are instead of toning the cover's. Dark focus paints the room in Now
 * Playing's dark scheme (owner 2026-11-03: "把现在默认的深色配色，做成全屏听歌的渐变").
 * [colors] holds four, most important first; [accent] is the wanderer.
 */
@Immutable
class RoomPalette(val colors: IntArray, val accent: Int) {
    init {
        require(colors.size == 4) { "RoomPalette takes four colours" }
    }

    /** The colour [phase] of the way round the four (wraps), for the rim's creep. */
    fun around(phase: Float): Int {
        val x = ((phase % 4f) + 4f) % 4f
        val i = x.toInt().coerceIn(0, 3)
        return mix(colors[i], colors[(i + 1) % 4], x - i)
    }
}

/** One control point of the field, in the field's own px. */
data class FieldPoint(val x: Float, val y: Float, val color: Int, val reach: Float, val weight: Float)

/**
 * The control points for one frame.
 *
 * [coverCenterX]/[coverCenterY]/[coverSize]: the cover as laid out in focus
 * (px); [width]/[height]: the field; [spread] 0 = every point packed into the
 * cover, 1 = opened out; [timeMs] drives the slow paths (hold it to freeze).
 * With a [room] palette the colours come from it as they are (same points,
 * same motion); without one they are the cover's, toned by [toneForRoom].
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
    room: RoomPalette? = null,
): List<FieldPoint> {
    val s = spread.coerceIn(0f, 1f)
    val open = 0.3f + 0.7f * s
    // The owner's 「明显」: every path FocusDriftSpeed× faster, FocusDriftReach× wider.
    val time = timeMs * FocusDriftSpeed
    val reach = FocusDriftReach
    fun px(x: Float) = coverCenterX + (x - coverCenterX) * open
    fun py(y: Float) = coverCenterY + (y - coverCenterY) * open
    val points = ArrayList<FieldPoint>(16)
    // Around the cover: tight, nearly still; their sampling spots orbit the rim.
    for (i in 0 until 6) {
        val a = i / 6f * TAU + 0.4f
        val ua = a + time / 16_000f
        val color = room?.around(ua / TAU * 4f)
            ?: toneForRoom(field.at(0.5f + 0.4f * cos(ua), 0.5f + 0.4f * sin(ua)), dark)
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
        val dx = sin(time / (5_200f + i * 640f) + i) * coverSize * 0.09f * reach
        val dy = cos(time / (6_100f + i * 530f) + i * 1.7f) * coverSize * 0.1f * reach
        val hue = field.palette[RoomHueOrder[i]]
        val near = field.at(fx.coerceIn(0.06f, 0.94f), fy.coerceIn(0.06f, 0.94f))
        points += FieldPoint(
            x = px(fx * width + dx),
            y = py(fy * height + dy),
            color = room?.colors?.get(RoomHueOrder[i])
                ?: toneForRoom(mix(hue, near, 0.3f), dark, if (i % 2 == 1) -0.07f else 0.05f),
            reach = coverSize * 0.69f * open,
            weight = 0.9f,
        )
    }
    // The lower corner holds the second hue.
    points += FieldPoint(
        x = px(width * 0.23f + sin(time / 8_800f) * coverSize * 0.12f * reach),
        y = py(height * 0.81f + sin(time / 6_100f + 1f) * coverSize * 0.28f * min(reach, 1.6f)),
        color = room?.colors?.get(1) ?: toneForRoom(field.palette[1], dark, if (dark) 0.06f else 0f),
        reach = coverSize * 0.34f * open,
        weight = 1.4f,
    )
    // The wanderer: the cover's most vivid colour, roaming (wider, but kept on screen).
    val roam = kotlin.math.sqrt(reach)
    points += FieldPoint(
        x = px(width * (0.5f + min(0.46f, 0.35f * roam) * sin(time / 7_300f + 0.6f))),
        y = py(height * (0.52f + min(0.44f, 0.33f * roam) * sin(time / 9_700f + 2.2f))),
        color = room?.accent ?: toneForRoom(field.accent, dark, if (dark) 0.12f else -0.02f),
        reach = coverSize * 0.37f * open,
        weight = 2.2f,
    )
    return points
}

/**
 * [to]'s points with each colour [t] of the way from [from]'s matching point:
 * a song change in focus keeps the geometry and crosses the colours over.
 * Both must come from [focusFieldPoints] with the same layout arguments.
 *
 * With [fromRight] set, the new colours sweep in from that side instead of
 * landing everywhere at once (owner 2026-11-05: a skip forward reads right
 * to left, a skip back left to right); [width] is the field's width.
 */
fun crossFieldColors(
    from: List<FieldPoint>,
    to: List<FieldPoint>,
    t: Float,
    fromRight: Boolean? = null,
    width: Float = 0f,
): List<FieldPoint> {
    if (from.size != to.size) return to
    val k = t.coerceIn(0f, 1f)
    return to.mapIndexed { i, p ->
        val local = if (fromRight == null) k else sweepAt(p.x, width, k, fromRight)
        p.copy(color = mix(from[i].color, p.color, local))
    }
}

/**
 * A front sweeping across a field [width] wide as [t] goes 0 → 1, entering
 * from the right ([fromRight]) or the left: how far it has passed a point at
 * [x]. 0 everywhere at the start, 1 everywhere at the end; the front itself
 * is [front] of the width, smoothstepped.
 */
fun sweepAt(x: Float, width: Float, t: Float, fromRight: Boolean, front: Float = SweepFront): Float {
    val xn = if (width > 0f) (x / width).coerceIn(0f, 1f) else 0.5f
    val fromEntry = if (fromRight) 1f - xn else xn
    val u = ((t.coerceIn(0f, 1f) * (1f + front) - fromEntry) / front).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/** A sweep's soft front, as a share of the field's width: narrow enough to read as moving. */
internal const val SweepFront = 0.3f

/**
 * A song's colour journey (owner 2026-11-05, 「一首歌一段色程」): over the
 * song, the room moves from the cover's main hue toward its second, entering
 * from the side the song came from — the right after a skip forward (or an
 * auto-advance), the left after a skip back — and covering the room by the
 * end. This is the field the room travels to: main hues turned one step.
 */
fun CoverField.journeyed(): CoverField = CoverField(grid, journeyOrder(palette, candidates = 3), accent)

/** [CoverField.journeyed] for a ready-made room palette (its fourth, the deep blend, stays put). */
fun RoomPalette.journeyed(): RoomPalette = RoomPalette(journeyOrder(colors, candidates = 2), accent)

/**
 * [colors] with the one of its next [candidates] whose hue lies furthest from
 * the first moved to the front, the rest in order behind it: the journey's
 * "second colour" is the one that reads as a change, not a near twin of the
 * first (owner's prototype: a peach cover journeying to peach showed nothing).
 */
internal fun journeyOrder(colors: IntArray, candidates: Int): IntArray {
    val hsl = FloatArray(3)
    argbToHsl(colors[0], hsl)
    val firstHue = hsl[0]
    var best = 1
    var bestDistance = -1f
    for (i in 1..candidates.coerceAtMost(colors.size - 1)) {
        argbToHsl(colors[i], hsl)
        // A grey has no hue to travel to: weigh the distance by saturation.
        val d = hueDistance(firstHue, hsl[0]) * hsl[1]
        if (d > bestDistance) {
            bestDistance = d
            best = i
        }
    }
    val out = IntArray(colors.size)
    out[0] = colors[best]
    var j = 1
    for (i in colors.indices) if (i != best) out[j++] = colors[i]
    return out
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/**
 * How far the journey has reached a point at [x] of [width] when the song is
 * [progress] (0..1) through: 0 at the start everywhere, the entry side first,
 * all of the room by the end. Smoothstepped, no hard front.
 */
fun journeyAt(x: Float, width: Float, progress: Float, fromRight: Boolean): Float {
    val xn = if (width > 0f) (x / width).coerceIn(0f, 1f) else 0.5f
    val fromEntry = if (fromRight) 1f - xn else xn
    val t = ((progress.coerceIn(0f, 1f) * (1f + SweepFront) - fromEntry) / SweepFront).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * [base]'s points with each colour moved toward [toward]'s matching point by
 * [JourneyMaxMix] × [journeyAt] its x. Both from [focusFieldPoints] with the
 * same layout arguments (the second on the [journeyed] field).
 */
fun journeyFieldColors(
    base: List<FieldPoint>,
    toward: List<FieldPoint>,
    width: Float,
    progress: Float,
    fromRight: Boolean,
): List<FieldPoint> {
    if (base.size != toward.size || progress <= 0f) return base
    return base.mapIndexed { i, p ->
        val k = JourneyMaxMix * journeyAt(p.x, width, progress, fromRight)
        if (k <= 0f) p else p.copy(color = mix(p.color, toward[i].color, k))
    }
}

/** How much of the second hue the room holds at the song's end. */
internal const val JourneyMaxMix = 0.85f

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
    val s = min(1f, hsl[1] * 1.6f + 0.12f)
    var l = ((if (dark) DarkRoomFloor + hsl[2] * DarkRoomSpan else LightRoomFloor + hsl[2] * LightRoomSpan) + bias)
        .coerceIn(0f, 1f)
    // HSL lightness flatters yellows and greens: in the dark room, step them
    // down until the light text over them still reads.
    var out = hslToArgb(hsl[0], s, l)
    while (dark && l > 0.05f && relativeLuminance(out) > DarkRoomMaxLuminance) {
        l -= 0.02f
        out = hslToArgb(hsl[0], s, l)
    }
    return out
}

private fun relativeLuminance(color: Int): Float {
    fun channel(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    return 0.2126f * channel((color shr 16) and 0xFF) +
        0.7152f * channel((color shr 8) and 0xFF) +
        0.0722f * channel(color and 0xFF)
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

/** Light text keeps ~4.5:1 over the dark room's brightest point. */
// Dark room: deeper than the lab's (owner 2026-11-03: "深色全屏播放页面有点亮").
internal const val DarkRoomFloor = 0.10f

/** Light room's lightness band (owner 2026-11-05: a step deeper than the old 0.70–0.94). */
internal const val LightRoomFloor = 0.64f
internal const val LightRoomSpan = 0.24f

/** Focus motion (owner 2026-11-05, 「明显」): paths this many times faster… */
internal const val FocusDriftSpeed = 3f

/** …and this many times wider. */
internal const val FocusDriftReach = 2.2f
internal const val DarkRoomSpan = 0.34f
private const val DarkRoomMaxLuminance = 0.065f
private const val TAU = (2.0 * Math.PI).toFloat()

/** Room points as fractions of the field: corners, edge midpoints, a touch outside. */
private val RoomAnchors = listOf(
    0f to 0f, 0.5f to -0.04f, 1f to 0f, 1.04f to 0.42f,
    1f to 0.98f, 0.5f to 1.04f, 0f to 0.98f, -0.04f to 0.42f,
)

/** Which main hue each room point takes: neighbours differ. */
private val RoomHueOrder = intArrayOf(0, 1, 0, 2, 3, 2, 1, 3)
