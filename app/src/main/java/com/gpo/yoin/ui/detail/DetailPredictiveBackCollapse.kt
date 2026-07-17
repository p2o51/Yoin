package com.gpo.yoin.ui.detail

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * In-window predictive back for the detail pages, a faithful port of AOSP's
 * `CrossActivityBackAnimation` pre-commit math applied to the page CONTENT
 * only — the bottom bar is a sibling and never transforms; instead it scrubs
 * its own detail⇄nav morph off [DetailBackCollapseState.progress].
 *
 * Ported behaviour (frameworks/base WM Shell, DefaultCrossActivityBackAnimation):
 *  - gesture progress through the BACK_GESTURE interpolator, spring-chased
 *  - uniform rect-lerped scale toward MAX_SCALE (0.9)
 *  - LEFT-edge swipes anchor the shrunken content's right edge 8dp from the
 *    screen edge; RIGHT-edge swipes stay centered (AOSP asymmetry)
 *  - vertical follow: deceleration-interpolated touch-Y delta, capped at half
 *    a screen of travel, scaled to the slack before the 8dp display margin
 *  - post-commit: fast alpha-out (AOSP fades in the first fifth of its 450ms
 *    post-commit) with a slight continued drift, then the activity finishes
 *    into its in-place window dissolve.
 *
 * The whole gesture is CONSUMED, so the system's window-level animation —
 * which would scale the bar too — never engages. Activities stay Activities.
 */
@Stable
class DetailBackCollapseState internal constructor() {
    /**
     * Gesture progress (0..1), tracked 1:1 with the finger DURING the
     * gesture (platform behaviour — no smoothing between finger and pixels);
     * springs run only on release (cancel settle).
     */
    internal val chased = Animatable(0f)

    /** Current scrub progress for consumers (bar morph). */
    val progress: Float
        get() = chased.value

    /** Raw finger Y delta from gesture start, px. Applied un-sprung, like AOSP. */
    internal var touchYDelta by mutableFloatStateOf(0f)

    internal var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /** Post-commit exit fraction (0..1): fast fade + slight drift. */
    internal val exit = Animatable(0f)
}

@Composable
fun rememberDetailBackCollapse(onBack: () -> Unit): DetailBackCollapseState {
    val state = remember { DetailBackCollapseState() }
    val scope = rememberCoroutineScope()
    val settleSpec = YoinMotion.predictiveBackSettleSpring<Float>()

    PredictiveBackHandler { events ->
        var initialTouchY = Float.NaN
        var sawGesture = false
        try {
            events.collect { event ->
                sawGesture = true
                if (initialTouchY.isNaN()) initialTouchY = event.touchY
                state.swipeEdge = event.swipeEdge
                state.touchYDelta = event.touchY - initialTouchY
                // Direct 1:1 tracking, like the platform: the eased progress
                // IS the pose. No smoothing between finger and pixels.
                state.chased.snapTo(
                    YoinMotion.backGestureEasing.transform(event.progress),
                )
            }
            // COMMIT. Button-backs emit no progress events — skip straight to
            // the window dissolve. Gesture commits play the AOSP post-commit
            // content exit (the fade lands within ~90ms) first.
            if (sawGesture && state.chased.value > 0.02f) {
                state.exit.animateTo(1f, tween(durationMillis = 140))
            }
            onBack()
        } catch (e: CancellationException) {
            // CANCEL: the handler coroutine is already dying — settle on the
            // host scope. The Y offset needs no separate settle: its head
            // room collapses with the progress spring (maxShift ∝ p).
            scope.launch {
                state.chased.animateTo(0f, settleSpec)
                state.touchYDelta = 0f
            }
            throw e
        }
    }
    return state
}

/**
 * The AOSP-mapped content transform. Apply to the page content container —
 * and ONLY the content; the bottom bar must stay outside. All values are read
 * in the layer block, so gesture frames never recompose the page.
 */
fun Modifier.detailBackCollapseTransform(state: DetailBackCollapseState): Modifier =
    graphicsLayer {
        val p = state.chased.value
        val exit = state.exit.value
        if (p <= 0f && exit <= 0f) {
            return@graphicsLayer
        }

        val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
        scaleX = scale
        scaleY = scale

        val marginPx = DisplayBoundsMarginDp * density
        // Horizontal: LEFT-edge swipes anchor the scaled content's right edge
        // 8dp from the screen edge; RIGHT-edge swipes stay centered.
        val dxTarget = if (state.swipeEdge == BackEventCompat.EDGE_LEFT) {
            size.width * (1f - BackMotionTokens.PopPageScaleTarget) / 2f - marginPx
        } else {
            0f
        }
        // Post-commit keeps drifting while the alpha snuffs out.
        translationX = dxTarget * p + exit * ExitDriftDp * density

        // Vertical follow (AOSP getYOffset): decelerated ratio of the raw
        // finger travel, capped at half a screen, scaled to the slack the
        // shrunken content has before hitting the 8dp margin.
        val rawDy = state.touchYDelta
        if (rawDy != 0f) {
            val halfH = size.height / 2f
            val ratio = min(halfH, abs(rawDy)) / halfH
            val decelerated = 1f - (1f - ratio) * (1f - ratio)
            val maxShift = max(0f, (size.height - size.height * scale) / 2f - marginPx)
            translationY = maxShift * decelerated * (if (rawDy < 0f) -1f else 1f)
        }

        // AOSP post-commit: alpha = max(1 − 5·t, 0) — gone in the first fifth.
        alpha = max(1f - exit * 5f, 0f)

        shape = RoundedCornerShape(BackMotionTokens.PopPageCornerRadius * p)
        clip = p > 0f
    }

/** AOSP `displayBoundsMargin` (8dp) — content never passes this margin. */
private const val DisplayBoundsMarginDp = 8f

/** Slight rightward drift during the post-commit fade. */
private const val ExitDriftDp = 24f
