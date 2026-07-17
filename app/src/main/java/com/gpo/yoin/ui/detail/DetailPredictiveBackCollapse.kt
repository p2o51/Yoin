package com.gpo.yoin.ui.detail

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.cancellation.CancellationException

/**
 * In-window predictive back for the detail pages: the handler CONSUMES the
 * gesture, so the system's cross-activity animation — which scales the whole
 * WINDOW, bottom bar and all — never engages. Instead only the page content
 * wrapped here collapses (uniform scale toward [BackMotionTokens.PopPageScaleTarget]
 * with a window-corner clip, the same Pixel preview model as NP's stage
 * collapse), while the bottom bar — a SIBLING of this wrapper, not a child —
 * stays motionless on top. Commit calls [onBack] (finish → the 220ms
 * in-place dissolve from applyDetailCloseTransition) with the collapsed pose
 * held so the dissolve continues from where the finger left off; cancel
 * springs the content back.
 *
 * Registered BEFORE the NP overlay host in composition, so NP's own back
 * handlers (enabled while it is expanded) take priority automatically.
 */
@Composable
fun DetailPredictiveBackCollapse(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    // Spring-chased so the release (cancel) settles smoothly and the scrub
    // itself keeps a hint of lag — the Pixel "chase" feel.
    val animatedProgress by animateFloatAsState(
        targetValue = backProgress,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "detailBackCollapse",
    )

    PredictiveBackHandler { progress ->
        try {
            progress.collect { event ->
                backProgress = YoinMotion.backGestureEasing.transform(event.progress)
            }
            // COMMIT: hold the collapsed pose — the window's close dissolve
            // takes over from exactly this frame.
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val cornerRadius = BackMotionTokens.PopPageCornerRadius
    Box(
        modifier = modifier.graphicsLayer {
            val p = animatedProgress
            if (p > 0f) {
                val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(cornerRadius * p)
                clip = true
            }
        },
    ) {
        content()
    }
}
