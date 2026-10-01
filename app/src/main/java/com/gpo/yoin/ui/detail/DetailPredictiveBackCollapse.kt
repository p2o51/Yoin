package com.gpo.yoin.ui.detail

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.component.rememberBottomBarShadowHandBack
import com.gpo.yoin.ui.experience.DetailBackPhase
import com.gpo.yoin.ui.experience.rememberIsActivityEmbedded
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * In-window predictive back for the detail pages, a faithful port of AOSP's
 * `CrossActivityBackAnimation` pre-commit math applied to the page CONTENT
 * only — the bottom bar is a sibling and never transforms; instead it scrubs
 * its own detail⇄nav morph off [DetailBackCollapseState.progress].
 *
 * Ported behaviour (frameworks/base WM Shell, DefaultCrossActivityBackAnimation):
 *  - gesture progress through the BACK_GESTURE interpolator, tracked 1:1
 *    with the finger (springs run only on the cancel settle)
 *  - uniform rect-lerped scale toward MAX_SCALE (0.9)
 *  - LEFT-edge swipes anchor the shrunken content's right edge 8dp from the
 *    screen edge; RIGHT-edge swipes stay centered (AOSP asymmetry)
 *  - vertical follow: deceleration-interpolated touch-Y delta, capped at half
 *    a screen of travel, scaled to the slack before the 8dp display margin
 *  - post-commit: effects-spring dissolve with a slight continued drift;
 *    the activity finishes only after both it and the bar's spatial spring
 *    have settled — and the bar's shadow has been handed back to the bar
 *    beneath — preserving the final hand-off pose.
 *
 * The whole gesture is CONSUMED, so the system's window-level animation —
 * which would scale the bar too — never engages. Activities stay Activities.
 *
 * Except in an Activity Embedding pane: a split-pane detail has no bar, so
 * the reason for the replica is gone and the page is a plain destination
 * (predictive-back skill, Pattern A). There the handler is disabled, the
 * window opaque and the close transition the system's — the platform plays
 * its own back, including closing the split when the pane's last page goes,
 * with nothing of ours to reveal a black pane behind it.
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

    /**
     * True from the first gesture sample until its cancel spring has settled.
     * Enter choreography observes this local owner state instead of the global
     * cross-window bridge, whose Idle value can be restored by another window.
     */
    internal var gestureActive by mutableStateOf(false)

    /** Terminal for this Activity instance: once a back commits it never resets. */
    internal var committed by mutableStateOf(false)
}

/**
 * Serialises a cancelled gesture's host-scope settle with the next back
 * operation. Animatable cancels an in-flight mutation when another starts; if
 * an old cancel settle is allowed to touch the same Animatable after a button
 * commit begins, it can cancel that commit and swallow the first back press.
 */
internal class DetailBackOperationGuard {
    private var generation = 0L
    private var cancelSettleJob: Job? = null
    private var committed = false
    private var finishDispatched = false

    suspend fun beginOperation(): Long {
        // Invalidate the old owner's completion before cancelling it. Even a
        // non-cooperative settle can no longer publish Idle for this operation.
        val owner = ++generation
        cancelSettleJob?.cancelAndJoin()
        cancelSettleJob = null
        return owner
    }

    fun launchCancelSettle(
        scope: CoroutineScope,
        owner: Long,
        settle: suspend () -> Unit,
        onSettled: () -> Unit,
    ) {
        cancelSettleJob = scope.launch {
            settle()
            currentCoroutineContext().ensureActive()
            if (generation == owner && !committed) onSettled()
        }
    }

    fun markCommitted() {
        committed = true
    }

    fun launchCommit(
        scope: CoroutineScope,
        settle: suspend () -> Unit,
        onFinish: () -> Unit,
    ): Job = scope.launch {
        try {
            settle()
        } finally {
            dispatchFinishOnce(onFinish)
        }
    }

    fun dispatchFinishOnce(onFinish: () -> Unit) {
        if (finishDispatched) return
        finishDispatched = true
        onFinish()
    }

    fun recoverCancellation(
        onCommittedCancellation: () -> Unit,
        onGestureCancellation: () -> Unit,
    ) {
        if (committed) onCommittedCancellation() else onGestureCancellation()
    }
}

