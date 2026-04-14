package com.gpo.yoin.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Single source of truth for the Now-Playing sheet expansion.
 *
 * `progress` = 0 → collapsed (off-screen below), 1 → fully expanded.
 *
 * Drag writes are **synchronous** — no `launch { snapTo() }` — so there
 * is zero frame-delay flicker during the gesture. Settle / expand / collapse
 * use an M3-Expressive-style spring for the elastic-but-not-overdone feel.
 *
 * Future: predictive-back progress can write directly to [progress] via
 * [onDrag], giving full compatibility without a separate animation layer.
 */
@Stable
class NowPlayingSheetState {

    /** 0f = collapsed, 1f = fully expanded. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** True while the user's finger is actively dragging. */
    var isDragging by mutableStateOf(false)
        private set

    /** Anything above 0 means the overlay should be composed & rendered. */
    val isVisible: Boolean get() = progress > 0f

    /** Cancellation token — incremented on every new gesture or programmatic call. */
    private var generation = 0L

    // ── Drag ────────────────────────────────────────────────────────────

    /** Call from `onDragStarted`. Cancels any running settle/expand animation. */
    fun onDragStart() {
        generation++
        isDragging = true
    }

    /**
     * Synchronous per-delta update.
     * Positive [deltaPx] = finger moving **down** → progress decreases (toward collapse).
     */
    fun onDrag(deltaPx: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        isDragging = true
        progress = (progress - deltaPx / heightPx).coerceIn(0f, 1f)
    }

    // ── Predictive back ─────────────────────────────────────────────────

    /** Call when the system predictive-back gesture begins. Cancels running animations. */
    fun onPredictiveBackStart() {
        generation++
        isDragging = true
    }

    /**
     * Map system predictive-back fraction (0 → 1) to sheet progress (1 → 0).
     * Call on every [BackEventCompat] emitted during the gesture.
     */
    fun onPredictiveBackProgress(backFraction: Float) {
        isDragging = true
        progress = (1f - backFraction).coerceIn(0f, 1f)
    }

    /** Reset interaction state after the predictive-back gesture ends (commit or cancel). */
    fun onPredictiveBackEnd() {
        isDragging = false
    }

    /**
     * Spring-settle to the nearest anchor after drag ends.
     *
     * Positive [velocityPxPerSec] = downward fling → collapse.
     * [onSettledCollapsed] fires only if the sheet settles to 0.
     */
    suspend fun settle(
        velocityPxPerSec: Float,
        heightPx: Float,
        onSettledCollapsed: () -> Unit = {},
    ) {
        isDragging = false
        val shouldCollapse =
            progress < DISMISS_THRESHOLD || velocityPxPerSec > VELOCITY_THRESHOLD_PX
        val target = if (shouldCollapse) 0f else 1f
        animateToTarget(target)
        // Only fire callback if we actually ended up collapsed — another animation
        // (e.g. a re-expand) may have taken over before we reached 0.
        if (target == 0f && progress <= 0f) onSettledCollapsed()
    }

    // ── Programmatic ────────────────────────────────────────────────────

    suspend fun animateExpand() {
        isDragging = false
        animateToTarget(1f)
    }

    suspend fun animateCollapse(onCollapsed: () -> Unit = {}) {
        isDragging = false
        animateToTarget(0f)
        // Guard: only fire callback if still collapsed — a concurrent expand may
        // have moved progress away from 0 before we get here.
        if (progress <= 0f) onCollapsed()
    }

    fun snapTo(value: Float) {
        generation++
        progress = value.coerceIn(0f, 1f)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun animateToTarget(target: Float) {
        val gen = ++generation
        if (progress == target) return
        val anim = Animatable(progress)
        anim.animateTo(target, SETTLE_SPRING) {
            if (gen == generation) progress = value
        }
        if (gen == generation) progress = target
    }

    companion object {
        /** Sheet must be above this progress to spring back to expanded. */
        private const val DISMISS_THRESHOLD = 0.65f
        /** Downward fling faster than this always collapses, regardless of position. */
        private const val VELOCITY_THRESHOLD_PX = 1800f

        /** M3 Expressive-style spring: low-bouncy, medium-low stiffness. */
        private val SETTLE_SPRING = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }
}

@Composable
fun rememberNowPlayingSheetState(): NowPlayingSheetState = remember {
    NowPlayingSheetState()
}
