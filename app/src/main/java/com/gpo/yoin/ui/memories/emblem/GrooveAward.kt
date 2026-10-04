package com.gpo.yoin.ui.memories.emblem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.Dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * 唱片刻纹 · the award, frame-driven. Each channel is a closed-form function of t taken from GrooveScript, so
 * nothing here is a tween: a frame loop (withFrameNanos) advances t and writes the channel values, and the
 * emblem reads them only in draw / layer blocks (no recomposition per frame). Ported from twostate4.html's
 * awardChans / setPending / awardRun / settleFrom / nod.
 *
 *  · pending: a card that has not had its award waits at the choreography's frame 0 (nothing cut yet), so it
 *    never shows the finished disc and then wipes it. The diary's 48dp copy waits uncut, small (×.6) and, from
 *    tier 2, a quarter-turn back.
 *  · interrupted: beats stop at once; every channel settles to rest from wherever it is on one envelope spring
 *    e: 1 → 0 (no last-frame jump). Angles take the short way round.
 *  · replay: grooves stay cut; the disc's turns are rescaled to whole turns and the label joins the script from
 *    rest on a stiff spring; only the climax beats play.
 *  · reduced motion: the grooves and label develop by alpha (~200ms, critically damped) and the one beat lands
 *    as the reveal finishes. Reduced motion is the user's setting only ([rememberGrooveReducedMotion]); when it
 *    flips, the state is kept and only restyled (a waiting card keeps waiting, in the new mode).
 */

/** Spring constants of the award flow (twostate4 `SPR.settle`, `EFFECTS`, `NOD`, `REVEAL`). */
object GrooveSprings {
    data class Spec(val z: Double, val k: Double)

    /** Interrupted / unfinished award back to rest. */
    val Settle = Spec(z = 0.9, k = 520.0)

    /** Reduced-motion settle: alpha only, no overshoot. */
    val Effects = Spec(z = 1.0, k = 200.0)

    /** The diary emblem's nod (scale .6 → 1, tier 2+ also −90° → 0°). */
    val Nod = Spec(z = 0.7, k = 420.0)

    /** The reduced award's alpha reveal (97.7% at 200ms). */
    val Reveal = Spec(z = 1.0, k = 800.0)

    /** A replay's label joining the script from rest (~70ms). */
    val ReplayJoin = Spec(z = 1.0, k = 1600.0)

    /** The reduced replay's label dip. */
    val Dip = Spec(z = 1.0, k = 2400.0)
}

enum class GrooveAwardMode { First, Replay }

/** What the emblem's draw pass reads from a running award. Read these ONLY in draw / graphicsLayer blocks. */
@Stable
interface GrooveAwardChannels {
    /** Disc rotation (and the album label's, which turns with it), degrees. */
    val discDegrees: Float
    val labelScale: Float
    val labelAlpha: Float

    /** Whole-emblem scale / alpha (the diary nod and its reduced-motion dim). */
    val emblemScale: Float
    val emblemAlpha: Float

    /** 0 while waiting uncut: no tilt colour on grooves that are not cut yet. */
    val tintAlpha: Float
    val flare: Float
    val burstAlpha: Float
    val burstScale: Float

    /** How much of each cut in ring order [order] is drawn, 0–1. */
    fun cutProgress(order: Int): Float
    fun cutAlpha(order: Int): Float
    fun cutGlow(order: Int): Float
    fun ringGlow(ring: Int): Float
}

private const val Disc = 0
private const val LabelScale = 1
private const val LabelAlpha = 2
private const val EmblemScale = 3
private const val EmblemAlpha = 4
private const val TintAlpha = 5
private const val Flare = 6
private const val BurstAlpha = 7
private const val BurstScale = 8
private const val ScalarCount = 9

/** One animated value: [fn] of t, its [rest], and whether it is an angle (settles the short way round). */
private class Chan(val index: Int, val rest: Float, val angular: Boolean = false, val fn: (Double) -> Double)

