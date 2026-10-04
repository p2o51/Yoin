package com.gpo.yoin.ui.memories.emblem

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.showcase.jsRound
import com.gpo.yoin.ui.memories.showcase.toFixed
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 唱片刻纹 · award haptics. The beat plan is groove.js `hapticPlan` thinned by twostate4's `awardBeats`
 * (v4 density): tier 4 keeps at most 4 ring ticks (stride ceil(n/4), ≥ 90ms apart, ×.35) and drops
 * QUICK_RISE, so no tier exceeds 7 beats; a replay keeps only the climax; the diary's 48dp nod is the tier's
 * strongest beat at half strength; reduced motion keeps only the strongest beat, on the reveal's last frame.
 */

/** Which award a beat plan is for. */
enum class GrooveBeatMode {
    /** The card's first award. */
    First,

    /** A replay on an awarded card (grooves stay cut). */
    Replay,

    /** The diary emblem's first nod, out of its waiting pose. */
    Nod,

    /** A replayed nod: from rest, dipping first. */
    NodAgain,
}

/** Beat times that come from springs outside the tier scripts. */
object GrooveBeatTimes {
    /** The nod's landing frame: scale .6 → 1 on [GrooveSprings.Nod] first reaches 1. */
    val NodMs: Int = run {
        var t = 0.0
        while (t < 0.6) {
            if (GrooveMath.spring(GrooveSprings.Nod.z, GrooveSprings.Nod.k, t, 0.6, 1.0) >= 1) {
                return@run jsRound(
                    t * 1000,
                ).toInt()
            }
            t += 0.001
        }
        160
    }

    /** A replayed nod starts at rest and dips (v0 −7): its beat lands where the scale comes back up through 1. */
    val NodAgainMs: Int = run {
        var t = 0.02
        while (t < 0.6) {
            if (GrooveMath.spring(GrooveSprings.Nod.z, GrooveSprings.Nod.k, t, 1.0, 1.0, -7.0) >= 1) {
                return@run jsRound(
                    t * 1000,
                ).toInt()
            }
            t += 0.001
        }
        215
    }

    /** Reduced motion: the award develops by alpha in ~200ms and its one beat lands as it finishes. */
    const val RevealMs: Int = 200

    /** Tier 4's ring ticks: at least this far apart. */
    const val RingTickSpacingMs: Int = 90
}

/** The tier's strongest beat (ties go to THUD), as the prototype's `strongest`. */
internal fun strongestBeat(plan: List<GrooveBeat>): GrooveBeat = plan.reduce { a, b ->
    if (b.scale > a.scale || (b.scale == a.scale && b.primitive == GroovePrimitive.THUD)) b else a
}

/**
 * The beats of one award of [model] drawn at [size] (prototype `awardBeats(m, mode, size)` + the nod's
 * landing times). Empty when unrated.
 */
fun awardBeats(
    model: GrooveModel,
    mode: GrooveBeatMode,
    size: Double = 96.0,
    reducedMotion: Boolean = false,
): List<GrooveBeat> {
    val script = grooveScriptFor(model, size) ?: return emptyList()
    var plan = script.beats
    if (script.tier == 4) {
        // ringsAt: every ring can light, except on a tiny drawing (no lit layers there)
        val n = if (grooveLayout(model, size).tiny) 0 else script.ringCount
        val stride = max(1, ceil(n / 4.0).toInt())
        val keep = plan.filter { it.primitive != GroovePrimitive.TICK && it.primitive != GroovePrimitive.QUICK_RISE }
            .toMutableList()
        var last = -1_000_000_000
        var i = 0
        while (i < n) {
            var best = -1.0
            var at = 0.0
            var x = 0.0
            while (x <= script.durationS) {
                val v = script.ringGlow(i, x)
                if (v > best) {
                    best = v
                    at = x
                }
                x += 0.001
            }
            val ms = jsRound(at * 1000).toInt()
            if (ms - last >= GrooveBeatTimes.RingTickSpacingMs) {
                // View-level ticks in a row blur into one: the fallback keeps only the first ring tick
                keep += GrooveBeat(
                    atMs = ms,
                    primitive = GroovePrimitive.TICK,
                    scale = 0.35f,
                    fallback = if (i == 0) GrooveFallback.LightTick else null,
                    what = "ring ${i + 1} lit",
                )
                last = ms
            }
            i += stride
        }
        plan = keep.sortedBy { it.atMs }
    }
    if (mode == GrooveBeatMode.Nod || mode == GrooveBeatMode.NodAgain) {
        val s = strongestBeat(plan)
        val at = when {
            reducedMotion -> GrooveBeatTimes.RevealMs
            mode == GrooveBeatMode.NodAgain -> GrooveBeatTimes.NodAgainMs
            else -> GrooveBeatTimes.NodMs
        }
        return listOf(
            s.copy(scale = toFixed(s.scale.toDouble() * 0.5, 2).toFloat(), atMs = at, what = "diary emblem nod"),
        )
    }
    if (reducedMotion) {
        return listOf(strongestBeat(plan).copy(atMs = GrooveBeatTimes.RevealMs, what = "reveal finished"))
    }
    if (mode == GrooveBeatMode.Replay) {
        val keep = when (script.tier) {
            4 -> setOf(GroovePrimitive.THUD, GroovePrimitive.CLICK)
            1 -> setOf(GroovePrimitive.LOW_TICK)
            else -> setOf(GroovePrimitive.CLICK)
        }
        plan = plan.filter { it.primitive in keep }
    }
    return plan
}

