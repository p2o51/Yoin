package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 曲奇浪口, the Cookie wave top seam ([SeamTopStyle.Cookie], 2026-10-04): a
 * graphic's top edge under fixed chrome is one wave lip of the Now Playing
 * pill's family, cut as a heightfield — so never holes, islands or marks.
 * The lip's character is scrubbed by the item's own exit progress q, as an
 * unrolled MaterialShape outline: Cookie crowns as it goes under, an almost
 * pure sine halfway (the pill's wave), soft SoftBurst points near the end,
 * rolling a quarter turn as it goes. The tips ease toward the page colour, so
 * they never read sharp. Shared by the AGSL pass and the Path fallback.
 *
 * Positional throughout: at rest every lip depends on q alone (a grid row
 * matches and holds still). Speed only widens the band and the waves and
 * adds one coherent ripple along the row; all of it settles with the
 * viewport's afterglow ([SeamFlow]).
 */
internal object SeamCookie {
    /** One graphic's lip, in its own px; built by [configure]. */
    class Lip {
        /** The seam's y (s): content above it is gone. */
        var seam = 0f

        /** The front's mean depth below the seam, .45G. */
        var rest = 0f
        var lobes = 2f
        var amplitude = 0f

        /** p: Cookie (.5) → sine (≈.95) → SoftBurst (1.8), after the tip-radius floor. */
        var character = 1f
        var phase = 0f

        /** The shoulders' depth at the item's side edges (squares only). */
        var shoulder = 0f
        var shoulderReach = 1f
        var guard = 1f
        var corner = 0f
        var edge = 1f

        /** How far the tips ease toward [page] at the seam, and over what depth. */
        var veil = 0f
        var veilDepth = 1f
        var page = Color.Transparent

        /** Below this depth the content passes through untouched. */
        val reach: Float get() = max(rest + amplitude + shoulder, veilDepth) + edge
    }

    /**
     * Sets [lip] for a graphic whose top is [seam] px above the seam line
     * (negative: still below it). False when the seam does not touch it yet.
     * Under [reduced] motion there is no stretch, disorder, lag or roll; the
     * morph and the envelope stay, being positional.
     */
    fun Density.configure(
        lip: Lip,
        seam: Float,
        width: Float,
        height: Float,
        centreX: Float,
        stretch: Float,
        disorder: Float,
        lagPx: Float,
        travelPx: Float,
        reveal: Float,
        reduced: Boolean,
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        val sigma = if (reduced) 0f else stretch
        val band = lerp(SeamDissolveTokens.CookieBand, SeamDissolveTokens.CookieBandStretched, sigma).toPx() * reveal
        if (band < SeamDissolveTokens.CookieMinBand.toPx()) return false
        val q = exitProgress(seam, height, SeamDissolveTokens.CookieEntry.toPx())
        val lobes = lobeCount(width, SeamDissolveTokens.CookieLobe.toPx())
        val lambda = width / lobes
        val wave = lerp(SeamDissolveTokens.CookieAmplitude, SeamDissolveTokens.CookieAmplitudeStretched, sigma).toPx()
        val amplitude = min(
            envelope(q) * wave,
            min(SeamDissolveTokens.CookieAmplitudeOfBand * band, SeamDissolveTokens.CookieAmplitudeOfLobe * lambda),
        ) * reveal
        lip.seam = seam
        lip.rest = SeamDissolveTokens.CookieRest * band
        lip.lobes = lobes.toFloat()
        lip.amplitude = amplitude
        lip.character = tipFloor(character(q), lambda, amplitude, SeamDissolveTokens.CookieTipRadius.toPx())
        lip.phase = if (reduced) {
            0f
        } else {
            val lag = lagPx.coerceIn(-band / 2f, band / 2f)
            val along = centreX / SeamDissolveTokens.CookieRippleWavelength.toPx() -
                travelPx / SeamDissolveTokens.CookieRippleTravel.toPx()
            val ripple = sin(along * TAU)
            SeamDissolveTokens.CookieRoll * lobes * q +
                SeamDissolveTokens.CookieLagPhase * lag / band +
                SeamDissolveTokens.CookieRipple * disorder * ripple
        }
        lip.shoulder = SeamDissolveTokens.CookieShoulder * band * smoothstep(0f, SeamDissolveTokens.CookieShoulderIn, q)
        lip.shoulderReach = SeamDissolveTokens.CookieShoulderReach.toPx()
        lip.guard = SeamDissolveTokens.CookieGuard.toPx()
        lip.corner = SeamDissolveTokens.CookieCorner.toPx()
        lip.edge = SeamDissolveTokens.CookieEdge.toPx()
        lip.veil = SeamDissolveTokens.CookieVeil * reveal
        lip.veilDepth = SeamDissolveTokens.CookieVeilDepth * band
        // Wholly below the lip and its veil: untouched.
        return -seam < lip.reach
    }

    /** q: 0 while the item's top is [entry] below the seam, 1 once its bottom reaches it. */
    fun exitProgress(seam: Float, height: Float, entry: Float): Float =
        ((seam + entry) / (height + entry)).coerceIn(0f, 1f)

    /** E(q), the M3 wavy indicator's envelope: flat at both ends of the exit. */
    fun envelope(q: Float): Float = smoothstep(0f, SeamDissolveTokens.CookieEnvelopeIn, q) *
        (1f - smoothstep(SeamDissolveTokens.CookieEnvelopeOut, 1f, q))

    /** n: whole lobes of about [lobe] across the item, at least two. */
    fun lobeCount(width: Float, lobe: Float): Int = max(SeamDissolveTokens.CookieMinLobes, (width / lobe).roundToInt())

    /** p(q), log-interpolated Cookie → SoftBurst over the middle of the exit. */
    fun character(q: Float): Float {
        val t = smoothstep(SeamDissolveTokens.CookieMorphFrom, SeamDissolveTokens.CookieMorphTo, q)
        return exp(
            ln(SeamDissolveTokens.CookieCharacterFrom) +
                (ln(SeamDissolveTokens.CookieCharacterTo) - ln(SeamDissolveTokens.CookieCharacterFrom)) * t,
        )
    }

    /** Caps p so a tip's radius never drops under [tipRadius]: λ² / (4π² · ρ · A). */
    fun tipFloor(character: Float, lambda: Float, amplitude: Float, tipRadius: Float): Float {
        if (amplitude <= 1e-4f) return character
        val pi = PI.toFloat()
        return min(character, lambda * lambda / (4f * pi * pi * tipRadius * amplitude))
    }

    /** The profile Pr in [-1, 1] at [x]: crowns (1) at the lobes' centres when the phase is 0. */
    fun profile(lip: Lip, width: Float, x: Float): Float {
        val c = cos((lip.lobes * x / width - .5f + lip.phase) * TAU)
        val g = (((1f + c) / 2f + SeamDissolveTokens.CookieNotch) / (1f + SeamDissolveTokens.CookieNotch))
            .pow(lip.character)
        return 2f * g - 1f
    }

    /**
     * The front f(x): how far below the seam the item starts at [x]. It
     * flattens to the plain rest line before the item's own bottom edge
     * reaches it, so the last sliver is never cut into separate teeth.
     */
    fun front(
        lip: Lip,
        width: Float,
        height: Float,
        x: Float,
        round: Boolean,
        guardRound: Boolean = round,
    ): Float {
        val sideEdge = 1f - smoothstep(0f, lip.shoulderReach, min(x, width - x))
        val shoulder = if (round) 0f else lip.shoulder * sideEdge * sideEdge
        val bottom = silhouetteBottom(lip, width, height, x, guardRound)
        val guard = smoothstep(0f, lip.guard, bottom - lip.seam - lip.rest)
        return lip.rest + guard * (-lip.amplitude * profile(lip, width, x) + shoulder)
    }

    /** Only a square box can hold a round item (an avatar); anything else is taken as square. */
    fun roundable(width: Float, height: Float): Boolean = abs(width - height) < 1f

    /** The item's bottom edge at [x]: an ellipse when [round], else the 4dp rounded square. */
    fun silhouetteBottom(lip: Lip, width: Float, height: Float, x: Float, round: Boolean): Float {
        if (round) {
            val u = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
            return height / 2f * (1f + sqrt(max(0f, 1f - u * u)))
        }
        val corner = lip.corner
        val ex = when {
            x < corner -> corner - x
            x > width - corner -> x - (width - corner)
            else -> 0f
        }
        return if (ex > 0f) height - corner + sqrt(max(0f, corner * corner - ex * ex)) else height
    }

    /** The content's alpha at [depth] below the seam, against the front. */
    fun alpha(front: Float, depth: Float, edge: Float): Float =
        if (depth < 0f) 0f else smoothstep(front - edge, front + edge, depth)

    /** How far the content has eased toward the page at [depth] below the seam (k). */
    fun veil(lip: Lip, depth: Float): Float = lip.veil * (1f - smoothstep(0f, lip.veilDepth, depth))

    /**
     * Pre-33: the region above the front, to cut away with DstOut — the same
     * front the shader evaluates, sampled every [step] px. It cannot read the
     * content, so shoulders stay on; a square box guards with the ellipse,
     * which lies inside both a circle and the rounded square, so neither
     * leaves islands at the end of its exit.
     */
    fun buildCut(path: Path, lip: Lip, width: Float, height: Float, step: Float, outside: Float) {
        val guardRound = roundable(width, height)
        fun edge(x: Float) = lip.seam + front(lip, width, height, x, round = false, guardRound = guardRound)
        path.rewind()
        path.moveTo(-outside, -outside)
        path.lineTo(-outside, edge(0f))
        var x = 0f
        while (true) {
            path.lineTo(x, edge(x))
            if (x >= width) break
            x = min(x + step, width)
        }
        path.lineTo(width + outside, edge(width))
        path.lineTo(width + outside, -outside)
        path.close()
    }

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp = start + (stop - start) * fraction

    private const val TAU = (PI * 2).toFloat()

    /**
     * One pass per band pixel: 1 cos, 1 pow and the content's own eval.
     * Whether a square box holds a round item (an artist's avatar: no
     * shoulders, an elliptical guard) is read from the content at [probe] —
     * (0.1w, 0.1w): outside a circle, inside a pressed square's corner — and
     * only where it matters; any other box ([roundable] = 0) is square.
     */
    val AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float seam;
        uniform float rest;
        uniform float lobes;
        uniform float amplitude;
        uniform float character;
        uniform float phase;
        uniform float shoulder;
        uniform float4 shape;
        uniform float2 probe;
        uniform float roundable;
        uniform float2 veil;
        uniform float reach;
        layout(color) uniform half4 page;

        const float TAU = 6.2831853;

        float silhouetteBottom(float x, bool isRound) {
            if (isRound) {
                float u = clamp((x - size.x * 0.5) / (size.x * 0.5), -1.0, 1.0);
                return size.y * 0.5 * (1.0 + sqrt(max(0.0, 1.0 - u * u)));
            }
            float corner = shape.z;
            float ex = x < corner ? corner - x : (x > size.x - corner ? x - (size.x - corner) : 0.0);
            return ex > 0.0 ? size.y - corner + sqrt(max(0.0, corner * corner - ex * ex)) : size.y;
        }

        half4 main(float2 xy) {
            float depth = xy.y - seam;
            if (depth > reach) return content.eval(xy);
            if (depth < 0.0) return half4(0.0);
            float x = xy.x;
            float side = min(x, size.x - x);
            // The shape only matters at the shoulders and where the guard may flatten the lip.
            bool needShape = (shoulder > 0.0 && side < shape.x) || seam + rest + shape.y > size.y * 0.5;
            bool isRound = false;
            if (needShape && roundable > 0.5) isRound = content.eval(probe).a < 0.5;
            float c = cos((lobes * x / size.x - 0.5 + phase) * TAU);
            float g = pow(
                ((1.0 + c) * 0.5 + ${SeamDissolveTokens.CookieNotch}) / ${1f + SeamDissolveTokens.CookieNotch},
                character);
            float sideEdge = 1.0 - smoothstep(0.0, shape.x, side);
            float shoulderDepth = isRound ? 0.0 : shoulder * sideEdge * sideEdge;
            float guard = smoothstep(0.0, shape.y, silhouetteBottom(x, isRound) - seam - rest);
            float front = rest + guard * (-amplitude * (2.0 * g - 1.0) + shoulderDepth);
            float alpha = smoothstep(front - shape.w, front + shape.w, depth);
            half4 color = content.eval(xy);
            float k = veil.x * (1.0 - smoothstep(0.0, veil.y, depth));
            return half4(mix(color.rgb, page.rgb * color.a, k), color.a) * alpha;
        }
    """.trimIndent()
}