/** Prototype `nrm`: an angle as its nearest equivalent to 0. */
private fun nearestRest(a: Double): Double = ((a % 360) + 540) % 360 - 180

/** The still picture an award state holds when no run is moving it (re-posed when reduced motion flips). */
private enum class GroovePose { Rest, Pending, Uncut }

@Stable
class GrooveAwardState internal constructor(
    val model: GrooveModel,
    val size: Double,
    val surface: GrooveSurface,
    reducedMotion: Boolean,
    private val haptics: GrooveHapticPlayer?,
    private val scope: CoroutineScope,
    private val tag: String,
) : GrooveAwardChannels {
    /**
     * The user's reduced motion, read by every pose and run as it starts. A flip keeps this state (and the
     * award lifecycle's bookkeeping): a still pose is redrawn in the new mode at once; a run finishes in the
     * mode it started in and then re-poses ([updateReducedMotion]).
     */
    var reducedMotion: Boolean = reducedMotion
        private set
    private var pose = GroovePose.Rest
    private var reposeWhenDone = false

    private val script: GrooveScript? = grooveScriptFor(model, size)
    private val geometry = grooveGeometry(model.trackRated, size, model.kind, surface)
    private val orders = max(max(1, geometry.ratedRings.size), script?.ratedRingCount ?: 0)
    private val rings = max(geometry.rings.size, script?.ringCount ?: 0)
    private fun cutProgressAt(j: Int) = ScalarCount + j
    private fun cutAlphaAt(j: Int) = ScalarCount + orders + j
    private fun cutGlowAt(j: Int) = ScalarCount + 2 * orders + j
    private fun ringGlowAt(i: Int) = ScalarCount + 3 * orders + i

    private val rest = FloatArray(ScalarCount + 3 * orders + rings).also { r ->
        r[LabelScale] = 1f
        r[LabelAlpha] = 1f
        r[EmblemScale] = 1f
        r[EmblemAlpha] = 1f
        r[TintAlpha] = 1f
        r[BurstScale] = 1f
        for (j in 0 until orders) {
            r[ScalarCount + j] = 1f
            r[ScalarCount + orders + j] = 1f
        }
    }
    private val values = rest.copyOf()
    private var version by mutableIntStateOf(0)
    private var job: Job? = null
    private var running: List<Chan> = emptyList()
    private var beatHandle: GrooveHapticHandle? = null

    /** The award's tier (0: unrated, no ceremony). */
    val tier: Int get() = script?.tier ?: 0

    /** True while a run, nod or settle is moving the picture. */
    var isAnimating: Boolean by mutableStateOf(false)
        private set

    /** The current run's strongest beat has passed (an award cut off before it did not count). */
    val climaxed: Boolean get() = beatHandle?.climaxed ?: true

    /** Reading [version] subscribes the reader (a draw / layer block) to every frame write. */
    private fun read(i: Int): Float = if (version >= 0) values[i] else rest[i]

    override val discDegrees: Float get() = read(Disc)
    override val labelScale: Float get() = read(LabelScale)
    override val labelAlpha: Float get() = read(LabelAlpha)
    override val emblemScale: Float get() = read(EmblemScale)
    override val emblemAlpha: Float get() = read(EmblemAlpha)
    override val tintAlpha: Float get() = read(TintAlpha)
    override val flare: Float get() = read(Flare)
    override val burstAlpha: Float get() = read(BurstAlpha)
    override val burstScale: Float get() = read(BurstScale)
    override fun cutProgress(order: Int): Float = if (order in 0 until orders) read(cutProgressAt(order)) else 1f
    override fun cutAlpha(order: Int): Float = if (order in 0 until orders) read(cutAlphaAt(order)) else 1f
    override fun cutGlow(order: Int): Float = if (order in 0 until orders) read(cutGlowAt(order)) else 0f
    override fun ringGlow(ring: Int): Float = if (ring in 0 until rings) read(ringGlowAt(ring)) else 0f

    private fun bump() {
        version++
    }

    // ------------------------------------------------------------------ poses

    /**
     * The waiting pose. On a cover: the script's frame 0 (nothing cut; reduced motion: grooves and label
     * undeveloped). On the bar: uncut, ×.6 and, tier 2+, −90° (reduced motion: dimmed to .35).
     * `false` returns to the finished, resting emblem.
     */
    fun setPending(pending: Boolean) {
        stopAll()
        pose = if (pending && script != null) GroovePose.Pending else GroovePose.Rest
        writePose()
        bump()
    }

    /** Stops everything and shows the finished emblem at once (e.g. once the card is off screen). */
    fun reset() = setPending(false)

    /**
     * The diary emblem landed by the morph on a card still due its award (prototype `barPending(false)` with
     * `barCut` kept): full size and upright, grooves uncut (reduced motion: undeveloped) until the card's award.
     */
    fun setUncut() {
        stopAll()
        pose = if (script != null) GroovePose.Uncut else GroovePose.Rest
        writePose()
        bump()
    }

    /**
     * The user's reduced motion changed under a live emblem: keep everything, restyle the still pose (a card
     * still due its award keeps waiting, now in the new mode). A run in flight finishes in its own mode, then
     * re-poses. Never a new state, so the award lifecycle never loses a card.
     */
    internal fun updateReducedMotion(reduced: Boolean) {
        if (reduced == reducedMotion) return
        reducedMotion = reduced
        if (isAnimating) {
            reposeWhenDone = true
            return
        }
        writePose()
        bump()
    }

    /** Writes [pose] in the current mode (no bump). */
    private fun writePose() {
        reposeWhenDone = false
        rest.copyInto(values)
        val s = script ?: return
        when (pose) {
            GroovePose.Rest -> Unit
            GroovePose.Pending -> {
                values[TintAlpha] = 0f
                when {
                    surface == GrooveSurface.Bar && reducedMotion -> {
                        for (j in 0 until orders) values[cutAlphaAt(j)] = 0f
                        values[EmblemAlpha] = BarWaitAlpha
                    }
                    surface == GrooveSurface.Bar -> {
                        for (j in 0 until orders) values[cutProgressAt(j)] = 0f
                        values[EmblemScale] = BarWaitScale
                        if (s.tier >= 2) values[Disc] = BarWaitTurn
                    }
                    reducedMotion -> {
                        for (j in 0 until orders) values[cutAlphaAt(j)] = 0f
                        values[LabelAlpha] = 0f
                    }
                    else -> channels(s, GrooveAwardMode.First).forEach { values[it.index] = it.fn(0.0).toFloat() }
                }
            }
            GroovePose.Uncut -> {
                values[TintAlpha] = 0f
                for (j in 0 until orders) {
                    if (reducedMotion) values[cutAlphaAt(j)] = 0f else values[cutProgressAt(j)] = 0f
                }
            }
        }
    }

    /** A run or settle reached its end: if reduced motion flipped meanwhile, redraw the pose in the new mode. */
    private fun reposeIfFlipped() {
        if (!reposeWhenDone) return
        writePose()
        bump()
    }

    // ------------------------------------------------------------------ the award

    /** Plays the card award (tier-scaled), from frame 0. A run already playing is replaced. */
    fun play(mode: GrooveAwardMode = GrooveAwardMode.First) {
        val s = script ?: return
        val chans = channels(s, mode)
        val duration = if (reducedMotion) ReducedDurationS else s.durationS
        val beats = awardBeats(
            model,
            if (mode == GrooveAwardMode.First) GrooveBeatMode.First else GrooveBeatMode.Replay,
            size,
            reducedMotion,
        )
        // frame 0 first, then the pending pose is gone: no flash
        stopAll()
        pose = GroovePose.Rest
        rest.copyInto(values)
        launchRun(chans, beats, isDone = { t -> t >= duration }, endAt = duration)
    }

    /**
     * The diary emblem's nod: scale .6 → 1 on [GrooveSprings.Nod] (tier 2+ also turns −90° → 0°); [again]
     * starts from rest and dips first. Reduced motion: the dimmed emblem develops to full alpha (a replay is the
     * beat alone). Grooves that wait uncut stay uncut: the card's award cuts them.
     */
    fun nod(again: Boolean = false) {
        val s = script ?: return
        val beats = awardBeats(model, if (again) GrooveBeatMode.NodAgain else GrooveBeatMode.Nod, size, reducedMotion)
        stopAll()
        // the small wait grows into the full-size, still uncut emblem
        if (pose == GroovePose.Pending) pose = GroovePose.Uncut
        val chans = mutableListOf<Chan>()
        if (reducedMotion) {
            if (!again) {
                val from = values[EmblemAlpha].toDouble()
                chans += Chan(EmblemAlpha, 1f) { t -> springTo(GrooveSprings.Reveal, t, from, 1.0) }
            }
        } else {
            val from = if (again) 1.0 else BarWaitScale.toDouble()
            val v0 = if (again) NodAgainScaleVelocity else 0.0
            chans += Chan(EmblemScale, 1f) { t -> springTo(GrooveSprings.Nod, t, from, 1.0, v0) }
            if (s.tier >= 2) {
                val turnFrom = if (again) 0.0 else BarWaitTurn.toDouble()
                val tv0 = if (again) NodAgainTurnVelocity else 0.0
                chans += Chan(Disc, 0f, angular = true) { t -> springTo(GrooveSprings.Nod, t, turnFrom, 0.0, tv0) }
            }
        }
        val envelopes = chans.map { c ->
            val spec = if (c.index == EmblemAlpha) GrooveSprings.Reveal else GrooveSprings.Nod
            val x0 = c.fn(0.0)
            val v0 = when {
                c.index == EmblemScale && again -> NodAgainScaleVelocity
                c.index == Disc && again -> NodAgainTurnVelocity
                else -> 0.0
            }
            val eps = if (c.angular) AngleEpsilon else ValueEpsilon
            { t: Double -> GrooveMath.springEnvelope(spec.z, spec.k, t, x0, c.rest.toDouble(), v0) < eps }
        }
        launchRun(chans, beats, isDone = { t -> t > MinSettleS && envelopes.all { it(t) } }, endAt = null)
    }

    /** Stops the beats at once and lets the picture settle to rest from where it is. */
    fun interrupt() {
        if (job == null || running.isEmpty()) return
        val chans = running
        beatHandle?.cancel()
        job?.cancel()
        job = scope.launch { settle(chans) }
    }

    /** A horizontal drag that may still come back: beats only; the picture keeps playing. */
    fun pauseBeats() = beatHandle?.pause()

    fun resumeBeats() = beatHandle?.resume()

    /** Stops everything without settling (used before a new pose). */
    private fun stopAll() {
        beatHandle?.cancel()
        beatHandle = null
        job?.cancel()
        job = null
        running = emptyList()
        isAnimating = false
    }

    internal fun dispose() = stopAll()

    private fun launchRun(chans: List<Chan>, beats: List<GrooveBeat>, isDone: (Double) -> Boolean, endAt: Double?) {
        running = chans
        chans.forEach { values[it.index] = it.fn(0.0).toFloat() }
        bump()
        isAnimating = true
        job = scope.launch {
            var t0 = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (t0 < 0) {
                    t0 = now
                    if (beats.isNotEmpty() && haptics != null) beatHandle = haptics.play(beats, scope, tag)
                }
                val t = (now - t0) / 1e9
                if (isDone(t)) break
                chans.forEach { values[it.index] = it.fn(t).toFloat() }
                bump()
            }
            if (endAt != null) chans.forEach { values[it.index] = it.fn(endAt).toFloat() }
            val atRest = chans.all {
                val v = values[it.index].toDouble()
                val d = if (it.angular) nearestRest(v) - it.rest else v - it.rest
                abs(d) < if (it.angular) RestAngleTolerance else RestValueTolerance
            }
            if (atRest || endAt == null) {
                chans.forEach { values[it.index] = it.rest }
                bump()
                running = emptyList()
                isAnimating = false
                reposeIfFlipped()
            } else {
                settle(chans)
            }
        }
    }

    /** Prototype `settleFrom`: e: 1 → 0 on one spring carries every channel from its snapshot to rest. */
    private suspend fun settle(chans: List<Chan>) {
        isAnimating = true
        running = chans
        val spec = if (reducedMotion) GrooveSprings.Effects else GrooveSprings.Settle
        val snap = chans.map { c ->
            val v = values[c.index].toDouble()
            if (c.angular) nearestRest(v) else v
        }
        var t0 = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (t0 < 0) t0 = now
            val t = (now - t0) / 1e9
            val e = GrooveMath.springAt(spec.z, spec.k, t, 1.0, 0.0)
            if (abs(e) < SettleEnd && t > MinSettleS) break
            chans.forEachIndexed { i, c -> values[c.index] = (c.rest + (snap[i] - c.rest) * e).toFloat() }
            bump()
        }
        chans.forEach { values[it.index] = it.rest }
        bump()
        running = emptyList()
        isAnimating = false
        reposeIfFlipped()
    }

    // ------------------------------------------------------------------ channels (twostate4 awardChans)

    private fun channels(s: GrooveScript, mode: GrooveAwardMode): List<Chan> {
        val out = mutableListOf<Chan>()
        if (reducedMotion) {
            val develop = { t: Double -> GrooveMath.cl(springTo(GrooveSprings.Reveal, t, 0.0, 1.0)) }
            if (mode == GrooveAwardMode.First) {
                for (j in 0 until orders) out += Chan(cutAlphaAt(j), 1f, fn = develop)
                out += Chan(LabelAlpha, 1f, fn = develop)
            } else {
                out += Chan(LabelAlpha, 1f) { t ->
                    val dip = GrooveMath.cl(springTo(GrooveSprings.Dip, t, 0.0, 1.0))
                    1 - 0.45 * dip * StrictMath.exp(-max(0.0, t - 0.04) / 0.12)
                }
            }
            return out
        }
        val replay = mode == GrooveAwardMode.Replay
        // a replay starts from the resting picture: tier 2's −330° becomes −360°, tier 1's −16° becomes 0°
        val d0 = s.disc(0.0)
        val discScale = if (!replay) 1.0 else if (d0 != 0.0) jsRoundTurns(d0) * 360 / d0 else 0.0
        out += Chan(Disc, 0f, angular = true) { t -> s.disc(t) * discScale }
        out += Chan(LabelScale, 1f) { t ->
            if (replay) {
                1 + (s.label(t) - 1) * GrooveMath.cl(springTo(GrooveSprings.ReplayJoin, t, 0.0, 1.0))
            } else {
                s.label(t)
            }
        }
        if (!replay) for (j in 0 until orders) out += Chan(cutProgressAt(j), 1f) { t -> s.cut(j, t) }
        for (j in 0 until orders) out += Chan(cutGlowAt(j), 0f) { t -> s.cutGlow(j, t) }
        if (s.tier == 4) {
            for (i in 0 until rings) out += Chan(ringGlowAt(i), 0f) { t -> s.ringGlow(i, t) }
            out += Chan(Flare, 0f) { t -> s.flare(t) }
            out += Chan(BurstAlpha, 0f) { t -> s.burstAlpha(t) }
            out += Chan(BurstScale, 1f) { t -> s.burstScale(t) }
        }
        return out
    }

    private fun springTo(spec: GrooveSprings.Spec, t: Double, from: Double, to: Double, v0: Double = 0.0) =
        GrooveMath.springAt(spec.z, spec.k, t, from, to, v0)

    private fun jsRoundTurns(d0: Double): Double = kotlin.math.floor(d0 / 360 + 0.5)

    companion object {
        /** The bar emblem's waiting pose. */
        const val BarWaitScale = 0.6f
        const val BarWaitTurn = -90f
        const val BarWaitAlpha = 0.35f

        /** A replayed nod's dip: initial velocities of scale and turn. */
        const val NodAgainScaleVelocity = -7.0
        const val NodAgainTurnVelocity = -1500.0

        /** The reduced award's length (its beat lands at [GrooveBeatTimes.RevealMs]). */
        const val ReducedDurationS = 0.34

        private const val SettleEnd = 0.002
        private const val MinSettleS = 0.05
        private const val ValueEpsilon = 0.001
        private const val AngleEpsilon = 0.01
        private const val RestValueTolerance = 0.003
        private const val RestAngleTolerance = 0.2
    }
}

