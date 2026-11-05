package com.gpo.yoin.ui.nowplaying

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Now Playing's room at rest, the same recipe as iOS's World preset
 * (iosApp/docs/gradient-parameters.md §1.1, owner 2026-11-04: "浅色、深色都让
 * 安卓跟 iOS 的渐变对齐"), bottom to top:
 *
 *  1. this cover's `background`, opaque;
 *  2. the previous song's `primaryContainer` as a soft ellipse past the top
 *     edge, the next song's past the bottom edge ([RoomAmbient.TopBlob],
 *     [RoomAmbient.BottomBlob]);
 *  3. this cover's `primary` as a soft circle in the middle ([RoomAmbient.Aura]),
 *     over the neighbours in both modes;
 *  4. a veil of `background` ([RoomAmbient.veilAlpha]), standing in for
 *     iOS's frosted glass;
 *  5. the glass's own effect on the room: a saturation lift in light
 *     ([RoomAmbient.glassSaturation]) and a darkening in dark
 *     ([RoomAmbient.glassShade]), applied to the room layer only.
 *
 * Lengths are iOS points for a 402 pt-wide phone, scaled by [scaleFor].
 * Pure Kotlin, JVM-tested in RoomAmbientTest.
 */
object RoomAmbient {
    /** iOS phone width the lengths below were drawn at. */
    const val ReferenceWidthDp = 402f

    /** Aura: `Circle().frame(width: 360).blur(radius: 95)`. */
    val Aura = SoftDisk(radius = 180f, blur = 95f)

    /** Previous song: `Ellipse` 300 high, full width, `.offset(y: -90)`, blur 50. */
    val TopBlob = SoftDisk(radius = 150f, blur = 50f)

    /** Next song: `Ellipse` 320 high, full width, `.offset(y: +110)`, blur 50. */
    val BottomBlob = SoftDisk(radius = 160f, blur = 50f)

    val AuraStops: List<Pair<Float, Float>> by lazy { Aura.stops() }
    val TopBlobStops: List<Pair<Float, Float>> by lazy { TopBlob.stops() }
    val BottomBlobStops: List<Pair<Float, Float>> by lazy { BottomBlob.stops() }

    /** Top blob's centre below the top edge (−90 + 150). */
    const val TopBlobCenterDp = 60f

    /** Bottom blob's centre above the bottom edge (160 − 110). */
    const val BottomBlobCenterDp = 50f

    // Strengths below were tuned on the emulator against iOS screenshots of
    // the same covers (2026-11-04): the centre within a few points of iOS's
    // lightness / saturation in both modes. iOS's own values (aura .68 / .88,
    // neighbours 1 / .42, veil .46) read grey in light and foggy in dark here,
    // because Android has no Liquid Glass behind the room.
    fun auraAlpha(dark: Boolean): Float = if (dark) 0.40f else 0.36f

    fun neighbourAlpha(dark: Boolean): Float = if (dark) 0.24f else 0.42f

    /** The room's glass tint, a veil of `background`. */
    fun veilAlpha(dark: Boolean): Float = if (dark) 0.46f else 0.40f

    /**
     * Glass emulation: the saturation the room layer is drawn at. iOS's
     * Liquid Glass lifts colour behind it, most in light mode.
     */
    fun glassSaturation(dark: Boolean): Float = if (dark) 1.25f else 1.8f

    /**
     * Glass emulation: black over the room, inside the room layer (a
     * multiply by 1 − this). iOS's glass darkens what is behind it in dark mode.
     */
    fun glassShade(dark: Boolean): Float = if (dark) 0.15f else 0f

    /**
     * Light only (owner 2026-11-05: 「深色都按照安卓的渐变来」): dark Now
     * Playing keeps Android's own surfaceContainer → background gradient and
     * its four-point cover wash. The dark values above are the tuned iOS match,
     * kept for reference.
     */
    fun appliesIn(dark: Boolean): Boolean = !dark

    /** Phone widths read as iOS; tablets grow the shapes, but not without bound. */
    fun scaleFor(widthDp: Float): Float = (widthDp / ReferenceWidthDp).coerceIn(0.85f, 1.6f)
}

/**
 * A disk of [radius] blurred by a Gaussian of σ = [blur] (iOS's `blur(radius:)`),
 * drawn as a radial gradient out to [outerRadius].
 */
data class SoftDisk(val radius: Float, val blur: Float) {
    val outerRadius: Float get() = radius + 2.5f * blur

    /**
     * Stops (fraction of [outerRadius], coverage 0..1) of the blurred disk,
     * [count] of them from the centre to the rim, the last one 0.
     */
    fun stops(count: Int = 9): List<Pair<Float, Float>> {
        val outer = outerRadius
        return List(count) { i ->
            val f = i / (count - 1).toFloat()
            f to if (i == count - 1) 0f else coverage(f * outer)
        }
    }

    /** How much of the blurred disk covers a point [r] from its centre. */
    fun coverage(r: Float): Float {
        if (blur <= 0f) return if (r <= radius) 1f else 0f
        // Integrate the disk against the Gaussian centred at (r, 0), in polar
        // steps around that point: coverage = Σ G(ρ) · (share of the ring
        // at ρ inside the disk) · ring area.
        val sigma = blur.toDouble()
        val rho = 4.0 * sigma
        val rings = 48
        val spokes = 72
        var sum = 0.0
        var norm = 0.0
        for (ri in 0 until rings) {
            val p = (ri + 0.5) / rings * rho
            val g = exp(-p * p / (2 * sigma * sigma)) * p
            var inside = 0
            for (si in 0 until spokes) {
                val a = (si + 0.5) / spokes * 2 * PI
                val x = r + p * cos(a)
                val y = p * sin(a)
                if (sqrt(x * x + y * y) <= radius) inside++
            }
            sum += g * inside / spokes
            norm += g
        }
        return (sum / norm).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Which songs frame the room (owner rule via the iOS thread, 2026-11-04):
 * the one before and after the current one in play order, wrapping under
 * repeat-all; null where there is none (repeat-one, or the queue's ends
 * without repeat), and the room then falls back to this cover's own
 * secondary / tertiary containers.
 *
 * @param upcoming the queue indices still to play, in play order.
 */
fun roomNeighbourIndices(
    queueSize: Int,
    currentIndex: Int,
    upcoming: List<Int>,
    repeatAll: Boolean,
    repeatOne: Boolean,
    shuffle: Boolean,
): Pair<Int?, Int?> {
    if (queueSize <= 1 || currentIndex !in 0 until queueSize || repeatOne) return null to null
    val next = upcoming.firstOrNull { it in 0 until queueSize && it != currentIndex }
        ?: if (repeatAll && !shuffle) (currentIndex + 1) % queueSize else null
    // Shuffle: the player doesn't say what played before, so no top neighbour.
    val previous = when {
        shuffle -> null
        currentIndex > 0 -> currentIndex - 1
        repeatAll -> queueSize - 1
        else -> null
    }
    return previous to next
}
