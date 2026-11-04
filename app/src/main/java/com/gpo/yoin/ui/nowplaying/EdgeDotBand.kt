package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Focus's edge dots, ordered (owner 2026-11-03, prototype v7e "整齐细点带"):
 * the scattered dots read as dust (random spots, random sizes, each moving on
 * its own). Here the dots sit on one fixed hex lattice and only their SIZE
 * changes: biggest at the screen edge, shrinking to nothing inward. The motion
 * lives in that gradient's inner edge — slow waves travel round the border.
 *
 *  - tinted by the cover's main colours by angle, very slowly rotating, over a
 *    faint wash of the same colours;
 *  - an entrance, not a fixture: it bursts out from the cover's side when focus
 *    opens (≈0.2 s to widest, dots a little bigger), settles within a second,
 *    recedes from 2 s and is gone at 8 s ("7~9 秒左右完全消失"); a song change
 *    in focus bursts it again, with a sweep from the left;
 *  - each new lyric line sends one soft swell out from the lyrics; pausing
 *    slows the waves and lets the band settle thinner.
 *
 * Also here: the transport keys' tap dots — a ring of lattice dots leaving the
 * tapped key's outline (leaning left for PREVIOUS, right for NEXT), gone in
 * about a second, on top of the background's own pulse.
 *
 * Times are the background clock's ms; lengths are dp × density.
 */

/** When the band was (re)born and what last stirred it. Plain fields: written by effects, read in draw. */
internal class EdgeBandState {
    var bornAtMs = -1e9f
        private set
    var linePulseAtMs = -1e9f
        private set
    var songPulseAtMs = -1e9f
        private set

    /** The waves' own clock (s): runs at 0.2× while paused. */
    var phase = 0f
        private set
    private var lastDrawMs = Float.NaN

    fun enter(nowMs: Float) {
        bornAtMs = nowMs
    }

    fun songChange(nowMs: Float) {
        bornAtMs = nowMs
        songPulseAtMs = nowMs
    }

    fun lineChange(nowMs: Float) {
        linePulseAtMs = nowMs
    }

    /** True while there is still something of the band to draw. */
    fun alive(nowMs: Float): Boolean = nowMs - bornAtMs < BandGoneS * 1000f

    fun advance(nowMs: Float, playing: Float) {
        val last = lastDrawMs
        lastDrawMs = nowMs
        if (last.isNaN() || nowMs <= last) return
        val dt = min(0.25f, (nowMs - last) / 1000f)
        phase += dt * (PausedWaveRate + (1f - PausedWaveRate) * playing)
    }
}

/** The band's life curve at [lifeS] seconds: (burst, fade), both 0..1. */
internal fun edgeBandLife(lifeS: Float): Pair<Float, Float> {
    val burst = when {
        lifeS < 0f -> 0f
        lifeS < BurstRiseS -> 1f - (1f - lifeS / BurstRiseS).pow(3)
        else -> exp(-(lifeS - BurstRiseS) * BurstDecay)
    }
    val fade = when {
        lifeS < BandRecedeS -> 1f
        lifeS >= BandGoneS -> 0f
        else -> 1f - smooth((lifeS - BandRecedeS) / (BandGoneS - BandRecedeS))
    }
    return burst to fade
}

/** Dots by colour layer and size bucket, drawn as round points. */
internal class DotLayers(private val layers: Int) {
    private val xy = Array(layers * Buckets) { FloatArray(256) }
    private val counts = IntArray(layers * Buckets)
    private val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    fun reset() = counts.fill(0)

    fun add(layer: Int, x: Float, y: Float, r: Float, rMax: Float) {
        val b = ((r / rMax) * Buckets).toInt().coerceIn(0, Buckets - 1)
        val i = layer * Buckets + b
        val n = counts[i]
        if (n * 2 + 2 > xy[i].size) xy[i] = xy[i].copyOf(xy[i].size * 2)
        xy[i][n * 2] = x
        xy[i][n * 2 + 1] = y
        counts[i] = n + 1
    }

    fun draw(canvas: android.graphics.Canvas, rMax: Float, colorOf: (layer: Int) -> Int) {
        for (layer in 0 until layers) {
            var any = false
            for (b in 0 until Buckets) if (counts[layer * Buckets + b] > 0) any = true
            if (!any) continue
            paint.color = colorOf(layer)
            for (b in 0 until Buckets) {
                val i = layer * Buckets + b
                if (counts[i] == 0) continue
                paint.strokeWidth = 2f * rMax * (b + 0.5f) / Buckets
                canvas.drawPoints(xy[i], 0, counts[i] * 2, paint)
            }
        }
    }

