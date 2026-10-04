package com.gpo.yoin.ui.memories.emblem

import androidx.compose.runtime.Immutable
import com.gpo.yoin.ui.memories.copy.MemoryScores
import com.gpo.yoin.ui.memories.showcase.jsRound
import com.gpo.yoin.ui.memories.showcase.toFixed
import kotlin.math.ceil
import kotlin.math.max

/*
 * 唱片刻纹 · the four award tiers, ported 1:1 from groove.js v4 `TIERS` / `tierOf` / `script()`.
 * Every channel is a pure function of t (seconds) built from closed-form Compose springs (dampingRatio,
 * stiffness, mass 1), and every haptic beat is found on the same curve that draws the picture (a settle
 * frame, an overshoot peak, a label contact, a ring at its brightest). Change a spring and the beats move.
 */

/** Closed-form springs and the beat-finding scans of the prototype. StrictMath = fdlibm, as V8 uses. */
internal object GrooveMath {
    /** Compose `spring()` from [x0] to [x1] with initial velocity [v0], evaluated at [t] seconds. */
    fun spring(z: Double, k: Double, t: Double, x0: Double, x1: Double, v0: Double = 0.0): Double {
        if (t <= 0) return x0
        val w0 = StrictMath.sqrt(k)
        val d0 = x0 - x1
        val d = if (z < 1) {
            val wd = w0 * StrictMath.sqrt(1 - z * z)
            StrictMath.exp(-z * w0 * t) *
                (d0 * StrictMath.cos(wd * t) + ((v0 + z * w0 * d0) / wd) * StrictMath.sin(wd * t))
        } else {
            StrictMath.exp(-w0 * t) * (d0 + (v0 + w0 * d0) * t)
        }
        return x1 + d
    }

    /**
     * An upper bound of |spring(t) − x1| from [t] on; used to end a spring run once it can no longer move by
     * more than an epsilon (instead of guessing a duration).
     */
    fun springEnvelope(z: Double, k: Double, t: Double, x0: Double, x1: Double, v0: Double = 0.0): Double {
        val w0 = StrictMath.sqrt(k)
        val d0 = x0 - x1
        return if (z < 1) {
            val wd = w0 * StrictMath.sqrt(1 - z * z)
            StrictMath.exp(-z * w0 * t) * (kotlin.math.abs(d0) + kotlin.math.abs((v0 + z * w0 * d0) / wd))
        } else {
            StrictMath.exp(-w0 * t) * (kotlin.math.abs(d0) + kotlin.math.abs(v0 + w0 * d0) * t)
        }
    }

    fun cl(v: Double): Double = v.coerceIn(0.0, 1.0)

    fun gauss(t: Double, c: Double, w: Double): Double {
        val x = (t - c) / w
        return StrictMath.exp(-(x * x))
    }

    /** An effects-spring flash: a stiff critically-damped rise, then an exponential fade (no linear tween). */
    fun pulse(t: Double, at: Double, hold: Double): Double =
        if (t < at) 0.0 else cl(spring(1.0, 2400.0, t - at, 0.0, 1.0)) * StrictMath.exp(-max(0.0, t - at - 0.04) / hold)

    /** The prototype's 1ms scans, including its floating-point step accumulation. */
    inline fun argmax(a: Double, b: Double, fn: (Double) -> Double): Double {
        var best = Double.NEGATIVE_INFINITY
        var bt = a
        var t = a
        while (t <= b) {
            val v = fn(t)
            if (v > best) {
                best = v
                bt = t
            }
            t += 0.001
        }
        return bt
    }

    inline fun argmin(a: Double, b: Double, fn: (Double) -> Double): Double = argmax(a, b) { -fn(it) }

    inline fun firstT(a: Double, b: Double, pred: (Double) -> Boolean): Double? {
        var t = a
        while (t <= b) {
            if (pred(t)) return t
            t += 0.001
        }
        return null
    }
}