/**
 * Who may change Memories' motion, and how much (B2 of the real-data QA).
 *
 *  · The CHOREOGRAPHY — the host's pose (slide on q vs fade in place), the award (the tiered ceremony vs the
 *    200ms develop), the diary morph (spatial vs fade-through) — follows the USER's reduced motion only: the
 *    system animator duration scale at 0, which is what Developer options and Accessibility › Remove
 *    animations set. It is a standing choice, so it is the same before, during and after an open.
 *  · ADAPTIVE PRESSURE ([MotionProfile.AdaptiveReduced]: battery saver, a low-RAM device, any screen reporting
 *    a busy moment) is transient and app-wide. It may only lighten the AMBIENT effects (the tilt sensor); it
 *    must never swap the spatial model mid-flow or spend a card's award as a 200ms develop.
 */
internal object MemoriesMotionPolicy {
    /** The choreography's reduced mode, from the window's animator duration scale (null: no scale known). */
    fun choreographyReduced(animatorDurationScale: Float?): Boolean = animatorDurationScale == 0f

    /** The ambient effects' reduced mode: the user's choice, or adaptive pressure. */
    fun ambientReduced(choreographyReduced: Boolean, profile: MotionProfile): Boolean =
        choreographyReduced || profile == MotionProfile.AdaptiveReduced
}

