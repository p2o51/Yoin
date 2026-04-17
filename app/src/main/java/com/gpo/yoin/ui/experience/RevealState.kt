package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Source of truth for a top-anchored reveal sheet (the Memories "attic"
 * page). Visual convention:
 *
 *   fraction = 1f → fully collapsed (surface translated off-screen above)
 *   fraction = 0f → fully expanded  (surface in place)
 *
 * Drag updates fraction 1:1 with the finger; a rubber-band kicks in past
 * the natural endpoints so over-pulls feel resistive. Release decides the
 * settle target from (current position, finger velocity) and springs there
 * with the velocity preserved.
 *
 * `dragBy` is intentionally non-suspend so callers in `onPreScroll` can
 * write through synchronously — the first drag frame mounts the surface in
 * the same composition pass instead of after a coroutine round-trip.
 */
@Stable
class RevealState internal constructor(
    initialFraction: Float,
    private val rubberBandCoefficient: Float,
    private val velocityThresholdFractionPerSec: Float,
    private val positionThreshold: Float,
    private val settleSpec: AnimationSpec<Float>,
) {
    private var _fraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))

    private var settleJob: Job? = null

    val fraction: Float get() = _fraction

    /** Surface has any visible portion (including overshoot). */
    val isVisible: Boolean get() = _fraction < 1f - VisibilityEpsilon

    /** Fully expanded (within an epsilon). */
    val isFullyExpanded: Boolean get() = _fraction <= VisibilityEpsilon

    /**
     * Apply a finger delta in pixels. Positive [deltaPx] (finger moves
     * down) shrinks the fraction (opens the surface); negative (finger up)
     * grows it (closes). Beyond [0, 1], an iOS-style rubber-band caps the
     * overshoot. Cancels any in-flight settle so the gesture wins.
     */
    fun dragBy(deltaPx: Float, containerPx: Float) {
        if (containerPx <= 0f) return
        settleJob?.cancel()
        settleJob = null
        val raw = _fraction - deltaPx / containerPx
        _fraction = applyRubberBand(raw, rubberBandCoefficient)
    }

    /**
     * Animate to the closer endpoint, preserving the finger's velocity.
     * Suspends until the spring settles or is interrupted, then returns
     * the chosen endpoint (0f open, 1f closed). Cancels any in-flight
     * settle.
     */
    suspend fun settle(velocityPxPerSec: Float, containerPx: Float): Float {
        val velocityFractionPerSec = if (containerPx > 0f) {
            -velocityPxPerSec / containerPx
        } else {
            0f
        }
        val target = chooseTarget(_fraction, velocityFractionPerSec)
        animateInternal(target, velocityFractionPerSec)
        return target
    }

    /** Programmatic open (0f) or close (1f). */
    suspend fun animateTo(target: Float) {
        animateInternal(target.coerceIn(0f, 1f), initialVelocity = 0f)
    }

    /** Snap without animation. Cancels any in-flight settle. */
    fun snapTo(target: Float) {
        settleJob?.cancel()
        settleJob = null
        _fraction = target.coerceIn(0f, 1f)
    }

    /**
     * Schedule a settle animation on [scope]. Use when the caller needs to
     * fire-and-forget from a non-suspend context (e.g. a side effect bound
     * to a state change).
     */
    internal fun launchAnimateTo(scope: CoroutineScope, target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            try {
                animate(
                    initialValue = _fraction,
                    targetValue = target.coerceIn(0f, 1f),
                    initialVelocity = 0f,
                    animationSpec = settleSpec,
                ) { value, _ -> _fraction = value }
            } catch (_: CancellationException) {
                // Drag or another settle preempted; leave fraction at last value.
            }
        }
    }

    private suspend fun animateInternal(target: Float, initialVelocity: Float) {
        settleJob?.cancel()
        try {
            animate(
                initialValue = _fraction,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = settleSpec,
            ) { value, _ -> _fraction = value }
        } catch (_: CancellationException) {
            // A new drag/settle took over; leave fraction at the latest value.
        }
    }

    private fun chooseTarget(current: Float, velocityFractionPerSec: Float): Float = when {
        velocityFractionPerSec <= -velocityThresholdFractionPerSec -> 0f
        velocityFractionPerSec >= velocityThresholdFractionPerSec -> 1f
        current < positionThreshold -> 0f
        else -> 1f
    }

    private companion object {
        const val VisibilityEpsilon = 0.001f
    }
}

/**
 * iOS-style rubber-band past the closed endpoint (raw > 1) — pulling
 * infinitely far saturates at +[c] of overshoot in fraction units.
 *
 * The open endpoint (raw < 0) is hard-clamped instead of rubber-banded:
 * a top-anchored sheet has nothing to reveal beyond "fully open", and
 * letting the surface translate further down would expose whatever sits
 * behind it (typically a different background color than the sheet).
 */
private fun applyRubberBand(raw: Float, c: Float): Float = when {
    raw <= 0f -> 0f
    raw <= 1f -> raw
    else -> {
        val overshoot = raw - 1f
        1f + c * (1f - 1f / (1f + overshoot / c))
    }
}

@Composable
fun rememberRevealState(
    initialFraction: Float = 1f,
    rubberBandCoefficient: Float = 0.30f,
    velocityThresholdFractionPerSec: Float = 1.6f,
    positionThreshold: Float = 0.5f,
): RevealState {
    val settleSpec: AnimationSpec<Float> = YoinMotion.predictiveBackSettleSpring()
    return rememberSaveable(
        rubberBandCoefficient,
        velocityThresholdFractionPerSec,
        positionThreshold,
        saver = Saver(
            save = { it.fraction },
            restore = { saved ->
                RevealState(
                    initialFraction = saved,
                    rubberBandCoefficient = rubberBandCoefficient,
                    velocityThresholdFractionPerSec = velocityThresholdFractionPerSec,
                    positionThreshold = positionThreshold,
                    settleSpec = settleSpec,
                )
            },
        ),
    ) {
        RevealState(
            initialFraction = initialFraction,
            rubberBandCoefficient = rubberBandCoefficient,
            velocityThresholdFractionPerSec = velocityThresholdFractionPerSec,
            positionThreshold = positionThreshold,
            settleSpec = settleSpec,
        )
    }
}
