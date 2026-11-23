package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.edit.HomeEditTokens.BadgeMinScale
import com.gpo.yoin.ui.home.edit.HomeEditTokens.BadgeStart
import com.gpo.yoin.ui.home.edit.HomeEditTokens.BlockSwayFactor
import com.gpo.yoin.ui.home.edit.HomeEditTokens.CardAmpDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.CardAmpMaxDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.CardAmpMinDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.CardAmpWidth
import com.gpo.yoin.ui.home.edit.HomeEditTokens.ChargePlateAlpha
import com.gpo.yoin.ui.home.edit.HomeEditTokens.ChargeScale
import com.gpo.yoin.ui.home.edit.HomeEditTokens.KickGain
import com.gpo.yoin.ui.home.edit.HomeEditTokens.LiftScale
import com.gpo.yoin.ui.home.edit.HomeEditTokens.MinAngleDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RippleMaxSteps
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RippleStep
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RubberK
import com.gpo.yoin.ui.home.edit.HomeEditTokens.SwayDetune
import com.gpo.yoin.ui.home.edit.HomeEditTokens.SwayFloor
import com.gpo.yoin.ui.home.edit.HomeEditTokens.SwayHz
import com.gpo.yoin.ui.home.edit.HomeEditTokens.ThetaMaxDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.ThetaMinDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.UnrubberMaxRatio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// Pure geometry for Home edit mode, ported from the prototype (port sheet
// §2–4). Everything is px; dp tokens go through the Density passed in.

// ── Press, plate and entry ────────────────────────────────────────────────

/**
 * R_press in block-content px: a [HomeEditTokens.PressBox] square around
 * [origin], clipped to the content (not the plate), then outset by
 * [HomeEditTokens.PressOutset] × [charge].
 */
internal fun pressRect(origin: Offset, content: Size, charge: Float, density: Density): Rect {
    val half = with(density) { HomeEditTokens.PressBox.toPx() } / 2f
    val outset = with(density) { HomeEditTokens.PressOutset.toPx() } * charge.coerceIn(0f, 1f)
    return Rect(
        left = (origin.x - half).coerceIn(0f, content.width) - outset,
        top = (origin.y - half).coerceIn(0f, content.height) - outset,
        right = (origin.x + half).coerceIn(0f, content.width) + outset,
        bottom = (origin.y + half).coerceIn(0f, content.height) + outset,
    )
}

/** The full plate in block-content px: the content outset by [outsetH] / [outsetV]. */
internal fun fullPlateRect(content: Size, outsetH: Float, outsetV: Float): Rect =
    Rect(-outsetH, -outsetV, content.width + outsetH, content.height + outsetV)

/** Vertical plate outset for a feed item spacing: 6dp at 18dp, less on tighter feeds so plates never touch. */
internal fun plateOutsetVDp(itemSpacingDp: Float): Float = min(
    HomeEditTokens.PlateOutsetVMax.value,
    (itemSpacingDp - HomeEditTokens.PlateMinGap.value) / 2f,
).coerceAtLeast(0f)

/**
 * Ripple progress p_i of block [index] at edit progress [p]: blocks further
 * from the [origin] block start later, in steps of [HomeEditTokens.RippleStep].
 */
internal fun rippleProgress(p: Float, index: Int, origin: Int, count: Int): Float {
    val delay = RippleStep * min(abs(index - origin), RippleMaxSteps)
    val maxDelay = RippleStep * min(max(count - 1, 0), RippleMaxSteps)
    return ((p - delay) / (1f - maxDelay)).coerceIn(0f, 1f)
}

/** Scale of a charging block (about the press point). */
internal fun chargeScale(charge: Float): Float = 1f - ChargeScale * charge

/** Plate pre-show alpha while charging; drawn only where it beats the ripple alpha. */
internal fun chargePlateAlpha(charge: Float): Float = ChargePlateAlpha * smoothstep(0f, 1f, charge.coerceIn(0f, 1f))

/** Scale of a lifted block (about the press point). */
internal fun liftScale(lift: Float): Float = 1f + LiftScale * lift

/** The entry block's plate alpha while it grows from R_press: the latched pre-show, until the ripple passes it. */
internal fun latchedPlateAlpha(latch: Float, rippleAlpha: Float): Float =
    max(ChargePlateAlpha * smoothstep(0f, 1f, latch), rippleAlpha)

/** Badge progress from the ripple progress p_i (not its smoothstep). */
internal fun badgeLocal(rippleProgress: Float): Float =
    ((rippleProgress - BadgeStart) / (1f - BadgeStart)).coerceIn(0f, 1f)

internal fun badgeScale(local: Float): Float = lerp(BadgeMinScale, 1f, smoothstep(0f, 1f, local))

