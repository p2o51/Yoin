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
import com.gpo.yoin.ui.home.edit.HomeEditTokens.FoldHoleStart
import com.gpo.yoin.ui.home.edit.HomeEditTokens.FoldLabelStart
import com.gpo.yoin.ui.home.edit.HomeEditTokens.FoldPlateEnd
import com.gpo.yoin.ui.home.edit.HomeEditTokens.FoldShadowStart
import com.gpo.yoin.ui.home.edit.HomeEditTokens.KickGain
import com.gpo.yoin.ui.home.edit.HomeEditTokens.LiftScale
import com.gpo.yoin.ui.home.edit.HomeEditTokens.MinAngleDeg
import com.gpo.yoin.ui.home.edit.HomeEditTokens.OffscreenStripScale
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RippleMaxSteps
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RippleStep
import com.gpo.yoin.ui.home.edit.HomeEditTokens.RubberK
import com.gpo.yoin.ui.home.edit.HomeEditTokens.StripTint
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

/** A section's vertical extent in Box px. [bleeds]: a full-width shelf that takes touches in the margins too. */
@Immutable
internal data class SectionBand(val id: HomeSection, val top: Float, val bottom: Float, val bleeds: Boolean)

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
        y >= band.top - padV &&
            y <= band.bottom + padV &&
            (band.bleeds || x >= contentLeft - padH && x <= contentRight + padH)
    }
}

// ── Strips ────────────────────────────────────────────────────────────────

/** The strip stack, computed once at fold start (port sheet §4.2). Box px. */
@Immutable
internal data class StripMetrics(
    val count: Int,
    /** Strip height h_s. */
    val height: Float,
    val pitch: Float,
    val stackHeight: Float,
    val left: Float,
    val width: Float,
    /** Slot 0's top. */
    val top: Float,
    val coverSize: Float,
    val safeTop: Float,
    val safeBottom: Float,
) {
    fun slotY(index: Int): Float = top + index * pitch
}

/** Lays out [count] strips so the carried one ([carriedIndex]) is centred on [fingerY], inside the safe area. */
internal fun stripMetrics(
    count: Int,
    carriedIndex: Int,
    fingerY: Float,
    safeTop: Float,
    safeBottom: Float,
    contentLeft: Float,
    contentWidth: Float,
    density: Density,
): StripMetrics = with(density) {
    val n = count.coerceAtLeast(1)
    val gap = HomeEditTokens.StripGap.toPx()
    val height = (((safeBottom - safeTop) - (n - 1) * gap) / n)
        .coerceIn(HomeEditTokens.StripMinH.toPx(), HomeEditTokens.StripMaxH.toPx())
    val pitch = height + gap
    val stackHeight = n * pitch - gap
    // (k + ½)·pitch − gap/2 puts strip k's centre exactly on the finger.
    val top = (fingerY - (carriedIndex + .5f) * pitch + gap / 2f)
        .coerceIn(safeTop, max(safeTop, safeBottom - stackHeight))
    StripMetrics(
        count = n,
        height = height,
        pitch = pitch,
        stackHeight = stackHeight,
        left = contentLeft,
        width = min(contentWidth, HomeEditTokens.StripMaxW.toPx()),
        top = top,
        coverSize = (height - HomeEditTokens.StripCoverInset.toPx())
            .coerceIn(HomeEditTokens.StripCoverMin.toPx(), HomeEditTokens.StripCoverMax.toPx()),
        safeTop = safeTop,
        safeBottom = safeBottom,
    )
}

/** Where a strip flies from (B_i): its visible plate, or an invisible strip at its side's edge. */
@Immutable
internal data class StripStart(val rect: Rect, val alpha: Float)

/**
 * B_i for a section whose feed plate is [plate] (Box px, null when not laid
 * out; [above] then picks the side). The visible part of the plate inside
 * the safe area, or an invisible 0.96 strip at the safe edge it left by.
 */
internal fun stripStart(plate: Rect?, metrics: StripMetrics, above: Boolean): StripStart {
    if (plate != null) {
        val top = max(plate.top, metrics.safeTop)
        val bottom = min(plate.bottom, metrics.safeBottom)
        if (bottom - top > 1f) return StripStart(Rect(plate.left, top, plate.right, bottom), alpha = 1f)
    }
    val offAbove = plate?.let { it.bottom <= metrics.safeTop } ?: above
    val y = if (offAbove) metrics.safeTop else metrics.safeBottom - metrics.height
    val inset = (1f - OffscreenStripScale) / 2f
    return StripStart(
        rect = Rect(
            offset = Offset(metrics.left + metrics.width * inset, y + metrics.height * inset),
            size = Size(metrics.width * OffscreenStripScale, metrics.height * OffscreenStripScale),
        ),
        alpha = 0f,
    )
}