@Composable
fun rememberDetailBackCollapse(
    onBack: () -> Unit,
    bridgeToShell: Boolean = false,
): DetailBackCollapseState {
    val state = remember { DetailBackCollapseState() }
    val operationGuard = remember { DetailBackOperationGuard() }
    val scope = rememberCoroutineScope()
    val settleSpec = YoinMotion.predictiveBackSettleSpring<Float>()
    val commitSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val exitSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val handBackBarShadow = rememberBottomBarShadowHandBack()
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as YoinApplication).container.experienceSessionStore
    }

    // Pattern A inside a split pane, Pattern B everywhere else — re-decided
    // whenever the pane joins or leaves a split (rotation, resizing).
    val nativeBack = rememberIsActivityEmbedded()
    DetailWindowBackModeEffect(nativeBack)

    // Out of a split, keep the detail window translucent for its whole lifetime. Converting it
    // to opaque lets WM stop and discard the shell surface underneath. On the
    // first predictive-back frame, setTranslucent(true) cannot recreate and
    // present that surface before the 1:1 card transform exposes it, leaving a
    // black ring for several frames. A normal app has no atomic cross-window
    // transaction that can wake the shell and move this content together, so
    // keeping the already-rendered shell surface alive is the only path that
    // preserves both the destination preview and direct finger tracking.

    PredictiveBackHandler(enabled = !nativeBack) { events ->
        if (state.committed) {
            events.collect { }
            return@PredictiveBackHandler
        }
        var initialTouchY = Float.NaN
        var sawGesture = false
        var operationOwner: Long? = null
        try {
            // A quick button-back can arrive while the previous gesture's
            // cancel spring is still running. Join that settle before touching
            // either Animatable, then synchronously restore the commit alpha.
            operationOwner = operationGuard.beginOperation()
            state.exit.snapTo(0f)
            events.collect { event ->
                if (!sawGesture) {
                    sawGesture = true
                    state.gestureActive = true
                    if (bridgeToShell) store.detailBackPhase.value = DetailBackPhase.Gesture
                }
                if (initialTouchY.isNaN()) initialTouchY = event.touchY
                state.swipeEdge = event.swipeEdge
                state.touchYDelta = event.touchY - initialTouchY
                // Direct 1:1 tracking, like the platform: the eased progress
                // IS the pose. No smoothing between finger and pixels.
                state.chased.snapTo(
                    YoinMotion.backGestureEasing.transform(event.progress),
                )
                if (bridgeToShell) {
                    store.detailBackProgress.floatValue = state.chased.value
                    store.detailBackTouchYDelta.floatValue = state.touchYDelta
                }
            }
            // Button and gesture commits share the same settle. The detail
            // stays translucent, so its actual source window is already live.
            operationGuard.markCommitted()
            state.committed = true
            state.gestureActive = false
            if (bridgeToShell) {
                store.detailBackPhase.value = DetailBackPhase.Committed
                store.setDetailChromeActive(false)
            }
            // Finish both the content dissolve and the bar's spatial motion
            // before handing the window back. A 140 ms timer used to dispose
            // the bar mid-spring; multiplying its alpha by five made the page
            // disappear in just a few frames.
            operationGuard.launchCommit(
                scope = scope,
                settle = {
                    coroutineScope {
                        launch { state.chased.animateTo(1f, commitSpec) }
                        launch {
                            state.exit.animateTo(1f, exitSpec)
                            // Page gone: only the bar is left, over its twin
                            // beneath. Hand the shadow back while both windows
                            // are still ours, so the system dissolve after
                            // finish() carries a bare bar, not a shadow whose
                            // fade another process times.
                            handBackBarShadow(exitSpec)
                        }
                    }
                },
                onFinish = onBack,
            ).join()
        } catch (e: CancellationException) {
            operationGuard.recoverCancellation(
                onCommittedCancellation = {
                    // A second back may cancel this handler's wait, but must
                    // not cut the visible settle short. The host-owned commit
                    // job finishes exactly once, including on host disposal.
                },
                onGestureCancellation = {
                    val owner = operationOwner
                    if (owner != null && state.gestureActive) {
                        // The handler coroutine is already dying; settle on the
                        // host scope. This job never touches [exit] (pre-commit
                        // exit is already zero), so it cannot cancel a later
                        // button commit. Generation guards its final Idle write.
                        operationGuard.launchCancelSettle(
                            scope = scope,
                            owner = owner,
                            settle = {
                                state.chased.animateTo(0f, settleSpec) {
                                    if (bridgeToShell) store.detailBackProgress.floatValue = value
                                }
                            },
                            onSettled = {
                                state.touchYDelta = 0f
                                if (bridgeToShell) store.detailBackTouchYDelta.floatValue = 0f
                                state.gestureActive = false
                                if (bridgeToShell && store.detailBackPhase.value == DetailBackPhase.Gesture) {
                                    store.detailBackPhase.value = DetailBackPhase.Idle
                                }
                            },
                        )
                    }
                }
            )
            throw e
        }
    }
    return state
}

/**
 * Applies [applyDetailWindowBackMode] when the pane's embedding changes. The
 * Activity's onCreate already set up Pattern B (translucent theme + close
 * dissolve), so nothing is re-applied until the first real switch.
 */
@Composable
private fun DetailWindowBackModeEffect(nativeBack: Boolean) {
    val activity = LocalContext.current.findActivityOrNull() ?: return
    val applied = remember(activity) { BooleanArray(1) }
    DisposableEffect(activity, nativeBack) {
        if (applied[0] != nativeBack) {
            activity.applyDetailWindowBackMode(nativeBack)
            applied[0] = nativeBack
        }
        onDispose { }
    }
}

/**
 * ARR high-refresh vote for the detail window while any of its cross-window
 * motion is live: the back-collapse scrub, the post-commit exit fade, or the
 * enter slide-in. Post-release settles have no touch boost, so without the
 * vote they pace at ARR-Normal (60Hz) on a 120Hz panel.
 */
@Composable
fun rememberDetailMotionFrameRateModifier(
    back: DetailBackCollapseState,
    intro: DetailEnterIntroState,
): Modifier {
    val active by remember(back, intro) {
        derivedStateOf {
            back.chased.value > 0.001f ||
                back.exit.value > 0.001f ||
                (intro.pageVisible && intro.slide.value > 0.001f)
        }
    }
    return Modifier.voteHighFrameRate(active)
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

        alpha = (1f - exit).coerceIn(0f, 1f)

        shape = RoundedCornerShape(BackMotionTokens.PopPageCornerRadius * p)
        clip = p > 0f
    }

/** AOSP `displayBoundsMargin` (8dp) — content never passes this margin. */
private const val DisplayBoundsMarginDp = 8f

/** Slight rightward drift during the post-commit fade. */
private const val ExitDriftDp = 24f