/** `VibrationEffect.Composition` primitives the plan uses, with their YoinHaptics stand-ins. */
enum class GroovePrimitive(val defaultFallback: GrooveFallback?) {
    TICK(GrooveFallback.Tick),
    LOW_TICK(GrooveFallback.LightTick),
    CLICK(GrooveFallback.Click),
    THUD(GrooveFallback.Confirm),
    SPIN(GrooveFallback.Tick),

    /** No View-level constant: skipped by the fallback. */
    QUICK_RISE(null),
}

/** The View-haptic fallback of a beat (`YoinHaptics.performTick / performLightTick / performClick / performConfirm`). */
enum class GrooveFallback { Tick, LightTick, Click, Confirm }

/** One haptic beat at [atMs] after the award's first frame. [scale] is the primitive's 0–1 strength. */
@Immutable
data class GrooveBeat(
    val atMs: Int,
    val primitive: GroovePrimitive,
    val scale: Float,
    val fallback: GrooveFallback?,
    val what: String,
)

/** Prototype `tierOf`: 0 unrated · 1 below 6 · 2 6–8 · 3 8–10 · 4 exactly 10.0, on the displayed one-decimal score. */
fun grooveTierOf(kind: GrooveKind, score: Double?): Int {
    if (kind == GrooveKind.Unrated || score == null) return 0
    val tenths = MemoryScores.tenths(score)
    return when {
        tenths >= 100 -> 4
        tenths >= 80 -> 3
        tenths >= 60 -> 2
        else -> 1
    }
}

/**
 * One tier's choreography (prototype `script(tier, inf, kind)`). Channels are pure functions of t in seconds;
 * [order] is a cut's ring order among the rated rings (outer first), [ring] a ring index.
 *
 * [ringCount] and [ratedRingCount] come from [grooveLayout] (tier 3 counts rated rings for its ring beats,
 * tier 4 counts all rings).
 */
