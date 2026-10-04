package com.gpo.yoin.ui.detail

import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The forward half of the AOSP cross-activity motion: the incoming page's
 * CONTENT slides in from 96dp right on the platform's EMPHASIZED curve,
 * OPAQUE from its first frame — the AOSP push model, which is the only
 * choreography with no blank gap and no ghost:
 *
 *  - a window-wide alpha fade (the old system anim, then a Compose one)
 *    ghosts the whole outgoing page through the incoming one for the fade's
 *    full duration;
 *  - receding Home before the incoming is visible leaves a blank window;
 *  - an opaque slide covers the receding Home progressively, so neither
 *    artifact can exist.
 *
 * The window itself stays transparent by theme (the bar-morph hold shows the
 * shell untouched beneath it — the hold is free once nothing fades). The
 * shell mirrors the slide with its own 96dp recede
 * (rememberDetailBackEnteringModifier's covered rest), so open and
 * predictive-back are the same trajectory run in both directions.
 *
 * [barHandoff] launches from a root section hold the slide for 200ms
 * so the shell bar's nav→split morph reads as the tap feedback before the
 * incoming window covers it. Until Content/Error is ready, the page subtree is
 * not mounted at all: the translucent window contributes only the pixel-aligned
 * bottom bar, so the live source page remains visible instead of being covered
 * by a mostly-empty Loading scaffold. A 700ms fallback deliberately reveals
 * the real Loading state for a genuinely slow/offline request rather than
 * leaving an apparently dead tap forever.
 *
 * The tick that releases the shell's recede fires only after the first mounted,
 * fully opaque page buffer has COMMITTED to the swap chain. A frame-clock
 * callback alone proves neither that the target content was mounted nor that a
 * buffer reached SurfaceFlinger. Now Playing and detail→detail pushes use the same readiness
 * boundary without the shell's 200ms bar hold. Plays once per Activity
 * (rememberSaveable), so rotation doesn't replay it.
 *
 * Pages composed in the shell's detail column ([DetailHostMode.Pane]) skip
 * all of it: the pane itself slides in, and its NavDisplay animates pushes
 * inside it, so a second slide would stack on that one; the page mounts at
 * once (the spinner then waits, see [DetailLoadingIndicator]).
 */
@Stable
class DetailEnterIntroState internal constructor(
    alreadyPlayed: Boolean,
    /** Composed in the detail column: mounted at once, so its spinner waits (see [DetailLoadingIndicator]). */
    internal val mountedEarly: Boolean = false,
) {
    internal val slide = Animatable(if (alreadyPlayed) 0f else 1f)
    var pageVisible by mutableStateOf(alreadyPlayed)
        internal set
    internal var slideReleased by mutableStateOf(alreadyPlayed)
        internal set

    /** The entrance sequence has finished, released or not (timeout, back abort). */
    internal var entranceResolved by mutableStateOf(alreadyPlayed)
        internal set
    internal var pageMounted by mutableStateOf(alreadyPlayed)
        private set

    internal fun notePageMounted() {
        pageMounted = true
    }
}

/**
 * A detail page's loading affordance. Gated pages mount Loading only after the
 * visual-ready window has already passed, so they show it at once; a page
 * opened into a split pane mounts immediately (nothing behind it to show
 * instead), so its spinner waits out that same window — a cached page fades
 * from the bare page surface straight to content, never through a spinner flash.
 */
@Composable
internal fun DetailLoadingIndicator(intro: DetailEnterIntroState, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(!intro.mountedEarly) }
    LaunchedEffect(Unit) {
        if (!show) {
            delay(VISUAL_READY_TIMEOUT_MS)
            show = true
        }
    }
    AnimatedVisibility(
        visible = show,
        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = ExitTransition.None,
        modifier = modifier,
    ) {
        YoinLoadingIndicator()
    }
}