/** The beats the View-haptic fallback actually plays (QUICK_RISE and tier 4's later ring ticks are skipped). */
fun fallbackBeats(beats: List<GrooveBeat>): List<GrooveBeat> = beats.filter { it.fallback != null }

/** One primitive of a composed effect: [delayMs] counts from the END of the previous primitive. */
@Immutable
internal data class ComposedPrimitive(val primitive: GroovePrimitive, val scale: Float, val delayMs: Int)

/**
 * Turns beat times into `Composition.addPrimitive` delays, given each primitive's real duration on this
 * device ([durationsMs], same order as [beats]). A beat whose predecessor is still playing starts right after it.
 */
internal fun composeSchedule(beats: List<GrooveBeat>, durationsMs: IntArray): List<ComposedPrimitive> {
    var cursor = 0
    return beats.mapIndexed { i, b ->
        val wait = max(0, b.atMs - cursor)
        cursor = max(cursor, b.atMs) + durationsMs.getOrElse(i) { 0 }
        ComposedPrimitive(b.primitive, b.scale, wait)
    }
}

// ---------------------------------------------------------------- trace (debug visual equivalent)

/** How a beat reached the user. */
enum class GrooveHapticRoute {
    /** Part of one `VibrationEffect.Composition` (API 31+, vibrator present, all primitives supported). */
    Composition,

    /** `YoinHaptics` at the same time (View haptics; silent when the device has no vibrator). */
    ViewFallback,

    /** No stand-in (QUICK_RISE, tier 4's later ring ticks) or touch feedback is off: nothing played. */
    Skipped,
}

@Immutable
data class GrooveHapticTraceEvent(
    val tag: String,
    val beat: GrooveBeat,
    val firedAtMs: Long,
    val route: GrooveHapticRoute,
)

/** Receives every beat as it is due. Debug builds provide one to draw a visible haptic trace. */
interface GrooveHapticTrace {
    fun onRunStart(tag: String, beats: List<GrooveBeat>, route: GrooveHapticRoute) {}

    fun onBeat(event: GrooveHapticTraceEvent)
}

/** Null in the product; the debug harness provides a trace overlay. */
val LocalHapticTrace = staticCompositionLocalOf<GrooveHapticTrace?> { null }

// ---------------------------------------------------------------- player

/**
 * Plays a beat plan. With API 31+, a vibrator, and every primitive supported, the whole plan is ONE
 * `VibrationEffect.Composition` started on the award's first frame (USAGE_TOUCH on 33+, so the system's
 * touch-feedback switch mutes it; 31–32 read HAPTIC_FEEDBACK_ENABLED first). Otherwise each beat calls
 * [YoinHaptics] at its time. Needs the VIBRATE permission for the composition path.
 */
