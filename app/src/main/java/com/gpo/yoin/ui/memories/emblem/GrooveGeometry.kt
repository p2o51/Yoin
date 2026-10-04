package com.gpo.yoin.ui.memories.emblem

import androidx.compose.runtime.Immutable
import com.gpo.yoin.ui.memories.copy.MemoryScores
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.jsRound
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * 唱片刻纹 · Groove — geometry, ported 1:1 from docs/handoff/memories-showcase/groove.js v4
 * (`geom` / `cutRuns` / `layout` plus the derived constants `render` uses). All lengths are dp; angles are
 * degrees, 0 = 3 o'clock, clockwise (the SVG / Compose canvas convention). One ring per track, outer = track 1,
 * merged when there are more tracks than the size's ring budget. Only rated tracks are cut.
 */

/** Which score the label carries: the user's album score, the track average, or none (an empty mould). */
enum class GrooveKind { Album, Average, Unrated }

/**
 * Where the emblem sits. [Cover]: pinned on the artwork (opaque ground, hairline when unrated, the ripple).
 * [Bar]: the diary title's native 48dp copy (rim inset only .5, no ground colour, no ripple).
 */
enum class GrooveSurface { Cover, Bar }

/** What one emblem shows. [trackRated] is in album order (track 1 first). [score] is on the 0–10 scale. */
@Immutable
data class GrooveModel(
    val kind: GrooveKind,
    val score: Double?,
    val trackRated: List<Boolean>,
    val palette: MemoryPalette,
) {
    /** Award tier 0–4 from the displayed one-decimal score. */
    val tier: Int get() = grooveTierOf(kind, score)

    /**
     * The label's number with one decimal (5.95 → "6.0"). It is rounded exactly like [tier] (the tier follows
     * the displayed score), so the two never disagree — JS `toFixed(1)` would show 9.95 as "9.9" while `tierOf`
     * already awards it the 10.0 tier.
     */
    val scoreText: String
        get() {
            val tenths = scoreTenths ?: return ""
            return "${tenths / 10}.${tenths % 10}"
        }

    /** The displayed score in tenths ([MemoryScores], JS `Math.round(score * 10)`), null when unrated. */
    val scoreTenths: Long?
        get() = if (kind == GrooveKind.Unrated || score == null) null else MemoryScores.tenths(score)

    val contentDescription: String
        get() = when {
            kind == GrooveKind.Unrated || score == null -> "Not rated"
            kind == GrooveKind.Album -> "Album rating $scoreText"
            else -> "Track average $scoreText"
        }
}

/** A run of rated tracks inside one ring: an arc from [startDegrees] clockwise to [endDegrees]. */
@Immutable
data class GrooveRun(val startDegrees: Double, val endDegrees: Double) {
    val sweepDegrees: Double get() = endDegrees - startDegrees
}

/** One ring: tracks [firstTrack, endTrack), centred at [radius]. */
@Immutable
data class GrooveRing(
    val index: Int,
    val firstTrack: Int,
    val endTrack: Int,
    val radius: Double,
    val runs: List<GrooveRun>,
)

@Immutable
data class GrooveGeometry(
    val size: Double,
    val kind: GrooveKind,
    val surface: GrooveSurface,
    val tiny: Boolean,
    val big: Boolean,
    val rimRadius: Double,
    val labelRadius: Double,
    val grooveOuter: Double,
    val grooveInner: Double,
    val pitch: Double,
    val rings: List<GrooveRing>,
    val dotDiameter: Double,
    val cutWidth: Double,
    val rimWidth: Double,
    val rim2At: Double,
) {
    val radius: Double get() = size / 2

    /** Rings that carry at least one cut, outer first; a ring's index here is its award order. */
    val ratedRings: List<Int> get() = rings.filter { it.runs.isNotEmpty() }.map { it.index }

    /** The award order of [ring] (prototype `ringOrder`: unrated rings fall back to 0). */
    fun ringOrder(ring: Int): Int = max(0, ratedRings.indexOf(ring))

    /** Dotted pre-cut lattice: dots per ring and their spacing along the circumference (dp). */
    fun dotCount(ring: GrooveRing): Int {
        val circumference = 2 * PI * ring.radius
        val target = max(if (tiny) 2.3 else 2.6, dotDiameter * 2.3)
        return max(6, jsRound(circumference / target).toInt())
    }

    fun dotGap(ring: GrooveRing): Double = 2 * PI * ring.radius / dotCount(ring)

    /** Odd rings start half a gap later, so neighbouring rings interleave. */
    fun dotOffset(ring: GrooveRing): Double = if (ring.index % 2 == 1) dotGap(ring) / 2 else 0.0

    /** The faint band under a cut (`under`), a hair wider than the pitch. */
    val underWidth: Double get() = pitch + 0.05
    val rim2Width: Double get() = max(0.75, size * 0.008)
    val flareWidth: Double get() = max(1.6, size * 0.026)
    val burstWidth: Double get() = max(1.0, size * 0.014)
    val hasLitLayers: Boolean get() = kind != GrooveKind.Unrated && !tiny

    // label
    val albumLabelRingStroke: Double get() = size * 0.007
    val averageLabelStroke: Double get() = max(1.2, size * 0.017)
    val unratedLabelStroke: Double get() = if (tiny) 1.4 else max(1.1, size * 0.013)
    val showsRipple: Boolean get() = kind == GrooveKind.Unrated && size >= 64 && surface != GrooveSurface.Bar
    val showsSpindle: Boolean get() = kind == GrooveKind.Unrated && size < 64
    val spindleRadius: Double get() = max(1.6, size * 0.045)

    // type
    val scoreFontSize: Double
        get() = when {
            big -> size * 0.27
            size >= 64 -> max(size * 0.29, 21.5)
            else -> max(15.5, size * 0.36)
        }
    val showsCaption: Boolean get() = size >= 88
    val captionFontSize: Double get() = max(10.0, size * (if (big) 0.1 else 0.105))
    val showsUnratedWord: Boolean get() = kind == GrooveKind.Unrated && size >= 64
    val unratedFontSize: Double get() = if (size < 88) 9.5 else max(10.0, size * 0.125)
    val unratedWidthAxis: Float get() = if (size < 88) 76f else 92f
}

