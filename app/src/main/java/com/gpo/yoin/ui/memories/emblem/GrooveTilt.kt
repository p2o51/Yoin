package com.gpo.yoin.ui.memories.emblem

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel

/*
 * 唱片刻纹 · tilt. v4 reads the tilt as COLOUR, not light: the outer ring lines turn lighter (toward the accent)
 * on the side the tilt points to and deeper on the far side; at rest nothing shows. Ported from groove.js v4
 * `tiltVars` / `tiltDriver` (sensor path): relative tilt around a slow baseline (τ 1.6 s, so a holding posture
 * becomes neutral), ±18° → ±1, capped to the unit disc, followed by one spring (dampingRatio .9, stiffness 90).
 */

private const val Deg = Math.PI / 180

/** Resting light direction of the v3 sheen (kept for the `rot` / `li` outputs). */
private const val RestAngle = -128.0

/**
 * Prototype `tiltVars(x, y)`: [x], [y] in −1…1 are the tilt in screen axes (+x right, +y down), capped to the
 * unit disc. v4 draws only from [ta] (direction, degrees) and [tm] (size, 0 at rest); [rot] and [li] are the
 * v3 light angle swing and intensity, kept so the port stays complete.
 */
@Immutable
data class GrooveTiltVars(
    val rot: Double,
    val tx: Double,
    val ty: Double,
    val li: Double,
    val ta: Double,
    val tm: Double,
)

fun grooveTiltVars(x: Double, y: Double): GrooveTiltVars {
    var tx = if (x.isNaN()) 0.0 else x
    var ty = if (y.isNaN()) 0.0 else y
    val mg = hypot(tx, ty)
    if (mg > 1) {
        tx /= mg
        ty /= mg
    }
    val ux = cos(RestAngle * Deg)
    val uy = sin(RestAngle * Deg)
    val a = 0.53 // asin(.53) ≈ 32° at most
    var d = atan2(uy + a * ty, ux + a * tx) / Deg - RestAngle
    d = ((d + 540) % 360) - 180
    return GrooveTiltVars(
        rot = d,
        tx = tx,
        ty = ty,
        li = 0.8 + 0.2 * (tx * ux + ty * uy),
        ta = atan2(ty, tx) / Deg,
        tm = min(1.0, hypot(tx, ty)),
    )
}

/**
 * The sensor half of `tiltDriver` (`onOri`), in W3C DeviceOrientation terms: [onSample] takes beta (front-back,
 * top edge up = +) and gamma (left-right, right edge down = +) in degrees, relative to the CURRENT display
 * orientation, and returns the raw tilt (uncapped; the follow step caps it).
 */
class GrooveTiltFilter {
    private var baseBeta = 0.0
    private var baseGamma = 0.0
    private var hasBase = false
    private var lastAtMs = Long.MIN_VALUE

    fun onSample(betaDeg: Double, gammaDeg: Double, nowMs: Long): Offset {
        if (!hasBase || nowMs - lastAtMs > RebaseAfterMs) {
            baseBeta = betaDeg
            baseGamma = gammaDeg
            hasBase = true
        }
        val dt = if (lastAtMs == Long.MIN_VALUE) 0.25 else ((nowMs - lastAtMs) / 1000.0).coerceIn(0.0, 0.25)
        val a = 1 - exp(-dt / BaselineTauS)
        baseBeta += (betaDeg - baseBeta) * a
        baseGamma += (gammaDeg - baseGamma) * a
        lastAtMs = nowMs
        // the light leans away from gravity
        val x = -(gammaDeg - baseGamma) / FullTiltDeg
        val y = -(betaDeg - baseBeta) / FullTiltDeg
        return Offset(x.toFloat(), y.toFloat())
    }

    fun reset() {
        hasBase = false
        lastAtMs = Long.MIN_VALUE
    }

    companion object {
        const val BaselineTauS = 1.6
        const val FullTiltDeg = 18.0
        const val RebaseAfterMs = 1_500L
    }
}

/** Caps a tilt to the unit disc (prototype `unit`). */
fun Offset.capToUnitDisc(): Offset {
    val mg = hypot(x.toDouble(), y.toDouble())
    return if (mg > 1) Offset((x / mg).toFloat(), (y / mg).toFloat()) else this
}

