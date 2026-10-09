package com.gpo.yoin.ui.navigation.back

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.gpo.yoin.ui.nowplaying.StageBackPreview
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The landing's back (a shell-owned first-run surface with an in-page state machine — its steps). One level:
 * back steps to the previous step; on a re-run's first step it closes the landing. On a first run's first step
 * it is disabled, so system back leaves to the launcher with its own animation (root back is never consumed).
 *
 * The step change is never scrubbed (the Memories diary lesson, 2026-10-09): the finger drives the AOSP
 * preview pose of the current step ([preview], the same pose as the Now Playing stage), and the commit runs
 * the real step change on its spring while the pose springs home. Cancel springs the pose home. Empty flow
 * (3-button / a11y back) commits straight away. Settles run on the outer scope; cancellation is rethrown.
 */
@Composable
fun LandingPredictiveBack(
    enabled: Boolean,
    preview: StageBackPreview,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val settle = YoinMotion.predictiveBackSettleSpring<Float>()
    PredictiveBackHandler(enabled = enabled) { events ->
        var startY: Float? = null
        try {
            events.collect { event ->
                val y0 = startY ?: event.touchY.also { startY = it }
                preview.swipeEdge = event.swipeEdge
                preview.touchYDelta = event.touchY - y0
                preview.progress.snapTo(YoinMotion.backGestureEasing.transform(event.progress.coerceIn(0f, 1f)))
            }
            currentOnBack()
            scope.launch {
                preview.progress.animateTo(0f, settle)
                preview.touchYDelta = 0f
            }
        } catch (e: CancellationException) {
            scope.launch {
                preview.progress.animateTo(0f, settle)
                preview.touchYDelta = 0f
            }
            throw e
        }
    }
}