/** Must live inside the conditional page subtree, not beside it. */
@Composable
internal fun DetailEnterPageMountEffect(state: DetailEnterIntroState) {
    SideEffect { state.notePageMounted() }
}

@Composable
fun rememberDetailEnterIntro(
    barHandoff: Boolean,
    visualReady: Boolean,
    back: DetailBackCollapseState,
): DetailEnterIntroState {
    // Previews render the settled end state directly (no enter animation).
    val inspectionMode = LocalInspectionMode.current
    val paneHosted = LocalDetailHostMode.current == DetailHostMode.Pane
    var played by rememberSaveable { mutableStateOf(inspectionMode || paneHosted) }
    val state = remember { DetailEnterIntroState(played, mountedEarly = paneHosted && !inspectionMode) }
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as YoinApplication).container.experienceSessionStore
    }
    val view = LocalView.current
    val currentVisualReady = rememberUpdatedState(visualReady)
    LaunchedEffect(Unit) {
        if (!played) {
            val started = coordinateDetailEnter(
                barHandoff = barHandoff,
                nowMillis = SystemClock::uptimeMillis,
                awaitInitialCommit = { awaitNextFrameCommit(view) },
                awaitVisualReadyOrTimeout = {
                    withTimeoutOrNull(VISUAL_READY_TIMEOUT_MS) {
                        snapshotFlow { currentVisualReady.value }.first { it }
                    } != null
                },
                delayMillis = { delay(it) },
                awaitBackIdleOrAbort = {
                    snapshotFlow {
                        when {
                            back.committed -> EnterBackGate.Abort
                            back.gestureActive -> EnterBackGate.Wait
                            else -> EnterBackGate.Proceed
                        }
                    }.first { it != EnterBackGate.Wait } == EnterBackGate.Proceed
                },
                revealPage = { state.pageVisible = true },
                hidePage = { state.pageVisible = false },
                // SideEffect is emitted from inside the conditional subtree,
                // so this proves that the exact branch was applied before a
                // frame-commit callback can be registered.
                awaitPageMount = {
                    withTimeoutOrNull(PAGE_MOUNT_TIMEOUT_MS) {
                        snapshotFlow { state.pageMounted }.first { it }
                    } != null
                },
                awaitVisibleCommit = { awaitNextFrameCommit(view) },
                settlePageWithoutTick = {
                    // A missing callback must never release the shell. Leave a
                    // usable, fully positioned page as the conservative
                    // fallback; if a buffer later lands it covers the source
                    // opaquely instead of exposing a 96dp gap.
                    state.slide.snapTo(0f)
                },
                noteSlideStarted = {
                    if (barHandoff) store.noteDetailEnterSlideStarted()
                },
            )
            if (started) state.slideReleased = true
            state.entranceResolved = true
        }
    }
    // A back gesture can interrupt an unfinished entrance. Freeze its current
    // pose while back owns the surface; cancellation resumes the same entrance
    // path instead of letting entry and back move the page in opposite directions.
    LaunchedEffect(state.slideReleased, back.gestureActive, back.committed) {
        if (state.slideReleased && !back.gestureActive && !back.committed && !played) {
            state.slide.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = ENTER_DURATION_MS,
                    easing = BackMotionTokens.EmphasizedEasing,
                ),
            )
            played = true
        }
    }
    return state
}

/**
 * Orders the cross-window hand-off without depending on Compose/View classes,
 * which keeps the safety invariant directly unit-testable.
 */