internal fun badgeAlpha(local: Float): Float = smoothstep(0f, 1f, local)

// ── Wiggle ────────────────────────────────────────────────────────────────

/** Block-mode amplitude Θ (degrees) from the plate size: a 2dp corner displacement, clamped. */
internal fun blockThetaDeg(plateW: Float, plateH: Float, density: Density): Float {
    val corner = with(density) { HomeEditTokens.ThetaCorner.toPx() }
    val degrees = Math.toDegrees(atan(corner / (.5f * hypot(plateW, plateH))).toDouble()).toFloat()
    return degrees.coerceIn(ThetaMinDeg, ThetaMaxDeg)
}

/** Card amplitude A_c (degrees). Tall cards count half their height, so a tall cell doesn't swing past 1dp. */
internal fun cardAmpDeg(widthDp: Float, heightDp: Float): Float =
    (CardAmpDeg * CardAmpWidth.value / max(max(widthDp, heightDp / 2f), 1f)).coerceIn(CardAmpMinDeg, CardAmpMaxDeg)

/** Card direction by its index in the section: even +1, odd −1. */
internal fun cardAlt(index: Int): Int = if (index % 2 == 0) 1 else -1

/**
 * Sway fade near the status tide and the bar: 0 once [top]..[bottom]
 * touches the bands, linear over [fadePx] away from them.
 */
internal fun edgeBand(top: Float, bottom: Float, safeTop: Float, safeBottom: Float, fadePx: Float): Float {
    val distance = min(top - safeTop, safeBottom - bottom)
    return if (distance <= 0f) 0f else (distance / fadePx).coerceIn(0f, 1f)
}

/** 32-bit FNV-1a over UTF-16 code units, read unsigned. Stable across runs, unlike `String.hashCode`. */
internal fun fnv1a32(value: String): Long {
    var hash = 0x811C9DC5.toInt()
    for (char in value) {
        hash = (hash xor char.code) * 16777619
    }
    return hash.toLong() and 0xFFFFFFFFL
}

/** A section's own sway: direction, frequency and phase. */
@Immutable
internal data class SwayParams(val sign: Int, val freqHz: Float, val phaseRad: Float)

/** Kick and sway direction of a section. */
internal fun swaySign(id: String): Int = if (fnv1a32(id) and 1L == 1L) -1 else 1

internal fun swayParams(id: String): SwayParams {
    val hash = fnv1a32(id)
    val detune = ((hash ushr 4) % 201).toFloat() / 100f - 1f
    return SwayParams(
        sign = if (hash and 1L == 1L) -1 else 1,
        freqHz = SwayHz * (1f + SwayDetune * detune),
        phaseRad = (hash % 628).toFloat() / 100f,
    )
}

/**
 * Normalised sway shared by a block and its cards. [ripple] is the block's
 * smoothstep(p_i); [timeSec] the one wiggle clock.
 */
internal fun swayValue(envelope: Float, ripple: Float, timeSec: Float, params: SwayParams): Float {
    if (envelope <= SwayFloor || ripple <= 0f) return 0f
    val angle = 2.0 * PI * params.freqHz * timeSec + params.phaseRad
    return params.sign * envelope * ripple * sin(angle).toFloat()
}

/** Wiggle gain: 0 under reduced motion or under its strip, fading out with the lift. */
internal fun wiggleGain(reduced: Boolean, lifted: Boolean, lift: Float, stripCarried: Boolean): Float = when {
    reduced || stripCarried -> 0f
    lifted -> 1f - lift.coerceIn(0f, 1f)
    else -> 1f
}

/** Card-level angle (degrees); 0 when too small to draw. The block kick reaches every card. */
internal fun cardAngleDeg(
    sway: Float,
    blockKick: Float,
    cardKick: Float,
    alt: Int,
    ampDeg: Float,
    gain: Float,
    band: Float,
): Float = drawableAngle(((sway + blockKick) * alt + cardKick) * ampDeg * gain * band)

/** Whole-block angle (degrees) for placeholders; 0 when too small to draw. */
internal fun blockAngleDeg(sway: Float, blockKick: Float, thetaDeg: Float, gain: Float, band: Float): Float =
    drawableAngle((BlockSwayFactor * sway + blockKick) * thetaDeg * gain * band)

private fun drawableAngle(degrees: Float): Float = if (abs(degrees) < MinAngleDeg) 0f else degrees

/**
 * Start velocity of a kick of [amplitude] (normalised, first peak = a). A
 * spring still ringing gets the kick along its current motion.
 */
internal fun kickInitialVelocity(amplitude: Float, sign: Int, running: Boolean, currentVelocity: Float): Float {
    val base = amplitude * KickGain
    return if (running && abs(currentVelocity) > 1e-3f) {
        currentVelocity + currentVelocity.sign * base
    } else {
        base * sign
    }
}