class GrooveHapticPlayer internal constructor(
    private val context: Context,
    private val haptics: YoinHaptics,
    private val trace: GrooveHapticTrace?,
) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            null
        }
    }

    /** Starts [beats] now. The returned handle cancels, pauses (drops due beats) and resumes. */
    fun play(beats: List<GrooveBeat>, scope: CoroutineScope, tag: String): GrooveHapticHandle =
        GrooveHapticHandle(this, beats, scope, tag).also { it.startFrom(0) }

    internal fun routeFor(beats: List<GrooveBeat>): GrooveHapticRoute {
        if (beats.isEmpty()) return GrooveHapticRoute.Skipped
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return GrooveHapticRoute.ViewFallback
        val v = vibrator ?: return GrooveHapticRoute.ViewFallback
        if (!v.hasVibrator()) return GrooveHapticRoute.ViewFallback
        val ids = beats.map { primitiveId(it.primitive) }.distinct().toIntArray()
        return if (v.areAllPrimitivesSupported(*ids)) GrooveHapticRoute.Composition else GrooveHapticRoute.ViewFallback
    }

    /** Vibrates [beats] (times relative to now) as one composition; false when it could not. */
    internal fun vibrateComposition(beats: List<GrooveBeat>): Boolean {
        if (beats.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return compose31(beats)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun compose31(beats: List<GrooveBeat>): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val enabled = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
            if (enabled == 0) return false
        }
        val ids = beats.map { primitiveId(it.primitive) }.toIntArray()
        val durations = v.getPrimitiveDurations(*ids)
        val composition = VibrationEffect.startComposition()
        composeSchedule(beats, durations).forEach {
            composition.addPrimitive(primitiveId(it.primitive), it.scale.coerceIn(0f, 1f), it.delayMs)
        }
        return try {
            val effect = composition.compose()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
            true
        } catch (_: SecurityException) {
            false // no VIBRATE permission: the View fallback still plays
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    internal fun cancelVibration() {
        try {
            vibrator?.cancel()
        } catch (_: SecurityException) {
            // nothing was vibrating
        }
    }

    internal fun performFallback(fallback: GrooveFallback) {
        when (fallback) {
            GrooveFallback.Tick -> haptics.performTick()
            GrooveFallback.LightTick -> haptics.performLightTick()
            GrooveFallback.Click -> haptics.performClick()
            GrooveFallback.Confirm -> haptics.performConfirm()
        }
    }

    internal fun traceStart(tag: String, beats: List<GrooveBeat>, route: GrooveHapticRoute) {
        trace?.onRunStart(tag, beats, route)
    }

    internal fun traceBeat(event: GrooveHapticTraceEvent) {
        trace?.onBeat(event)
    }

    internal val tracing: Boolean get() = trace != null

    private fun primitiveId(p: GroovePrimitive): Int = when (p) {
        GroovePrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        GroovePrimitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        GroovePrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        GroovePrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        GroovePrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
        GroovePrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    }
}

/** One playing plan. Times are measured from [GrooveHapticPlayer.play]. */
class GrooveHapticHandle internal constructor(
    private val player: GrooveHapticPlayer,
    val beats: List<GrooveBeat>,
    private val scope: CoroutineScope,
    private val tag: String,
) {
    private val startNanos = System.nanoTime()
    private var job: Job? = null
    private var composing = false
    private var cancelled = false

    var paused: Boolean = false
        private set

    val route: GrooveHapticRoute = player.routeFor(beats)

    val elapsedMs: Long get() = (System.nanoTime() - startNanos) / 1_000_000

    /** The strongest beat's time has passed (prototype `climaxed()`). */
    val climaxed: Boolean get() = beats.isEmpty() || elapsedMs >= strongestBeat(beats).atMs

    internal fun startFrom(fromMs: Long) {
        val due = beats.filter { it.atMs >= fromMs }
        if (fromMs == 0L) player.traceStart(tag, beats, route)
        composing = route == GrooveHapticRoute.Composition &&
            player.vibrateComposition(due.map { it.copy(atMs = (it.atMs - elapsedMs).toInt().coerceAtLeast(0)) })
        val viewRoute = !composing
        if (!viewRoute && !player.tracing) return
        job = scope.launch {
            for (b in due) {
                val wait = b.atMs - elapsedMs
                if (wait > 0) delay(wait)
                val fired = when {
                    composing -> GrooveHapticRoute.Composition
                    b.fallback != null -> {
                        player.performFallback(b.fallback)
                        GrooveHapticRoute.ViewFallback
                    }
                    else -> GrooveHapticRoute.Skipped
                }
                player.traceBeat(GrooveHapticTraceEvent(tag, b, elapsedMs, fired))
            }
        }
    }

    /** Stops every remaining beat at once. */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        job?.cancel()
        if (composing) player.cancelVibration()
    }

    /** A drag that may still come back: beats due while paused are dropped, the later ones land on time. */
    fun pause() {
        if (cancelled || paused) return
        paused = true
        job?.cancel()
        if (composing) player.cancelVibration()
    }

    fun resume() {
        if (cancelled || !paused) return
        paused = false
        startFrom(elapsedMs + ResumeLeadMs)
    }

    private companion object {
        const val ResumeLeadMs = 30L
    }
}

@Composable
fun rememberGrooveHapticPlayer(): GrooveHapticPlayer {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val trace = LocalHapticTrace.current
    return remember(context, haptics, trace) { GrooveHapticPlayer(context.applicationContext, haptics, trace) }
}
