package com.gpo.yoin.ui.memories.emblem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.mixSrgb

/*
 * 唱片刻纹 · colour, ported from groove.js v4 `colours()`. Direct palette lerp, never hue-rotated; every fill
 * and stroke is ONE flat colour (no gloss, sheen, glow, gradient or shadow — owner, 2026-10-04).
 */

/** Fixed dark ends the palette lerps toward (M3 baseline background / surface-container in dark). */
private val BackgroundDark = Color(0xFF141218)
private val SurfaceDark = Color(0xFF1D1B20)

/**
 * The neutral theme tokens an unrated emblem is drawn with ("the album's colour is earned, not given").
 * [tiltHigh] / [tiltLow] are the neutral tilt tones of the hairline.
 */
@Immutable
data class GrooveNeutrals(
    val outline: Color,
    val surface: Color,
    val onSurfaceVariant: Color,
    val tiltHigh: Color,
    val tiltLow: Color,
    val dark: Boolean,
) {
    companion object {
        fun from(scheme: ColorScheme): GrooveNeutrals {
            val dark = scheme.surface.luminance() < 0.5f
            return GrooveNeutrals(
                outline = scheme.outline,
                surface = scheme.surface,
                onSurfaceVariant = scheme.onSurfaceVariant,
                tiltHigh = if (dark) scheme.onSurface else Color.White,
                tiltLow = if (dark) scheme.outlineVariant else scheme.onSurface,
                dark = dark,
            )
        }

        val current: GrooveNeutrals
            @Composable
            @ReadOnlyComposable
            get() = from(MaterialTheme.colorScheme)
    }
}

/** The lighter (toward the accent) and deeper tilt tones of one ring line's colour. */
@Immutable
data class GrooveTint(val high: Color, val low: Color)

/**
 * Every colour one emblem uses. Fields that a kind does not draw are [Color.Unspecified].
 *
 * Album / average: [ground] is the opaque disc, [cut] the rated grooves, [under] the faint band beneath a cut,
 * [dot] the pre-cut lattice, [rim] / [rim2] the outer lines, [label] / [labelRing] (album) or [labelFill] /
 * [labelStroke] (average) the centre, [ink] / [caption] its type, [lit] a ring lit by the award, [flare] the
 * 10.0 rim pulse. Unrated: [ground], [dot], [tinyDot], [slot] / [slotFill] (the dashed mould), [hairline]
 * (the cover rim), [hairlineStroke] (its tilt base).
 */
@Immutable
data class GrooveColors(
    val ground: Color,
    val cut: Color = Color.Unspecified,
    val under: Color = Color.Unspecified,
    val dot: Color,
    val tinyDot: Color = Color.Unspecified,
    val rim: Color = Color.Unspecified,
    val rim2: Color = Color.Unspecified,
    val label: Color = Color.Unspecified,
    val labelRing: Color = Color.Unspecified,
    val labelFill: Color = Color.Unspecified,
    val labelStroke: Color = Color.Unspecified,
    val ink: Color,
    val caption: Color = Color.Unspecified,
    val tiltHigh: Color,
    val tiltLow: Color,
    val lit: Color = Color.Unspecified,
    val flare: Color = Color.Unspecified,
    val slot: Color = Color.Unspecified,
    val slotFill: Color = Color.Unspecified,
    val hairline: Color = Color.Unspecified,
    val hairlineStroke: Color = Color.Unspecified,
) {
    /** Prototype `c.tint(base)`: half way to the accent-ward / deep-ward target, flat. */
    fun tint(base: Color): GrooveTint = GrooveTint(
        high = mixSrgb(base, tiltHigh, 0.55),
        low = mixSrgb(base, tiltLow, 0.5),
    )
}

private fun Color.a(alpha: Double): Color = copy(alpha = alpha.toFloat())

