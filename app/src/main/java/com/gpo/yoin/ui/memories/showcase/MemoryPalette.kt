package com.gpo.yoin.ui.memories.showcase

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One album's four palette anchors (prototype `m.palette`). Every Memories colour is a direct sRGB lerp
 * between these and a few fixed ends — never a hue rotation, never `fromSeed`.
 */
@Immutable
data class MemoryPalette(
    val base: Color,
    val accent: Color,
    val deep: Color,
    val soft: Color,
) {
    /** The card / diary tones for this palette (prototype `pal(m, dark)`; the glow-era seal / halo fields are gone). */
    fun tones(dark: Boolean): MemoryPaletteTones {
        if (!dark) {
            return MemoryPaletteTones(
                ink = base,
                highlight = base,
                button = base,
                onButton = Color.White,
                dot = base,
                tint = base.copy(alpha = 0.06f),
            )
        }
        val lift = mixSrgb(base, soft, 0.6)
        return MemoryPaletteTones(
            ink = lift,
            highlight = liftHue(base, DarkHighlightLightness),
            button = lift,
            onButton = deep,
            dot = lift,
            tint = lift.copy(alpha = 0.1f),
        )
    }

    companion object {
        /** OKLab L of the dark playhead highlight: on-surface (#E7E0E8) is .915, so a lit line never reads dimmer. */
        const val DarkHighlightLightness = 0.92

        /** HSL lightness window of a backdrop-built base: ink on the light page, and a pill white can sit on. */
        const val BackdropBaseMinLightness = 0.22
        const val BackdropBaseMaxLightness = 0.46

        /** Where the fixtures' deep / soft anchors sit relative to base (m1: deep ≈ base × .45, soft ≈ .8 white). */
        const val BackdropDeepMix = 0.55
        const val BackdropSoftMix = 0.8

        /**
         * A palette from a cover's extracted backdrop colours (`rememberExpressiveBackdropColors`): the real
         * deck has no hand-picked anchors, so [base] is held inside the ink lightness window and deep / soft
         * are lerped from it toward black / white — a direct sRGB lerp, never `fromSeed`.
         */
        fun fromBackdrop(base: Color, accent: Color): MemoryPalette {
            val held = holdLightness(base, BackdropBaseMinLightness, BackdropBaseMaxLightness)
            return MemoryPalette(
                base = held,
                accent = accent.copy(alpha = 1f),
                deep = mixSrgb(held, Color.Black, BackdropDeepMix),
                soft = mixSrgb(held, Color.White, BackdropSoftMix),
            )
        }
    }
}

/** [color] moved straight toward black or white until its HSL lightness is inside [min]…[max]. */
internal fun holdLightness(color: Color, min: Double, max: Double): Color {
    val c = color.copy(alpha = 1f)
    val hi = maxOf(c.red, c.green, c.blue).toDouble()
    val lo = minOf(c.red, c.green, c.blue).toDouble()
    val lightness = (hi + lo) / 2
    return when {
        lightness > max -> mixSrgb(c, Color.Black, 1 - max / lightness)
        lightness < min -> mixSrgb(c, Color.White, (min - lightness) / (1 - lightness))
        else -> c
    }
}

/**
 * @property ink text / rule colour drawn in the album's hue.
 * @property highlight the playing line; in dark it is lifted to on-surface lightness ([liftHue]).
 * @property button the album-coloured filled pill; [onButton] is its label.
 */
@Immutable
data class MemoryPaletteTones(
    val ink: Color,
    val highlight: Color,
    val button: Color,
    val onButton: Color,
    val dot: Color,
    val tint: Color,
)

/** 8-bit sRGB channels of an opaque colour, as the prototype's `#rrggbb` strings hold them. */
internal fun Color.rgb8(): IntArray = intArrayOf(
    (red * 255f).roundToInt(),
    (green * 255f).roundToInt(),
    (blue * 255f).roundToInt(),
)

/** JS `Math.round`: halves go up (towards +∞), unlike Kotlin's `roundToInt` on negatives. */
internal fun jsRound(x: Double): Double = floor(x + 0.5)

internal fun colorOf8(r: Double, g: Double, b: Double): Color = Color(
    red = jsRound(r.coerceIn(0.0, 255.0)).toInt(),
    green = jsRound(g.coerceIn(0.0, 255.0)).toInt(),
    blue = jsRound(b.coerceIn(0.0, 255.0)).toInt(),
)

/**
 * The prototype's `mix(a, b, t)`: a per-channel sRGB lerp rounded back to 8 bits, so nested mixes round at
 * every step exactly like the `#rrggbb` strings they were tuned on. Alpha is dropped (inputs are opaque).
 */
internal fun mixSrgb(a: Color, b: Color, t: Double): Color {
    val x = a.rgb8()
    val y = b.rgb8()
    return colorOf8(
        x[0] + (y[0] - x[0]) * t,
        x[1] + (y[1] - x[1]) * t,
        x[2] + (y[2] - x[2]) * t,
    )
}

/** JS `(+v).toFixed(digits)` as a number: half-up on the exact binary value. */
internal fun toFixed(value: Double, digits: Int): Double =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toDouble()

/**
 * The palette's own hue at OKLab lightness [lightness], with as much chroma as sRGB allows up to .075
 * (prototype `liftHue`, ported line for line).
 */
fun liftHue(base: Color, lightness: Double): Color {
    val (r, g, b) = base.rgb8().map { lin(it.toDouble()) }
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val hue = atan2(
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    )
    fun rgbAt(c: Double): DoubleArray {
        val aa = c * cos(hue)
        val bb = c * sin(hue)
        val x = (lightness + 0.3963377774 * aa + 0.2158037573 * bb).pow(3)
        val y = (lightness - 0.1055613458 * aa - 0.0638541728 * bb).pow(3)
        val z = (lightness - 0.0894841775 * aa - 1.2914855480 * bb).pow(3)
        return doubleArrayOf(
            4.0767416621 * x - 3.3077115913 * y + 0.2309699292 * z,
            -1.2684380046 * x + 2.6097574011 * y - 0.3413193965 * z,
            -0.0041960863 * x - 0.7034186147 * y + 1.7076147010 * z,
        )
    }
    var lo = 0.0
    var hi = 0.075
    repeat(20) {
        val c = (lo + hi) / 2
        if (rgbAt(c).all { it in 0.0..1.0 }) lo = c else hi = c
    }
    val out = rgbAt(lo).map(::gam)
    return colorOf8(out[0], out[1], out[2])
}

private fun lin(v8: Double): Double {
    val v = v8 / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun gam(v: Double): Double = 255.0 * if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

/** The sample albums of the handoff (`data.js` m1–m4, twostate4's m5), for previews, the debug harness and tests. */
internal object MemoryPaletteSamples {
    val M1 = MemoryPalette(Color(0xFF3B2D8F), Color(0xFFE2C27A), Color(0xFF1B1554), Color(0xFFD9D2F4))
    val M2 = MemoryPalette(Color(0xFF7A5A14), Color(0xFF9CC7E8), Color(0xFF3D2E07), Color(0xFFEFE4C4))
    val M3 = MemoryPalette(Color(0xFF1F4F8F), Color(0xFFE5A07C), Color(0xFF0B2448), Color(0xFFD3E2F6))
    val M4 = MemoryPalette(Color(0xFF8F1D3A), Color(0xFF7FE0C4), Color(0xFF4A0B1C), Color(0xFFF5D4DC))
    val M5 = MemoryPalette(Color(0xFF2F6A4F), Color(0xFFE8B86A), Color(0xFF123826), Color(0xFFD3EADC))
}
