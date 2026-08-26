package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@Stable
class NowPlayingStageProgress internal constructor(
    initialDetail: Float,
    initialImmersive: Float,
) {
    // Animatable rather than raw floats + animate(): gesture chasing and
    // release settles then share one velocity-continuous value, so a settle
    // started mid-chase inherits the chase velocity instead of restarting
    // from rest.
    private val detailAnim = Animatable(initialDetail.coerceIn(0f, 1f))
    private val immersiveAnim = Animatable(initialImmersive.coerceIn(0f, 1f))
    private var gestureActive by mutableStateOf(false)

    // Clamp the live frame value, not just the animateTo target: a bouncy open
    // spring overshoots past 1.0, and an unclamped `detail` dipping back down
    // through 0.99 re-enters the CoverTransitionOverlay's visibility window,
    // briefly re-mounting a near-full-size cover on top (the flash bug).
    val detail: Float get() = detailAnim.value.coerceIn(0f, 1f)
    val immersive: Float get() = immersiveAnim.value.coerceIn(0f, 1f)
    val compact: Float get() = (1f - detailAnim.value).coerceIn(0f, 1f)
    val isGestureDriving: Boolean get() = gestureActive

    /**
     * True while any stage value is in flight (gesture or settle) — the ARR
     * high-refresh vote window. Animatable.isRunning is snapshot-backed, so a
     * derivedStateOf over this flips composition only at the endpoints.
     */
    val isMoving: Boolean
        get() = gestureActive || detailAnim.isRunning || immersiveAnim.isRunning

    /**
     * Directly track the back gesture: snap the live value each event. The
     * system already spring-smooths back progress (BackProgressAnimator), so a
     * direct eased snap reads as smooth finger-tracking — and avoids the
     * fragile cancel-and-relaunch chase, which could drop frames and make the
     * collapse look like it snapped with no in-between detail.
     */
    suspend fun snapDetail(value: Float) {
        detailAnim.snapTo(value.coerceIn(0f, 1f))
    }

    fun beginGesture() {
        gestureActive = true
    }

    fun endGesture() {
        gestureActive = false
    }

    suspend fun animateDetailTo(target: Float, spec: AnimationSpec<Float>) {
        try {
            detailAnim.animateTo(target.coerceIn(0f, 1f), spec)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            // Keep the last written frame so the next driver can continue.
        }
    }

    suspend fun animateImmersiveTo(target: Float, spec: AnimationSpec<Float>) {
        try {
            immersiveAnim.animateTo(target.coerceIn(0f, 1f), spec)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            // Keep the last written frame so the next driver can continue.
        }
    }
}

@Composable
internal fun rememberNowPlayingStageProgress(
    initialMode: NowPlayingStageMode,
): NowPlayingStageProgress = remember {
    NowPlayingStageProgress(
        initialDetail = if (initialMode == NowPlayingStageMode.Expanded) 1f else 0f,
        initialImmersive = if (initialMode == NowPlayingStageMode.Immersive) 1f else 0f,
    )
}