internal suspend fun coordinateDetailEnter(
    barHandoff: Boolean,
    nowMillis: () -> Long,
    awaitInitialCommit: suspend () -> Boolean,
    awaitVisualReadyOrTimeout: suspend () -> Boolean,
    delayMillis: suspend (Long) -> Unit,
    awaitBackIdleOrAbort: suspend () -> Boolean,
    revealPage: () -> Unit,
    hidePage: () -> Unit,
    awaitPageMount: suspend () -> Boolean,
    awaitVisibleCommit: suspend () -> Boolean,
    settlePageWithoutTick: suspend () -> Unit,
    noteSlideStarted: () -> Unit,
): Boolean {
    // This commit keeps the detail surface alive while only its pixel-aligned
    // bar is mounted. Failure is bounded and non-fatal: the visible page
    // commit below is the authoritative release boundary.
    awaitInitialCommit()
    val holdDeadline = nowMillis() + if (barHandoff) BAR_HANDOFF_HOLD_MS else 0L
    awaitVisualReadyOrTimeout()
    if (!awaitBackIdleOrAbort()) return false
    val remainingHold = (holdDeadline - nowMillis()).coerceAtLeast(0L)
    if (remainingHold > 0L) delayMillis(remainingHold)
    if (!awaitBackIdleOrAbort()) return false
    revealPage()
    if (!awaitPageMount()) {
        settlePageWithoutTick()
        return false
    }
    if (!awaitBackIdleOrAbort()) {
        hidePage()
        return false
    }
    if (!awaitVisibleCommit()) {
        settlePageWithoutTick()
        return false
    }
    // Covers a gesture/commit that began while the render callback was
    // outstanding. This is the final suspension point before the tick, so
    // the main thread cannot interleave another back event afterward.
    if (!awaitBackIdleOrAbort()) {
        hidePage()
        return false
    }
    noteSlideStarted()
    return true
}

private suspend fun awaitNextFrameCommit(view: View): Boolean =
    withTimeoutOrNull(FRAME_COMMIT_TIMEOUT_MS) {
        if (!view.isAttachedToWindow) return@withTimeoutOrNull false
        if (Build.VERSION.SDK_INT < 29 || !view.isHardwareAccelerated) {
            // API 29 introduced frame-commit callbacks. A pre-29/software
            // window has no stronger public primitive; a posted draw is the
            // bounded best effort and still never releases on detach.
            suspendCancellableCoroutine { continuation ->
                view.postOnAnimation {
                    if (continuation.isActive) {
                        continuation.resume(view.isAttachedToWindow)
                    }
                }
            }
        } else {
            suspendCancellableCoroutine { continuation ->
                val observer = view.viewTreeObserver
                if (!observer.isAlive) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                val callback = Runnable {
                    if (continuation.isActive) continuation.resume(true)
                }
                continuation.invokeOnCancellation {
                    if (observer.isAlive) {
                        runCatching { observer.unregisterFrameCommitCallback(callback) }
                    }
                }
                try {
                    observer.registerFrameCommitCallback(callback)
                    // If composition landed just after the current traversal
                    // was queued, request one more frame explicitly.
                    view.postInvalidateOnAnimation()
                } catch (_: IllegalStateException) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    } ?: false

private enum class EnterBackGate { Wait, Proceed, Abort }

/** Slide only — apply to the page CONTENT container (never the bottom bar). */
fun Modifier.detailEnterIntroTransform(state: DetailEnterIntroState): Modifier =
    graphicsLayer {
        val s = state.slide.value
        if (s <= 0f) return@graphicsLayer
        translationX = ENTER_START_OFFSET_DP * density * s
    }

/** Mirror of the back gesture's entering start offset (AOSP 96dp). */
private const val ENTER_START_OFFSET_DP = 96f

/** AOSP cross-activity open duration. Back completion uses shared springs. */
private const val ENTER_DURATION_MS = 450

/** The bar-morph hold — the tap feedback beat before the slide covers it. */
private const val BAR_HANDOFF_HOLD_MS = 200L

/** Delay progress UI so ordinary launches reveal real detail content first. */
internal const val VISUAL_READY_TIMEOUT_MS = 700L

/** Every render handshake is bounded so a detached/paused view cannot hang. */
private const val FRAME_COMMIT_TIMEOUT_MS = 1_500L

/** SideEffect should land in one apply pass; keep a conservative busy-device bound. */
private const val PAGE_MOUNT_TIMEOUT_MS = 1_500L