    private companion object {
        const val Buckets = 8
    }
}

/**
 * Focus's edge band. [focus] 0..1 (the stage), [playing] 0..1 (a spring),
 * [cover] and [lyrics] in this scope's px (the band grows out from the cover;
 * a line change swells out from the lyrics), [palette] the cover's four main
 * hues (ARGB).
 */
internal fun DrawScope.drawEdgeBand(
    state: EdgeBandState,
    dots: DotLayers,
    nowMs: Float,
    focus: Float,
    playing: Float,
    cover: Rect,
    lyrics: Offset?,
    palette: IntArray,
    primary: Color,
    dark: Boolean,
) {
    state.advance(nowMs, playing)
    val (burst, fade) = edgeBandLife((nowMs - state.bornAtMs) / 1000f)
    if (focus <= 0.01f || fade <= 0f || palette.isEmpty()) return
    val w = size.width
    val h = size.height
    val dp = density
    val tt = state.phase
    val short = min(w, h)
    val diag = hypot(w, h)
    val pitch = BandPitch * dp
    val rowStep = pitch * 0.866f
    val bandWidth = short * 0.15f * (1f + 1.4f * burst) * (0.25f + 0.75f * fade)
    val rMax = BandDotRadius * dp * (1f + 0.4f * burst)
    val corner = short * 0.09f
    val limit = bandWidth * 1.7f
    val ink = IntArray(palette.size) { i ->
        val c = Color(palette[i])
        (if (dark) lerp(c, Color.White, 0.35f) else lerp(c, primary, 0.55f)).toArgb()
    }

    // The faint wash under the band, so the dots sit on light.
    val washAlpha = (if (dark) 0.22f else 0.3f) * focus * fade
    val washDepth = bandWidth * 1.1f
    fun washColor(i: Int) = Color(palette[i % palette.size]).copy(alpha = washAlpha)
    drawRect(
        brush = Brush.verticalGradient(
            listOf(washColor(1), Color.Transparent),
            startY = 0f,
            endY = washDepth,
        ),
        size = Size(w, washDepth),
    )
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, washColor(0)),
            startY = h - washDepth,
            endY = h,
        ),
        topLeft = Offset(0f, h - washDepth),
        size = Size(w, washDepth),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(washColor(2), Color.Transparent),
            startX = 0f,
            endX = washDepth,
        ),
        size = Size(washDepth, h),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, washColor(3)),
            startX = w - washDepth,
            endX = w,
        ),
        topLeft = Offset(w - washDepth, 0f),
        size = Size(washDepth, h),
    )

    // A new lyric line: one soft swell out from the lyrics.
    val lp = (nowMs - state.linePulseAtMs) / 1000f
    val lineAmp = if (lp in 0f..1.6f) 0.38f * exp(-lp * 1.6f) else 0f
    val lineRadius = lp * 620f * dp
    val lineWidth2 = 5000f * dp * dp
    val origin = lyrics ?: Offset(w * 0.5f, h * 0.6f)
    // A new song: a sweep from the left.
    val sp = (nowMs - state.songPulseAtMs) / 1000f
    val songAmp = if (sp in 0f..1.4f) 0.55f * sin(PI.toFloat() * sp / 1.4f) else 0f
    val songX = sp * w * 0.9f
    val songWidth2 = 9000f * dp * dp
    val play = 0.78f + 0.22f * playing
    val cx = cover.center.x
    val cy = cover.center.y

    dots.reset()
    var j = 0
    while (j * rowStep <= h) {
        val y = j * rowStep
        val off = if (j and 1 == 1) pitch / 2f else 0f
        val ey = min(y, h - y)
        var i = 0
        while (i * pitch + off <= w) {
            val x = i * pitch + off
            val ex = min(x, w - x)
            if (ex > limit && ey > limit) {
                // Skip the room's middle in one step.
                i = max(i + 1, floor((w - limit - off) / pitch).toInt())
                continue
            }
            var e = min(ex, ey)
            if (ex < corner && ey < corner) e = corner - hypot(corner - ex, corner - ey)
            val th = atan2(y - h / 2f, x - w / 2f)
            val wave = (0.5f + 0.36f * sin(2f * th - tt * 0.35f) + 0.16f * sin(5f * th + tt * 0.25f + 1.1f))
                .coerceIn(0.08f, 1f)
            // Entry: grows out from the cover's side with a small overshoot.
            val ek = (focus * 1.8f - hypot(x - cx, y - cy) / diag * 0.8f).coerceIn(0f, 1f)
            val q = ek - 1f
            val grow = 1f + 2.1f * q * q * q + 1.1f * q * q
            var swell = 1f
            if (lineAmp > 0f) {
                val dd = hypot(x - origin.x, y - origin.y) - lineRadius
                swell += lineAmp * exp(-dd * dd / lineWidth2)
            }
            if (songAmp > 0f) {
                val dx = x - songX
                swell += songAmp * exp(-dx * dx / songWidth2)
            }
            val reach = max(1f, bandWidth * wave * grow * play * swell)
            var d = 1f - e / reach
            if (d > 0f) {
                d = d * d * (3f - 2f * d)
                val r = rMax * d
                if (r >= 0.25f * dp) {
                    val seg = floor(((th / (2f * PI.toFloat()) + 0.5f) + tt * 0.004f) * BandSegments).toInt()
                    dots.add(((seg % BandSegments) + BandSegments) % BandSegments, x, y, r, rMax)
                }
            }
            i++
        }
        j++
    }
    val alpha = (if (dark) 0.55f else 0.45f) * focus * min(1f, fade * 1.4f)
    dots.draw(drawContext.canvas.nativeCanvas, rMax) { k ->
        val u = k.toFloat() / BandSegments * ink.size
        val a = floor(u).toInt() % ink.size
        val f = u - floor(u)
        lerp(Color(ink[a]), Color(ink[(a + 1) % ink.size]), f).copy(alpha = alpha).toArgb()
    }
}