/** Size thresholds of the ring budget and the "tiny" drawing (prototype `geom`). */
internal object GrooveSizes {
    const val TinyBelow = 60.0
    const val BigFrom = 110.0
}

/** Prototype `geom(m, s, kind, on)`. */
fun grooveGeometry(trackRated: List<Boolean>, size: Double, kind: GrooveKind, surface: GrooveSurface): GrooveGeometry {
    val s = size
    val r = s / 2
    val tiny = s < GrooveSizes.TinyBelow
    val big = s >= GrooveSizes.BigFrom
    val rimR = r - if (surface == GrooveSurface.Bar) 0.5 else 1.0
    // the unrated mould holds a word, not a score, so it opens a little wider below 88
    val labelR = s * (if (tiny) 0.345 else if (big) 0.3 else 0.305) *
        (if (kind == GrooveKind.Unrated && s >= 60 && s < 88) 1.07 else 1.0)
    val rimW = when (kind) {
        GrooveKind.Album -> max(1.1, s * 0.019)
        GrooveKind.Average -> max(0.9, s * 0.012)
        GrooveKind.Unrated -> 0.0
    }
    val rim2At = rimW + max(0.9, s * 0.011)
    val leadIn = if (tiny) 0.9 else max(1.4, s * 0.016)
    val gOut = rimR - (if (kind == GrooveKind.Album && !tiny) rim2At + 0.4 else rimW) - leadIn
    val gIn = labelR + max(1.2, s * 0.017)
    val band = gOut - gIn
    val cap = if (big) 12 else if (s >= 88) 8 else if (s >= 64) 6 else 3
    val minPitch = if (tiny) 1.3 else 1.72
    val n = trackRated.size
    val ringCount = max(1, min(min(cap, n), floor(band / minPitch).toInt()))
    val pitch = band / ringCount
    val rings = List(ringCount) { i ->
        val a = jsRound(i.toDouble() * n / ringCount).toInt()
        val b = jsRound((i + 1).toDouble() * n / ringCount).toInt()
        GrooveRing(
            index = i,
            firstTrack = a,
            endTrack = b,
            radius = gOut - pitch * (i + 0.5),
            runs = if (kind == GrooveKind.Unrated) emptyList() else grooveCutRuns(trackRated.subList(a, b)),
        )
    }
    val dw = min(pitch * 0.58, if (tiny) 1.0 else 1.2)
    val sw = min(pitch * 0.64, dw + 0.25)
    return GrooveGeometry(
        size = s,
        kind = kind,
        surface = surface,
        tiny = tiny,
        big = big,
        rimRadius = rimR,
        labelRadius = labelR,
        grooveOuter = gOut,
        grooveInner = gIn,
        pitch = pitch,
        rings = rings,
        dotDiameter = dw,
        cutWidth = sw,
        rimWidth = rimW,
        rim2At = rim2At,
    )
}

/**
 * Prototype `cutRuns(ring)`: runs of rated tracks inside one ring. Each track gets an equal share of the
 * circle, starting at 12 o'clock.
 */
fun grooveCutRuns(rated: List<Boolean>): List<GrooveRun> {
    val len = rated.size
    val runs = mutableListOf<GrooveRun>()
    var start = -1
    for (j in 0..len) {
        val isRated = j < len && rated[j]
        if (isRated && start < 0) start = j
        if (!isRated && start >= 0) {
            runs += GrooveRun(-90.0 + start.toDouble() / len * 360.0, -90.0 + j.toDouble() / len * 360.0)
            start = -1
        }
    }
    return runs
}

/** One cut as the award counts it: [k] in render order, on ring [ring]. */
@Immutable
data class GrooveCut(val k: Int, val ring: Int)

/** Prototype `layout(m, s)`: what the award needs to know about an emblem (always measured on a cover). */
@Immutable
data class GrooveLayout(
    val kind: GrooveKind,
    val size: Double,
    val ringCount: Int,
    val cuts: List<GrooveCut>,
    val ratedRings: List<Int>,
    val tiny: Boolean,
)

fun grooveLayout(model: GrooveModel, size: Double): GrooveLayout {
    val g = grooveGeometry(model.trackRated, size, model.kind, GrooveSurface.Cover)
    val cuts = mutableListOf<GrooveCut>()
    if (model.kind != GrooveKind.Unrated) {
        g.rings.forEach { ring -> ring.runs.forEach { cuts += GrooveCut(cuts.size, ring.index) } }
    }
    return GrooveLayout(
        kind = model.kind,
        size = size,
        ringCount = g.rings.size,
        cuts = cuts,
        ratedRings = cuts.map { it.ring }.distinct().sorted(),
        tiny = g.tiny,
    )
}