// ── Hit testing ───────────────────────────────────────────────────────────

/**
 * A section's vertical extent in Box px. [bleeds]: a full-width shelf that takes touches in the margins too.
 * [left] / [right]: half of a two-column row, its column's x extent (null = the content's whole width).
 */
@Immutable
internal data class SectionBand(
    val id: HomeSection,
    val top: Float,
    val bottom: Float,
    val bleeds: Boolean,
    val left: Float? = null,
    val right: Float? = null,
)

/**
 * [bands] with every two-column pair split in two: the band of a row's left
 * half ([pairs], left → right, only pairs laid out side by side) becomes its
 * column and its partner's, [gap] apart across [contentLeft]..[contentRight]. Pure.
 */
internal fun splitPairBands(
    bands: List<SectionBand>,
    pairs: Map<HomeSection, HomeSection>,
    contentLeft: Float,
    contentRight: Float,
    gap: Float,
): List<SectionBand> {
    if (pairs.isEmpty()) return bands
    val half = (contentRight - contentLeft - gap) / 2f
    return bands.flatMap { band ->
        val right = pairs[band.id] ?: return@flatMap listOf(band)
        listOf(
            band.copy(bleeds = false, left = contentLeft, right = contentLeft + half),
            band.copy(id = right, bleeds = false, left = contentRight - half, right = contentRight),
        )
    }
}

/** The band nearest to [y] (distance 0 inside); the first wins a tie. −1 when there are none. */
internal fun nearestSectionIndex(y: Float, bands: List<SectionBand>): Int {
    var best = -1
    var bestDistance = Float.POSITIVE_INFINITY
    bands.forEachIndexed { index, band ->
        val distance = when {
            y < band.top -> band.top - y
            y > band.bottom -> y - band.bottom
            else -> 0f
        }
        if (distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

/**
 * The band under ([x], [y]), or null for blank. In edit mode the plate
 * outsets count as the block. Bleeding shelves take any x inside their y range.
 */
internal fun resolveSectionAt(
    x: Float,
    y: Float,
    bands: List<SectionBand>,
    contentLeft: Float,
    contentRight: Float,
    outsetH: Float,
    outsetV: Float,
    editing: Boolean,
): SectionBand? {
    val padH = if (editing) outsetH else 0f
    val padV = if (editing) outsetV else 0f
    return bands.firstOrNull { band ->
        val left = band.left ?: contentLeft
        val right = band.right ?: contentRight
        y >= band.top - padV &&
            y <= band.bottom + padV &&
            (band.bleeds || x >= left - padH && x <= right + padH)
    }
}

// ── Rubber band (the row resize past its first and last stop) ──────────

/** Displayed overscroll for a raw [overscroll] past the end stops. Resists from the first px. */
internal fun rubber(overscroll: Float, density: Density): Float {
    val range = with(density) { HomeEditTokens.RubberD.toPx() }
    return range * (1f - 1f / (overscroll * RubberK / range + 1f))
}

/** Inverse of [rubber]. */
internal fun unrubber(displayed: Float, density: Density): Float {
    val range = with(density) { HomeEditTokens.RubberD.toPx() }
    val ratio = min(displayed / range, UnrubberMaxRatio)
    return range / RubberK * (1f / (1f - ratio) - 1f)
}

// ── Velocity ──────────────────────────────────────────────────────────────

/**
 * Release velocity (px/s) over the last [HomeEditTokens.VelocityWindowMs]:
 * a two-point slope, 0 when the finger rested before lifting, clamped to
 * ±[maxVelocity]. Feed it finger y in Box px.
 */
internal class HomeEditVelocity(private val maxVelocity: Float) {
    private val times = ArrayList<Long>()
    private val positions = ArrayList<Float>()

    fun reset() {
        times.clear()
        positions.clear()
    }

    fun add(uptimeMs: Long, y: Float) {
        times += uptimeMs
        positions += y
        while (times.size > 2 && uptimeMs - times[0] > HomeEditTokens.VelocityWindowMs) {
            times.removeAt(0)
            positions.removeAt(0)
        }
    }

    fun velocity(nowMs: Long): Float {
        if (times.size < 2) return 0f
        val last = times.lastIndex
        if (nowMs - times[last] > HomeEditTokens.VelocityStaleMs) return 0f
        var first = 0
        while (first < times.size - 2 && times[last] - times[first] > HomeEditTokens.VelocityWindowMs) first++
        val elapsedMs = times[last] - times[first]
        if (elapsedMs <= HomeEditTokens.VelocityMinDtMs) return 0f
        val velocity = (positions[last] - positions[first]) / (elapsedMs / 1000f)
        return velocity.coerceIn(-maxVelocity, maxVelocity)
    }
}