class GrooveScript internal constructor(
    val tier: Int,
    val kind: GrooveKind,
    val ringCount: Int,
    val ratedRingCount: Int,
) {
    private val a = if (kind == GrooveKind.Album) 1.0 else 0.7

    /** Duration in seconds. */
    val durationS: Double = when (tier) {
        1 -> 0.62
        2 -> 1.0
        3 -> 1.3
        else -> 1.6
    }

    // tier 2
    private val t2Cross: Double
    private val t2Peak: Double

    // tiers 3 / 4: the label lifts until LAB, then presses down on its own spring
    private val labAt: Double = if (tier == 4) 0.74 else 0.5

    init {
        if (tier == 2) {
            t2Cross = GrooveMath.firstT(0.0, durationS) { disc(it) >= 0 }?.takeIf { it != 0.0 } ?: 0.43
            t2Peak = GrooveMath.argmax(t2Cross, durationS) { disc(it) }
        } else {
            t2Cross = 0.0
            t2Peak = 0.0
        }
    }

    /** Disc (and album label) rotation, degrees; 0 at rest. */
    fun disc(t: Double): Double = when (tier) {
        1 -> GrooveMath.spring(1.0, 90.0, t, -16.0, 0.0)
        2 -> GrooveMath.spring(0.74, 70.0, t, -330.0, 0.0)
        3 -> GrooveMath.spring(0.8, 42.0, t, -360.0, 0.0, 1300.0)
        else -> GrooveMath.spring(0.82, 44.0, t, -720.0, 0.0, 2000.0)
    }

    private fun lift(t: Double): Double = if (tier == 4) {
        GrooveMath.spring(1.0, 200.0, t, 1.0, 1 + 0.18 * a)
    } else {
        GrooveMath.spring(1.0, 220.0, t, 1.0, 1 + 0.12 * a)
    }

    /** Label scale; 1 at rest. */
    fun label(t: Double): Double = when (tier) {
        1 -> GrooveMath.spring(0.9, 300.0, t, 1 + 0.06 * a, 1.0)
        2 -> if (t < t2Peak) 1.0 else GrooveMath.spring(0.5, 420.0, t - t2Peak, 1.0, 1.0, -1.1 * a)
        3 -> if (t < labAt) lift(t) else GrooveMath.spring(0.55, 520.0, t - labAt, lift(labAt), 1.0)
        else -> if (t < labAt) lift(t) else GrooveMath.spring(0.5, 600.0, t - labAt, lift(labAt), 1.0)
    }

    /** How much of a cut is drawn, 0–1 (clockwise from its start). */
    fun cut(order: Int, t: Double): Double = when (tier) {
        1 -> GrooveMath.cl(GrooveMath.spring(1.0, 140.0, t, 0.0, 1.0))
        2 -> GrooveMath.cl(GrooveMath.spring(1.0, 150.0, t - (0.05 + order * 0.05), 0.0, 1.0))
        3 -> GrooveMath.cl(GrooveMath.spring(1.0, 150.0, t - (0.04 + order * 0.06), 0.0, 1.0))
        else -> GrooveMath.cl(GrooveMath.spring(1.0, 150.0, t - (0.04 + order * 0.045), 0.0, 1.0))
    }

    private fun t3At(order: Int) = 0.72 + order * 0.08
    private fun t4At(ring: Int) = 0.28 + ring * 0.055

    /** Opacity of a cut's flat "lit" colour. */
    fun cutGlow(order: Int, t: Double): Double = when (tier) {
        2 -> 0.85 * GrooveMath.gauss(t, t2Peak + 0.01, 0.085)
        3 -> 0.95 * GrooveMath.pulse(t, t3At(order), 0.2)
        4 -> 0.7 * GrooveMath.pulse(t, TF, 0.26)
        else -> 0.0
    }

    /** Opacity of a whole ring lit in turn, outer → inner (tier 4 only). */
    fun ringGlow(ring: Int, t: Double): Double = if (tier == 4) 0.9 * GrooveMath.pulse(t, t4At(ring), 0.2) else 0.0

    /** The 10.0 rim pulse (tier 4 only). */
    fun flare(t: Double): Double = if (tier == 4) GrooveMath.pulse(t, TF - 0.04, 0.3) else 0.0

    /** The thin ring released from the rim (tier 4 only): scale and opacity. */
    fun burstScale(t: Double): Double =
        if (tier == 4) 1 + 0.2 * GrooveMath.cl(GrooveMath.spring(1.0, 90.0, t - (TF - 0.04), 0.0, 1.0)) else 1.0

    fun burstAlpha(t: Double): Double = if (tier == 4) 0.9 * GrooveMath.pulse(t, TF - 0.04, 0.2) else 0.0

    /** The raw beat plan (prototype `hapticPlan`), merged so no two beats are closer than 45ms. */
    val beats: List<GrooveBeat> = buildBeats()

    private fun buildBeats(): List<GrooveBeat> {
        val out = mutableListOf<GrooveBeat>()
        fun beat(
            at: Double,
            p: GroovePrimitive,
            scale: Double,
            what: String,
            fallback: GrooveFallback? = p.defaultFallback,
        ) {
            out += GrooveBeat(
                atMs = max(0.0, jsRound(at * 1000)).toInt(),
                primitive = p,
                scale = toFixed(scale, 2).toFloat(),
                fallback = fallback,
                what = what,
            )
        }
        when (tier) {
            1 -> {
                val settle = GrooveMath.firstT(0.05, durationS) { label(it) <= 1 }?.takeIf { it != 0.0 } ?: 0.36
                beat(settle, GroovePrimitive.LOW_TICK, 0.5, "label settles")
            }
            2 -> {
                val fastest = GrooveMath.argmax(0.0, t2Cross) { disc(it + 0.001) - disc(it) }
                beat(fastest, GroovePrimitive.TICK, 0.5, "spin-up at its fastest")
                beat(t2Peak, GroovePrimitive.CLICK, 0.75, "overshoot peak: cuts lit, label nods")
            }
            3 -> {
                val contact = GrooveMath.argmin(labAt, labAt + 0.3) { label(it) }
                beat(0.0, GroovePrimitive.SPIN, 0.5, "flick: one full turn")
                beat(contact, GroovePrimitive.CLICK, 1.0, "label at its lowest", GrooveFallback.Confirm)
                val n = ratedRingCount
                val stride = max(1, ceil(n / 4.0).toInt())
                var j = 0
                while (j < n) {
                    val at = t3At(j)
                    beat(
                        GrooveMath.argmax(at, at + 0.2) { GrooveMath.pulse(it, at, 0.2) },
                        GroovePrimitive.LOW_TICK,
                        0.35,
                        "rated ring ${j + 1} lit",
                    )
                    j += stride
                }
            }
            else -> {
                val contact = GrooveMath.argmin(labAt, labAt + 0.3) { label(it) }
                val flarePeak = GrooveMath.argmax(TF - 0.04, TF + 0.2) { flare(it) }
                beat(0.0, GroovePrimitive.SPIN, 0.8, "flick: two turns")
                val n = ringCount
                val stride = max(1, ceil(n / 8.0).toInt())
                var i = 0
                while (i < n) {
                    val at = t4At(i)
                    beat(
                        GrooveMath.argmax(at, at + 0.2) { GrooveMath.pulse(it, at, 0.2) },
                        GroovePrimitive.TICK,
                        0.45,
                        "ring ${i + 1} lit",
                        if (i == 0) GrooveFallback.LightTick else null,
                    )
                    i += stride
                }
                beat(contact - 0.01, GroovePrimitive.THUD, 1.0, "label stamps down", GrooveFallback.Confirm)
                beat(TF - 0.04, GroovePrimitive.QUICK_RISE, 0.7, "rim turns accent", null)
                beat(flarePeak, GroovePrimitive.CLICK, 1.0, "rim fullest, ring released", GrooveFallback.Click)
            }
        }
        return mergeCloseBeats(out)
    }

    companion object {
        /** Tier 4: the moment the flat accent reaches the rim. */
        private const val TF = 1.02

        /** Beats closer than this blur into one buzz; the stronger one is kept. */
        const val MergeWindowMs = 45
    }
}