/** A tapped transport key: its bounds (root px), which way it leans, and when. */
internal data class TapDots(val boundsRoot: Rect, val lean: Int, val atUptimeMs: Long)

/**
 * The tap dots at [t] 0..1 of their life: a ring of lattice dots leaves the
 * key's outline ([key], this scope's px; a stadium), leaning toward [lean]
 * (−1 left, +1 right, 0 even).
 */
internal fun DrawScope.drawTapDots(dots: DotLayers, key: Rect, lean: Int, t: Float, color: Color) {
    if (t <= 0f || t >= 1f || key.isEmpty) return
    val dp = density
    val pitch = TapPitch * dp
    val rowStep = pitch * 0.866f
    val ring = TapReach * dp * (1f - (1f - t).pow(3))
    val width = (9f + 16f * t) * dp
    val amp = (1f - t).pow(1.4f)
    val rMax = TapDotRadius * dp
    val radius = min(key.width, key.height) / 2f
    val cx = key.center.x
    val cy = key.center.y
    val halfStraight = max(0f, key.width / 2f - radius)
    val halfStraightY = max(0f, key.height / 2f - radius)
    val reach = TapReach * dp * 1.75f + width * 2f + 4f * dp
    val left = key.left - reach
    val top = key.top - reach
    val right = key.right + reach
    val bottom = key.bottom + reach
    dots.reset()
    var j = floor(top / rowStep).toInt()
    while (j * rowStep <= bottom) {
        val y = j * rowStep
        val off = if (j and 1 == 1) pitch / 2f else 0f
        var i = floor((left - off) / pitch).toInt()
        while (i * pitch + off <= right) {
            val x = i * pitch + off
            i++
            // Signed distance to the stadium.
            val qx = max(0f, abs(x - cx) - halfStraight)
            val qy = max(0f, abs(y - cy) - halfStraightY)
            val sd = hypot(qx, qy) - radius
            if (sd <= 0f) continue
            val ang = atan2(y - cy, x - cx)
            val leanBy = if (lean != 0) max(0.2f, 1f + 0.75f * lean * cos(ang)) else 1f
            val rr = ring * leanBy + 3f * dp * sin(5f * ang + 9f * t)
            val g = (sd - rr) / width
            val d = amp * exp(-g * g)
            val r = rMax * d
            if (r >= 0.25f * dp) dots.add(0, x, y, r, rMax)
        }
        j++
    }
    dots.draw(drawContext.canvas.nativeCanvas, rMax) { color.toArgb() }
}

private fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private const val BandPitch = 5.4f
private const val BandDotRadius = 1.2f
private const val BandSegments = 12
private const val BurstRiseS = 0.22f
private const val BurstDecay = 3.2f
private const val BandRecedeS = 2f
internal const val BandGoneS = 8f
private const val PausedWaveRate = 0.2f

private const val TapPitch = 4.6f
private const val TapReach = 72f
private const val TapDotRadius = 1.7f

/** The tap dots' life. */
internal const val TapDotsMs = 1050