/** One strip at one fold value. Box px. */
@Immutable
internal data class StripFrame(
    val rect: Rect,
    val radius: Float,
    val plateAlpha: Float,
    /** The label keeps its slot size (w × h_s); only its position follows [rect]. */
    val labelOffset: Offset,
    val labelAlpha: Float,
    /** Weight toward the first cover's colour; lerp only where the section has a cover. */
    val tint: Float,
    /** Carried strip only. */
    val shadowAlpha: Float,
)

/**
 * Strip frame (port sheet §4.4) at [fold], which overshoots on purpose: the
 * geometry uses it unclamped. [slotTop] is the strip's slot plus its offset
 * (drag display, settle or make-way).
 */
internal fun stripFrame(
    start: StripStart,
    slotTop: Float,
    metrics: StripMetrics,
    fold: Float,
    carried: Boolean,
    lift: Float,
    density: Density,
): StripFrame {
    var slot = Rect(metrics.left, slotTop, metrics.left + metrics.width, slotTop + metrics.height)
    if (carried) {
        val grow = LiftScale * lift / 2f
        slot = Rect(
            left = slot.left - slot.width * grow,
            top = slot.top - slot.height * grow,
            right = slot.right + slot.width * grow,
            bottom = slot.bottom + slot.height * grow,
        )
    }
    val rect = lerp(start.rect, slot, fold)
    val alpha = lerp(start.alpha, 1f, fold.coerceIn(0f, 1f))
    val radius = min(with(density) { HomeEditTokens.PlateRadius.toPx() }, rect.height / 2f).coerceAtLeast(0f)
    return StripFrame(
        rect = rect,
        radius = radius,
        plateAlpha = alpha * smoothstep(0f, FoldPlateEnd, fold),
        labelOffset = Offset(rect.left, rect.center.y - metrics.height / 2f),
        labelAlpha = alpha * smoothstep(FoldLabelStart, 1f, fold),
        tint = StripTint * smoothstep(FoldHoleStart, 1f, fold),
        shadowAlpha = if (carried) lift.coerceIn(0f, 1f) * smoothstep(FoldShadowStart, 1f, fold) else 0f,
    )
}

/** The drop hole at [holeY]: slot-sized, under every strip. */
internal fun holeRect(metrics: StripMetrics, holeY: Float): Rect =
    Rect(metrics.left, holeY, metrics.left + metrics.width, holeY + metrics.height)

/** Feed sections fade out as the strips fold in: the exact complement of the strip plates. */
internal fun feedFoldAlpha(fold: Float): Float = 1f - smoothstep(0f, FoldPlateEnd, fold)

/**
 * How the feed shows [feedFoldAlpha] (port sheet §4.5): a page-background
 * wash of this alpha over the sections and tray (Home draws it under the
 * strips, below the header), the same picture as fading each block's layer,
 * without an offscreen pass per block for every frame of the fade.
 */
internal fun feedFoldWash(fold: Float): Float = 1f - feedFoldAlpha(fold)

/** The sections' and tray's own alpha under the wash: drawn, or skipped once it covers them. */
internal fun feedFoldShown(fold: Float): Float = if (feedFoldAlpha(fold) > 0f) 1f else 0f

internal fun holeAlpha(fold: Float): Float = smoothstep(FoldHoleStart, 1f, fold)

/** Displayed overscroll for a raw [overscroll] past the end slots. Resists from the first px. */
internal fun rubber(overscroll: Float, density: Density): Float {
    val range = with(density) { HomeEditTokens.RubberD.toPx() }
    return range * (1f - 1f / (overscroll * RubberK / range + 1f))
}

/** Inverse of [rubber], for re-grabbing a strip that is settling from an overscroll. */
internal fun unrubber(displayed: Float, density: Density): Float {
    val range = with(density) { HomeEditTokens.RubberD.toPx() }
    val ratio = min(displayed / range, UnrubberMaxRatio)
    return range / RubberK * (1f / (1f - ratio) - 1f)
}

/**
 * The `requestScrollToItem` offset that puts the dropped item's top at
 * [desiredTop] (px below the list's scroll origin), or null to leave the
 * scroll alone: the header is visible, or a height needed for the clamp is
 * unknown. Clamped so the header stays out: [heightsAbove] are items
 * 1 until [droppedIndex] (item 0 is the header).
 */
internal fun anchorScrollOffset(
    droppedIndex: Int,
    desiredTop: Float,
    heightsAbove: List<Int?>,
    spacingPx: Int,
    headerVisible: Boolean,
): Int? {
    if (headerVisible || droppedIndex < 1) return null
    var room = 0f
    for (index in heightsAbove.indices.reversed()) {
        if (room >= desiredTop) break
        val height = heightsAbove[index] ?: return null
        room += height + spacingPx
    }
    return -min(desiredTop, room).roundToInt()
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