/** Two beats closer than [GrooveScript.MergeWindowMs] blur into one buzz: sorted by time, the stronger one is kept. */
internal fun mergeCloseBeats(beats: List<GrooveBeat>): List<GrooveBeat> {
    val kept = mutableListOf<GrooveBeat>()
    beats.sortedBy { it.atMs }.forEach { b ->
        val last = kept.lastOrNull()
        if (last != null && b.atMs - last.atMs < GrooveScript.MergeWindowMs) {
            if (b.scale > last.scale) kept[kept.lastIndex] = b
        } else {
            kept += b
        }
    }
    return kept
}

private val scriptCache = object : LinkedHashMap<List<Any>, GrooveScript>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, GrooveScript>?): Boolean = size > 32
}

/** The script of [model]'s tier as drawn at [size], cached by (tier, kind, ring layout). Null when unrated. */
fun grooveScriptFor(model: GrooveModel, size: Double): GrooveScript? {
    val tier = model.tier
    if (tier == 0) return null
    val layout = grooveLayout(model, size)
    return grooveScript(tier, model.kind, layout.ringCount, layout.ratedRings.size)
}

internal fun grooveScript(tier: Int, kind: GrooveKind, ringCount: Int, ratedRingCount: Int): GrooveScript {
    val key = listOf(tier, kind, ringCount, ratedRingCount)
    synchronized(scriptCache) {
        return scriptCache.getOrPut(key) { GrooveScript(tier, kind, ringCount, ratedRingCount) }
    }
}