/** Prototype `colours(m, kind, dark, on)`. */
fun grooveColors(
    palette: MemoryPalette,
    kind: GrooveKind,
    dark: Boolean,
    surface: GrooveSurface,
    neutrals: GrooveNeutrals,
): GrooveColors {
    val p = palette
    val white = Color.White
    fun mix(a: Color, b: Color, t: Double) = mixSrgb(a, b, t)
    return when (kind) {
        GrooveKind.Album -> if (dark) {
            GrooveColors(
                ground = mix(mix(p.base, BackgroundDark, 0.34), mix(p.base, p.soft, 0.06), 0.6),
                cut = mix(mix(p.soft, p.base, 0.12), mix(p.accent, p.soft, 0.15), 0.4),
                under = p.soft.a(0.16),
                dot = p.soft.a(0.46),
                rim = p.accent,
                rim2 = p.accent.a(0.5),
                label = mix(mix(p.base, p.soft, 0.66), mix(p.base, p.soft, 0.42), 0.5),
                labelRing = mix(p.deep, p.base, 0.3),
                ink = p.deep,
                caption = mix(p.deep, p.base, 0.35),
                tiltHigh = mix(p.accent, white, 0.45),
                tiltLow = mix(p.base, BackgroundDark, 0.25),
                lit = mix(p.accent, white, 0.3),
                flare = mix(p.accent, white, 0.15),
            )
        } else {
            GrooveColors(
                ground = mix(mix(p.soft, white, 0.62), mix(p.soft, p.base, 0.06), 0.6),
                cut = mix(mix(p.base, p.deep, 0.2), mix(p.base, p.accent, 0.32), 0.4),
                under = p.base.a(0.1),
                dot = p.base.a(0.42),
                rim = p.base,
                rim2 = mix(p.accent, p.base, 0.25),
                label = mix(mix(p.base, white, 0.14), mix(p.base, p.deep, 0.3), 0.5),
                labelRing = p.accent,
                ink = mix(p.soft, white, 0.72),
                caption = mix(p.soft, white, 0.35),
                tiltHigh = p.accent,
                tiltLow = p.deep,
                lit = p.accent,
                flare = mix(p.accent, p.base, 0.1),
            )
        }
        GrooveKind.Average -> if (dark) {
            GrooveColors(
                ground = mix(mix(p.deep, SurfaceDark, 0.45), mix(p.deep, SurfaceDark, 0.25), 0.6),
                cut = mix(mix(p.base, p.soft, 0.45), mix(p.soft, p.accent, 0.4), 0.4),
                under = p.soft.a(0.08),
                dot = p.soft.a(0.42),
                rim = mix(p.base, p.soft, 0.5),
                labelFill = mix(p.deep, SurfaceDark, 0.15),
                labelStroke = mix(p.base, p.soft, 0.6),
                ink = mix(p.soft, white, 0.5),
                caption = p.soft.a(0.82),
                tiltHigh = mix(p.accent, white, 0.45),
                tiltLow = mix(p.deep, SurfaceDark, 0.2),
                lit = mix(p.accent, white, 0.3),
                flare = mix(p.accent, white, 0.15),
            )
        } else {
            GrooveColors(
                ground = mix(mix(p.soft, white, 0.82), mix(p.soft, white, 0.6), 0.6),
                cut = mix(mix(p.base, p.soft, 0.08), mix(p.base, p.accent, 0.34), 0.4),
                under = p.base.a(0.06),
                dot = p.base.a(0.38),
                rim = p.base,
                labelFill = mix(p.soft, white, 0.4),
                labelStroke = p.base,
                ink = p.deep,
                caption = p.deep.a(0.74),
                tiltHigh = p.accent,
                tiltLow = p.deep,
                lit = p.accent,
                flare = mix(p.accent, p.base, 0.1),
            )
        }
        GrooveKind.Unrated -> {
            val n = neutrals.outline
            GrooveColors(
                // opaque on a cover so the silhouette separates from the artwork; a faint ground elsewhere
                ground = if (surface == GrooveSurface.Cover) neutrals.surface else n.a(if (dark) 0.1 else 0.06),
                dot = n.a(if (dark) 0.82 else 0.6),
                tinyDot = n.a(if (dark) 0.95 else 0.8),
                slot = n,
                slotFill = n.a(if (dark) 0.14 else 0.08),
                ink = neutrals.onSurfaceVariant,
                hairline = n.a(if (dark) 0.7 else 0.45),
                hairlineStroke = n,
                tiltHigh = neutrals.tiltHigh,
                tiltLow = neutrals.tiltLow,
            )
        }
    }
}
