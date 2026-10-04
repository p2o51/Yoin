package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halftone geometry shared by the AGSL mask and the Path fallback
 * (dissolve-final §1.1, §1.2, §1.7). A staggered lattice anchored to the
 * element; every dot is evaluated against each active seam and keeps the
 * smallest radius (and that seam's swirl):
 *
 *  - [Top]: curve C under fixed chrome — the Dots top seam, one of the
 *    user's choices ([SeamTopStyle.Dots]). The radius follows the dot's
 *    distance below the seam (zero at the seam, whole at band + front).
 *  - [Tail]: the bottom field. Content opens up over the approach, runs on
 *    under the bar as a still lattice to the screen's bottom edge, and dots
 *    whose lightness is close to the bar's give way next to it.
 *
 * Everything that is not a function of position alone — the ragged front and
 * the swirl — is multiplied by the disorder D ([seamDisorder]); the swirl is
 * phased by position plus the viewport's flow lag, never by time, so at rest
 * the print is still (and, with D = 0, perfectly regular).
 */
internal object SeamHalftone {
    class Lattice(val columns: Int, val cellWidth: Float, val rowHeight: Float, val fullRadius: Float)

    fun lattice(width: Float, pitch: Float): Lattice {
        val columns = max(4, (width / pitch).roundToInt())
        val cellWidth = width / columns
        val rowHeight = cellWidth * .866f
        return Lattice(columns, cellWidth, rowHeight, hypot(cellWidth, rowHeight) * .53f)
    }

    /** Curve C seam, in the element's own px; [band] (G) and [front] (F) already revealed. */
    class Top {
        var seam = 0f
        var band = 0f
        var front = 0f

        /** The flow's lag behind the content, in bands. */
        var flowShift = 0f
        val solid: Float get() = band + front
    }

    /** The bottom field, in the element's own px (screen geometry translated by the element's origin). */
    class Tail {
        var y0 = 0f
        var yb = 0f
        var len = 0f

        /** The screen's bottom edge. */
        var end = 0f
        var guard = 0f
        var k0 = 0f
        var k1 = 0f
        var front = 0f
        var frontNear = 0f

        /** The flow's lag behind the content, in [SeamDissolveTokens.FieldFlowScale] units. */
        var flowShift = 0f

        /** Field presence (0..1): fades the whole field out while the bar is away. */
        var reveal = 1f
        var barLeft = 0f
        var barTop = 0f
        var barRight = 0f
        var barBottom = 0f
        var barRadius = 0f
        var barLum = 0f
        var bgLum = 0f
        var yieldReach = 0f
        var yieldInset = 0f

        /** The 退色 ramp's far end: the screen's bottom edge plus a little overshoot. */
        var fadeEnd = 0f

        /** How far the dots have eased toward the page colour at [y] (before [reveal]). */
        fun fadeAt(y: Float): Float = when {
            y <= y0 -> 0f
            y < barTop -> SeamDissolveTokens.FieldFadeAtBar * (y - y0) / max(1f, barTop - y0)
            else -> {
                val depth = ((y - barTop) / max(1f, fadeEnd - barTop)).coerceIn(0f, 1f)
                SeamDissolveTokens.FieldFadeAtBar +
                    (SeamDissolveTokens.FieldFadeAtEdge - SeamDissolveTokens.FieldFadeAtBar) * depth
            }
        }

        /** Signed distance from ([x], [y]) to the bar's rounded rectangle (negative inside). */
        fun barDistance(x: Float, y: Float): Float {
            val halfWidth = (barRight - barLeft) * .5f
            val halfHeight = (barBottom - barTop) * .5f
            val radius = min(barRadius, min(halfWidth, halfHeight))
            val qx = abs(x - (barLeft + halfWidth)) - (halfWidth - radius)
            val qy = abs(y - (barTop + halfHeight)) - (halfHeight - radius)
            return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
        }
    }

    /** Per-dot front offset: the item's bend plus the dot's own jitter, about ±0.22. */
    fun bendJitter(u: Float, column: Int, row: Int, seed: Float): Float {
        val bend = .10f * sin((u * .9f + seed * .37f) * TAU) + .05f * sin((u * 2.3f - seed * .21f + .3f) * TAU)
        val jitter = (hash(column + seed * 31f, row + seed * 7f) - .5f) * .14f
        return bend + jitter
    }

    /**
     * Writes the dot's displaced centre (x, y) and radius into [out]. Returns
     * false when no seam touches it — a whole dot on its lattice site. [lum]
     * is the content's CIELAB L* at the dot's site (NaN when unknown or
     * transparent); only the tail's lightness yield reads it.
     */
    fun dot(
        lattice: Lattice,
        width: Float,
        height: Float,
        column: Int,
        row: Int,
        seed: Float,
        disorder: Float,
        top: Top?,
        tail: Tail?,
        lum: Float,
        out: FloatArray,
    ): Boolean {
        val stagger = if (row and 1 == 0) 0f else .5f
        val lx = (column + .5f + stagger) * lattice.cellWidth
        val ly = (row + .5f) * lattice.rowHeight
        val u = lx / width
        val v = ly / height
        val bendJitter = bendJitter(u, column, row, seed)
        var radius = lattice.fullRadius
        var envelope = 0f
        var flow = 0f
        var touched = false
        if (top != null) {
            val below = ly - top.seam
            if (below < top.solid) {
                val near = smoothstep((below / (top.band * .35f)).coerceIn(0f, 1f))
                val shifted = below + (bendJitter / .22f) * top.front * near * disorder
                val t = (shifted / top.band).coerceIn(0f, 1f)
                radius = if (t <= 0f) {
                    0f
                } else {
                    SeamDissolveTokens.DotRadius * lattice.cellWidth * t.pow(SeamDissolveTokens.DotGamma)
                }
                envelope = sin(t * PI.toFloat()) * sqrt(1f - t) * disorder
                flow = shifted / top.band + top.flowShift
                touched = true
            }
        }
        if (tail != null) {
            var tailRadius = lattice.fullRadius
            var tailEnvelope = 0f
            var tailFlow = 0f
            var tailTouched = false
            val fromStart = ly - tail.y0
            if (fromStart > 0f) {
                var approach = 1f
                val k = if (ly < tail.yb && tail.len > .5f) {
                    val front = (bendJitter / .22f) * tail.front *
                        smoothstep((fromStart / tail.frontNear).coerceIn(0f, 1f)) * disorder
                    approach = ((fromStart + front) / tail.len).coerceIn(0f, 1f)
                    tailEnvelope = sin(approach * PI.toFloat()) * disorder
                    lerp(SeamDissolveTokens.FieldApproachRadius, tail.k0, smoothstep(approach))
                } else {
                    lerp(tail.k0, tail.k1, ((ly - tail.yb) / max(1f, tail.end - tail.yb)).coerceIn(0f, 1f))
                }
                tailRadius = lerp(lattice.fullRadius, k * lattice.cellWidth, tail.reveal)
                tailEnvelope *= tail.reveal
                tailFlow = approach * 2f + tail.flowShift
                tailTouched = true
            }
            if (!lum.isNaN()) {
                val toBar = tail.barDistance(lx, ly)
                if (toBar < tail.yieldReach + tail.yieldInset) {
                    val yielded = tailRadius * yieldFactor(tail, lum, ly, toBar)
                    if (yielded < tailRadius) {
                        tailRadius = yielded
                        tailTouched = true
                    }
                }
            }
            if (tailTouched && (!touched || tailRadius < radius)) {
                radius = tailRadius
                envelope = tailEnvelope
                flow = tailFlow
                touched = true
            }
        }
        out[0] = lx + lattice.cellWidth * .42f * sin((v * 1.4f + u * .45f - flow * .8f) * TAU) * envelope
        out[1] = ly + lattice.rowHeight * .38f * sin((v * .7f - u * 1.3f + flow * .65f) * TAU) * envelope
        out[2] = radius
        return touched
    }

    /**
     * 按亮度让位: a dot whose lightness (after 退色) is within reach of the bar's
     * shrinks to nothing [Tail.yieldInset] from the bar's edge and is whole
     * again [Tail.yieldReach] further out. ΔL* ≤ 6 yields fully, ≥ 18 not at all.
     */
    fun yieldFactor(tail: Tail, lum: Float, y: Float, toBar: Float): Float {
        val seen = lerp(lum, tail.bgLum, tail.fadeAt(y) * tail.reveal)
        val span = SeamDissolveTokens.YieldNoneDelta - SeamDissolveTokens.YieldFullDelta
        val apart = ((abs(seen - tail.barLum) - SeamDissolveTokens.YieldFullDelta) / span).coerceIn(0f, 1f)
        val need = (1f - smoothstep(apart)) * tail.reveal
        if (need <= 0f) return 1f
        return lerp(1f, smoothstep(((toBar - tail.yieldInset) / tail.yieldReach).coerceIn(0f, 1f)), need)
    }

    /** CIELAB L* (0–100) of [color]: the tone axis M3's palettes are built on (HCT tone = L*). */
    fun lightness(color: Color): Float {
        val srgb = color.convert(ColorSpaces.Srgb)
        return lightness(srgb.red, srgb.green, srgb.blue)
    }

    fun lightness(red: Float, green: Float, blue: Float): Float {
        val y = .2126f * linear(red) + .7152f * linear(green) + .0722f * linear(blue)
        return if (y > .008856f) 116f * cbrt(y) - 16f else 903.3f * y
    }

    private fun linear(channel: Float): Float =
        if (channel <= .04045f) channel / 12.92f else ((channel + .055f) / 1.055f).pow(2.4f)

    /** Field dot radius, in cells, for a lattice coverage (the share of the area the dots print). */
    fun radiusForCoverage(coverage: Float): Float = sqrt(coverage * .866f / PI.toFloat())

    private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)

    private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

    private fun hash(x: Float, y: Float): Float {
        val value = sin(x * 127.1f + y * 311.7f) * 43758.547f
        return value - floor(value)
    }

    private const val TAU = (PI * 2).toFloat()

    /**
     * Mirrors [dot] per pixel. Each pixel walks the 4×4 lattice neighbourhood
     * (enough for the widest dot plus its swirl), keeps the dots that can
     * reach it and unions them by max coverage. Only a dot that can cover the
     * pixel and sits within the yield's reach of the bar samples the content a
     * second time — at its lattice site, so the whole dot shares one verdict.
     * The 退色 ramp is mixed into the dots' own pixels (never into text, which
     * is not drawn here).
     */
    val AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float2 cell;
        uniform float seed;
        uniform float disorder;
        uniform float minRadius;
        uniform float topOn;
        uniform float seam;
        uniform float band;
        uniform float front;
        uniform float topFlow;
        uniform float tailOn;
        uniform float tailY0;
        uniform float tailYb;
        uniform float tailLen;
        uniform float tailEnd;
        uniform float tailGuard;
        uniform float2 tailK;
        uniform float2 tailFront;
        uniform float tailFlow;
        uniform float tailReveal;
        uniform float4 bar;
        uniform float barRadius;
        uniform float2 lums;
        uniform float2 yieldBand;
        uniform float fadeEnd;
        layout(color) uniform half4 page;

        const float TAU = 6.2831853;
        const float PI = 3.1415927;

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.547);
        }

        float barDistance(float2 p) {
            float2 extent = (bar.zw - bar.xy) * 0.5;
            float radius = min(barRadius, min(extent.x, extent.y));
            float2 q = abs(p - (bar.xy + extent)) - (extent - radius);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }

        float fadeAt(float y) {
            if (y <= tailY0) return 0.0;
            if (y < bar.y) return ${SeamDissolveTokens.FieldFadeAtBar} * (y - tailY0) / max(1.0, bar.y - tailY0);
            return ${SeamDissolveTokens.FieldFadeAtBar} +
                ${SeamDissolveTokens.FieldFadeAtEdge - SeamDissolveTokens.FieldFadeAtBar} *
                clamp((y - bar.y) / max(1.0, fadeEnd - bar.y), 0.0, 1.0);
        }

        float toLinear(float c) {
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
        }

        float lightness(half3 rgb) {
            float y = 0.2126 * toLinear(rgb.r) + 0.7152 * toLinear(rgb.g) + 0.0722 * toLinear(rgb.b);
            return y > 0.008856 ? 116.0 * pow(y, 1.0 / 3.0) - 16.0 : 903.3 * y;
        }

        half4 main(float2 xy) {
            float reach = cell.y * 2.0;
            bool nearTop = topOn > 0.5 && xy.y < seam + band + front + reach;
            bool nearTail = tailOn > 0.5 && xy.y > min(tailY0, tailGuard) - reach;
            if (!nearTop && !nearTail) return content.eval(xy);
            if (topOn > 0.5 && xy.y < seam - reach) return half4(0.0);
            // Dots only leave their sites in the top band and the approach, and
            // only while scrolling. Elsewhere no dot reaches past its two
            // nearest rows and columns, so a 2×2 walk is enough.
            bool swirl = disorder > 0.0 && (nearTop ||
                (tailOn > 0.5 && xy.y > tailY0 - reach && xy.y < tailYb + reach));
            int lo = swirl ? -1 : 0;
            int hi = swirl ? 2 : 1;
            float fullRadius = length(cell) * 0.53;
            float coverage = 0.0;
            float rowBase = floor(xy.y / cell.y - 0.5);
            for (int i = -1; i <= 2; i++) {
                if (i < lo || i > hi) continue;
                float row = rowBase + float(i);
                float stagger = mod(row, 2.0) >= 1.0 ? 0.5 : 0.0;
                float ly = (row + 0.5) * cell.y;
                float v = ly / size.y;
                float columnBase = floor(xy.x / cell.x - 0.5 - stagger);
                // Only rows beside the bar can be within the yield's reach.
                bool yieldRow = tailOn > 0.5 &&
                    ly > bar.y - yieldBand.x - yieldBand.y && ly < bar.w + yieldBand.x + yieldBand.y;
                for (int j = -1; j <= 2; j++) {
                    if (j < lo || j > hi) continue;
                    float column = columnBase + float(j);
                    float lx = (column + 0.5 + stagger) * cell.x;
                    float u = lx / size.x;
                    float below = ly - seam;
                    bool topBand = topOn > 0.5 && below < band + front;
                    float fromStart = ly - tailY0;
                    bool approachRow = tailOn > 0.5 && fromStart > 0.0 && ly < tailYb && tailLen > 0.5;
                    // The front's bend and jitter only exist while scrolling, and
                    // only for dots inside a band.
                    float bendJitter = 0.0;
                    if (disorder > 0.0 && ((topBand && below > 0.0) || approachRow)) {
                        bendJitter = 0.10 * sin((u * 0.9 + seed * 0.37) * TAU)
                            + 0.05 * sin((u * 2.3 - seed * 0.21 + 0.3) * TAU)
                            + (hash(float2(column + seed * 31.0, row + seed * 7.0)) - 0.5) * 0.14;
                    }
                    float radius = fullRadius;
                    float envelope = 0.0;
                    float flow = 0.0;
                    bool touched = false;
                    if (topOn > 0.5) {
                        if (topBand) {
                            float near = smoothstep(0.0, 1.0, clamp(below / (band * 0.35), 0.0, 1.0));
                            float shifted = below + (bendJitter / 0.22) * front * near * disorder;
                            float t = clamp(shifted / band, 0.0, 1.0);
                            radius = t <= 0.0 ? 0.0
                                : ${SeamDissolveTokens.DotRadius} * cell.x * pow(t, ${SeamDissolveTokens.DotGamma});
                            envelope = disorder > 0.0 ? sin(t * PI) * sqrt(1.0 - t) * disorder : 0.0;
                            flow = shifted / band + topFlow;
                            touched = true;
                        }
                    }
                    float tailRadius = fullRadius;
                    float tailEnvelope = 0.0;
                    float tailFlowPhase = 0.0;
                    bool tailTouched = false;
                    if (tailOn > 0.5) {
                        if (fromStart > 0.0) {
                            float approach = 1.0;
                            float k;
                            if (approachRow) {
                                float shift = (bendJitter / 0.22) * tailFront.x
                                    * smoothstep(0.0, 1.0, clamp(fromStart / tailFront.y, 0.0, 1.0)) * disorder;
                                approach = clamp((fromStart + shift) / tailLen, 0.0, 1.0);
                                tailEnvelope = sin(approach * PI) * disorder;
                                k = mix(${SeamDissolveTokens.FieldApproachRadius}, tailK.x,
                                    smoothstep(0.0, 1.0, approach));
                            } else {
                                k = mix(tailK.x, tailK.y, clamp((ly - tailYb) / max(1.0, tailEnd - tailYb), 0.0, 1.0));
                            }
                            tailRadius = mix(fullRadius, k * cell.x, tailReveal);
                            tailEnvelope *= tailReveal;
                            tailFlowPhase = approach * 2.0 + tailFlow;
                            tailTouched = true;
                        }
                    }
                    float toBar = yieldRow ? barDistance(float2(lx, ly)) : 1.0e9;
                    bool canYield = toBar < yieldBand.x + yieldBand.y;
                    // The smaller seam owns the dot (and its swirl).
                    bool useTail = tailTouched && (!touched || tailRadius < radius);
                    float r = useTail ? tailRadius : radius;
                    float e = useTail ? tailEnvelope : envelope;
                    float f = useTail ? tailFlowPhase : flow;
                    if (r < minRadius) continue;
                    float2 centre = float2(lx, ly);
                    if (e > 0.0) {
                        centre += float2(
                            cell.x * 0.42 * sin((v * 1.4 + u * 0.45 - f * 0.8) * TAU),
                            cell.y * 0.38 * sin((v * 0.7 - u * 1.3 + f * 0.65) * TAU)
                        ) * e;
                    }
                    float dist = distance(xy, centre);
                    if (dist > r + 0.5) continue;
                    if (canYield) {
                        half4 site = content.eval(float2(lx, ly));
                        if (site.a >= 0.5) {
                            float seen = mix(lightness(site.rgb / site.a), lums.y, fadeAt(ly) * tailReveal);
                            float need = (1.0 - smoothstep(${SeamDissolveTokens.YieldFullDelta},
                                ${SeamDissolveTokens.YieldNoneDelta}, abs(seen - lums.x))) * tailReveal;
                            float yielded = tailRadius * mix(1.0,
                                smoothstep(0.0, 1.0, clamp((toBar - yieldBand.y) / yieldBand.x, 0.0, 1.0)), need);
                            // The yield shrinks the tail's dot; the top seam may still be smaller.
                            if (yielded < tailRadius && (!touched || yielded < radius)) {
                                r = yielded;
                                centre = float2(lx, ly);
                                if (tailEnvelope > 0.0) {
                                    centre += float2(
                                        cell.x * 0.42 * sin((v * 1.4 + u * 0.45 - tailFlowPhase * 0.8) * TAU),
                                        cell.y * 0.38 * sin((v * 0.7 - u * 1.3 + tailFlowPhase * 0.65) * TAU)
                                    ) * tailEnvelope;
                                }
                                dist = distance(xy, centre);
                            }
                        }
                        if (r < minRadius) continue;
                    }
                    coverage = max(coverage, clamp(r - dist + 0.5, 0.0, 1.0));
                }
            }
            if (coverage <= 0.0) return half4(0.0);
            half4 color = content.eval(xy);
            if (tailOn > 0.5) {
                float fade = fadeAt(xy.y) * tailReveal;
                color = half4(mix(color.rgb, page.rgb * color.a, fade), color.a);
            }
            return color * coverage;
        }
    """.trimIndent()
}