/**
 * The follow spring of `tiltDriver.step`: semi-implicit Euler on (dampingRatio .9, stiffness 90), dt capped at
 * 34ms. [step] returns true once it has come to rest on a rest goal.
 */
class GrooveTiltSpring {
    var x = 0.0
        private set
    var y = 0.0
        private set
    private var vx = 0.0
    private var vy = 0.0

    fun step(goal: Offset, dtS: Double): Boolean {
        val dt = dtS.coerceIn(0.001, 0.034)
        vx += (Stiffness * (goal.x - x) - Damping * vx) * dt
        x += vx * dt
        vy += (Stiffness * (goal.y - y) - Damping * vy) * dt
        y += vy * dt
        if (goal == Offset.Zero && abs(x) + abs(y) + abs(vx) + abs(vy) < RestEpsilon) {
            snapToRest()
            return true
        }
        return false
    }

    fun snapToRest() {
        x = 0.0
        y = 0.0
        vx = 0.0
        vy = 0.0
    }

    companion object {
        const val Stiffness = 90.0
        val Damping = 2 * 0.9 * sqrt(Stiffness)
        const val RestEpsilon = 2e-3
    }
}

/** The tilt one emblem reads. Only read [offset] in draw: tilting then only redraws, never recomposes. */
@Stable
class GrooveTiltState internal constructor() {
    internal var x by mutableFloatStateOf(0f)
    internal var y by mutableFloatStateOf(0f)

    val offset: Offset get() = Offset(x, y)
}

/** Sensor samples older than this no longer steer: the tilt returns to rest. */
private const val SensorFreshMs = 700L

/**
 * Registers `TYPE_GAME_ROTATION_VECTOR` (SENSOR_DELAY_GAME) only while [active], the lifecycle is RESUMED
 * and motion is not reduced; any one of them dropping unregisters at once. Each sample goes through
 * getRotationMatrixFromVector → remapCoordinateSystem (display rotation) → getOrientation → [GrooveTiltFilter].
 * Under reduced motion the tilt stays at rest: [reducedMotion] is the AMBIENT switch
 * ([rememberGrooveAmbientReduced]), so battery saver and adaptive pressure also park the sensor.
 */
@Composable
fun rememberGrooveTilt(active: Boolean, reducedMotion: Boolean = rememberGrooveAmbientReduced()): GrooveTiltState {
    val state = remember { GrooveTiltState() }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val enabled = active && !reducedMotion && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val goal = remember { TiltGoal() }

    DisposableEffect(enabled, context, view) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val filter = GrooveTiltFilter()
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val (axisX, axisY) = when (view.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                // Android pitch is + when the top edge dips, roll + when the left edge dips; the W3C angles the
                // prototype was tuned on run the other way round.
                val beta = -Math.toDegrees(orientation[1].toDouble())
                val gamma = -Math.toDegrees(orientation[2].toDouble())
                val now = SystemClock.uptimeMillis()
                goal.set(filter.onSample(beta, gamma, now), now)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && manager != null && sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            if (manager != null && sensor != null) manager.unregisterListener(listener)
            goal.clear()
        }
    }

    LaunchedEffect(state, reducedMotion) {
        val spring = GrooveTiltSpring()
        if (reducedMotion) {
            state.x = 0f
            state.y = 0f
            return@LaunchedEffect
        }
        while (true) {
            goal.kicks.receive()
            var last = -1L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last < 0) 0.016 else (now - last) / 1e9
                last = now
                val atRest = spring.step(goal.current(SystemClock.uptimeMillis()), dt)
                state.x = spring.x.toFloat()
                state.y = spring.y.toFloat()
                if (atRest) break
            }
        }
    }
    return state
}

/** The latest sensor goal, plus a conflated wake-up for the follow loop (it sleeps at rest). */
private class TiltGoal {
    private var goal = Offset.Zero
    private var atMs = Long.MIN_VALUE
    val kicks = Channel<Unit>(Channel.CONFLATED)

    fun set(raw: Offset, nowMs: Long) {
        goal = raw.capToUnitDisc()
        atMs = nowMs
        kicks.trySend(Unit)
    }

    fun clear() {
        atMs = Long.MIN_VALUE
        kicks.trySend(Unit)
    }

    fun current(nowMs: Long): Offset = if (atMs != Long.MIN_VALUE && nowMs - atMs < SensorFreshMs) goal else Offset.Zero
}
