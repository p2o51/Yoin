package com.gpo.yoin.ui.detail

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.gpo.yoin.ui.experience.DetailBackPhase
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
fun rememberDetailBackCollapse(onBack: () -> Unit): DetailBackCollapseState {
    val state = remember { DetailBackCollapseState() }
    val operationGuard = remember { DetailBackOperationGuard() }
    val scope = rememberCoroutineScope()
    val settleSpec = YoinMotion.predictiveBackSettleSpring<Float>()
    val commitSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as YoinApplication).container.experienceSessionStore
    }

    // Keep the detail window translucent for its whole lifetime. Converting it
    // to opaque lets WM stop and discard the shell surface underneath. On the
    // first predictive-back frame, setTranslucent(true) cannot recreate and
    // present that surface before the 1:1 card transform exposes it, leaving a
    // black ring for several frames. A normal app has no atomic cross-window
    // transaction that can wake the shell and move this content together, so
    // keeping the already-rendered shell surface alive is the only path that
    // preserves both the destination preview and direct finger tracking.

    PredictiveBackHandler { events ->
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
                    store.detailBackPhase.value = DetailBackPhase.Gesture
                }
                if (initialTouchY.isNaN()) initialTouchY = event.touchY
                state.swipeEdge = event.swipeEdge
                state.touchYDelta = event.touchY - initialTouchY
                // Direct 1:1 tracking, like the platform: the eased progress
                // IS the pose. No smoothing between finger and pixels.
                state.chased.snapTo(
                    YoinMotion.backGestureEasing.transform(event.progress),
                )
                store.detailBackProgress.floatValue = state.chased.value
                store.detailBackTouchYDelta.floatValue = state.touchYDelta
            }
            // COMMIT. Button-backs emit no progress events — skip straight to
            // the window dissolve. Gesture commits play the AOSP post-commit
            // content exit (the fade lands within ~90ms) first, while the
            // window beneath runs its own entering settle off the phase flip.
            //
            // The detail Activity stays translucent, so the shell surface is
            // already live before either a gesture or button commit reveals
            // it. No resume/readiness handshake is needed here.
            operationGuard.markCommitted()
            state.committed = true
            state.gestureActive = false
            store.detailBackPhase.value = DetailBackPhase.Committed
            if (sawGesture && state.chased.value > 0.02f) {
                // Finish the bar's scrub to full nav — the SAME spec the shell
                // bar uses for its commit settle beneath the dissolve, so the
                // two bars ride near-identical trajectories and the crossfade
                // shows no pose jump (a long drag froze the top bar near nav
                // while the bottom one started from split — the pill flash).
                scope.launch { state.chased.animateTo(1f, commitSpec) }
                state.exit.animateTo(1f, tween(durationMillis = 140))
            } else if (!sawGesture) {
                // Button-back: play the same commit motion from rest — the
                // card collapse + bar scrub (morph or slide-down) + content
                // fade. The live shell is already underneath the first frame.
                scope.launch { state.chased.animateTo(1f, commitSpec) }
                state.exit.animateTo(1f, tween(durationMillis = 140))
            }
            operationGuard.dispatchFinishOnce(onBack)
        } catch (e: CancellationException) {
            operationGuard.recoverCancellation(
                onCommittedCancellation = {
                    // A commit is terminal. Lifecycle/second-back cancellation
                    // may interrupt its cosmetic fade, but must neither reset
                    // the shared phase to Idle nor swallow the requested finish.
                    operationGuard.dispatchFinishOnce(onBack)
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
                                    store.detailBackProgress.floatValue = value
                                }
                            },
                            onSettled = {
                                state.touchYDelta = 0f
                                store.detailBackTouchYDelta.floatValue = 0f
                                state.gestureActive = false
                                if (store.detailBackPhase.value == DetailBackPhase.Gesture) {
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

        // AOSP post-commit: alpha = max(1 − 5·t, 0) — gone in the first fifth.
        alpha = max(1f - exit * 5f, 0f)

        shape = RoundedCornerShape(BackMotionTokens.PopPageCornerRadius * p)
        clip = p > 0f
    }

/** AOSP `displayBoundsMargin` (8dp) — content never passes this margin. */
private const val DisplayBoundsMarginDp = 8f

/** Slight rightward drift during the post-commit fade. */
private const val ExitDriftDp = 24f