/**
 * The user's reduced motion (the system's "remove animations": animator duration scale 0) — the one switch that
 * changes Memories' choreography. Adaptive pressure never reaches it; see [MemoriesMotionPolicy].
 */
@Composable
fun rememberGrooveReducedMotion(): Boolean {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return MemoriesMotionPolicy.choreographyReduced(durationScale?.scaleFactor)
}

/**
 * The ambient effects' switch (the tilt sensor; the unrated mould no longer ripples): the user's reduced motion, or
 * adaptive pressure (battery saver, low RAM, a busy moment). Never use it for the choreography.
 */
@Composable
fun rememberGrooveAmbientReduced(reducedMotion: Boolean = rememberGrooveReducedMotion()): Boolean =
    MemoriesMotionPolicy.ambientReduced(reducedMotion, LocalMotionProfile.current)

/**
 * The award controller of one emblem. [tag] names its beats in the debug haptic trace. The state is keyed on
 * [model], [size] and [surface] (a new key starts at rest), never on [reducedMotion]: a flip of the user's
 * setting restyles the live state ([GrooveAwardState.updateReducedMotion]) so a card still due its award is
 * never rebuilt as already finished.
 */
@Composable
fun rememberGrooveAwardState(
    model: GrooveModel,
    size: Dp,
    surface: GrooveSurface = GrooveSurface.Cover,
    reducedMotion: Boolean = rememberGrooveReducedMotion(),
    haptics: GrooveHapticPlayer? = rememberGrooveHapticPlayer(),
    tag: String = "groove",
): GrooveAwardState {
    val scope = rememberCoroutineScope()
    val state = remember(model, size, surface, haptics, tag) {
        GrooveAwardState(model, size.value.toDouble(), surface, reducedMotion, haptics, scope, tag)
    }
    SideEffect { state.updateReducedMotion(reducedMotion) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
