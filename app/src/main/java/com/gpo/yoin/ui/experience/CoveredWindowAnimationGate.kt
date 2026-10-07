package com.gpo.yoin.ui.experience

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.YoinApplication
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Stops a Yoin window's animations while another Yoin window covers it.
 *
 * Detail Activities stay translucent for their whole life (an opaque detail lets WM discard the window beneath,
 * and the first back frame then shows a black ring — see `rememberDetailBackCollapse`). The window beneath is
 * therefore only PAUSED, never STOPPED, and Compose pauses a window's frame clock on ON_STOP only. Measured on the
 * Pixel Tablet (2026-10-06): with an album page open over Home while music played, the hidden shell redrew every
 * vsync (the mini player's wave) — ~4.5 ms of main-thread draw per frame on the thread the visible window shares,
 * plus a second full-screen window for the GPU. The expanded lyrics' back gesture janked on it, and it ran for as
 * long as any detail page stayed open.
 *
 * So once a window has been covered BY ANOTHER YOIN WINDOW (a resumed gated Activity — never a system share
 * sheet, a dialog or a split-screen peer, under which it is still seen) for [FreezeDelayMs] (past every forward
 * hand-off ride) with nothing revealing it, its Recomposer's frame clock is paused: animations stop, while
 * recomposition and draw-phase invalidations (the back pose read inside graphicsLayer) still run, and the surface
 * keeps its last frame for the next reveal. It thaws when the detail directly above starts to reveal it
 * ([ExperienceSessionStore.windowBeneathRevealed] carries the revealed window's key — a nested detail's back
 * does not wake the shell two windows down), on ON_RESUME, and on ON_START / ON_STOP (a stopped window is
 * Compose's own business; a restarted one is being shown).
 *
 * A frozen window should not draw. One that keeps drawing ([BusyDraws] frames inside [BusyWindowMs]) was frozen in
 * the middle of a transition that re-requests layout until it lands — measured: the bar's idle → pill morph, when
 * playback started under a covering page, recomposed the shell every frame while frozen. It is thawed for
 * [SettleMs] to land, then frozen again; one still busy right after that refreeze backs off further, while a
 * later, separate episode starts the back-off over.
 */
internal class CoveredWindowAnimationGate(
    private val activity: ComponentActivity,
    /** This window's key: what a detail directly above it names when it reveals it. */
    private val windowKey: String,
    private val revealed: StateFlow<String?>,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val freeze = Runnable { freezeIfCovered() }
    private val settle = Runnable { settleBusyWindow() }
    private var frozen: Recomposer? = null
    private var frozenAt = 0L

    /** Thaws for one busy episode (consecutive refreezes that stay busy), for the back-off. */
    private var busyThaws = 0
    private var drawWindowStart = 0L
    private var drawsInWindow = 0
    private var drawWatchInstalled = false
    private val drawWatch = ViewTreeObserver.OnDrawListener {
        if (frozen == null) return@OnDrawListener
        val now = SystemClock.uptimeMillis()
        if (now - drawWindowStart > BusyWindowMs) {
            drawWindowStart = now
            drawsInWindow = 0
        }
        if (++drawsInWindow == BusyDraws) handler.post(settle)
    }

    /**
     * True while this window is frozen ([LocalWindowCovered]). State shared with the visible window — the pill
     * wave, which that window keeps advancing — is not read while it is: a draw-phase read would still redraw
     * this window on every one of the other window's frames.
     */
    val covered = mutableStateOf(false)

    private val isResumed: Boolean get() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** Seen but not in front: started, not resumed. */
    private val isPausedVisible: Boolean
        get() = activity.lifecycle.currentState == Lifecycle.State.STARTED

    override fun onStart(owner: LifecycleOwner) = thawAndRearm()

    override fun onPause(owner: LifecycleOwner) {
        busyThaws = 0
        schedule(FreezeDelayMs)
    }

    override fun onResume(owner: LifecycleOwner) {
        cancel()
        thaw()
        // A Yoin window came to the front: the paused Yoin windows beneath it are covered by Yoin again.
        gates.forEach { gate -> if (gate !== this) gate.onPeerResumed() }
    }

    override fun onStop(owner: LifecycleOwner) {
        // Stopped: Compose pauses this window's clock itself and it does not draw. Holding it frozen would only
        // leave the next reveal (an ON_START back preview) on stale state.
        cancel()
        thaw()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        cancel()
        thaw()
        removeDrawWatch()
        gates.remove(this)
    }

    /** The window being revealed changed ([ExperienceSessionStore.windowBeneathRevealed]). */
    fun onRevealChanged(revealedKey: String?) {
        if (isRevealed(revealedKey)) {
            cancel()
            thaw()
        } else if (isPausedVisible) {
            schedule(FreezeDelayMs)
        }
    }

    private fun onPeerResumed() {
        if (isPausedVisible) schedule(FreezeDelayMs)
    }

    private fun thawAndRearm() {
        cancel()
        thaw()
        if (isPausedVisible) schedule(FreezeDelayMs)
    }

    private fun isRevealed(revealedKey: String?): Boolean = revealedKey == windowKey || revealedKey == AnyWindow

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(freeze)
        handler.postDelayed(freeze, delayMs)
    }

    private fun cancel() {
        handler.removeCallbacks(freeze)
        handler.removeCallbacks(settle)
    }

    private fun freezeIfCovered() {
        if (frozen != null || activity.isFinishing || !isPausedVisible) return
        if (isRevealed(revealed.value)) return
        // Only another Yoin window in front is a cover: under a system sheet, a dialog or a split-screen peer this
        // window is still seen.
        if (gates.none { gate -> gate !== this && gate.isResumed && !gate.activity.isFinishing }) return
        // setContent's ComposeView is the content root's first child; its window Recomposer is its context.
        val root = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val recomposer = root.compositionContext as? Recomposer ?: return
        installDrawWatch()
        frozenAt = SystemClock.uptimeMillis()
        drawWindowStart = frozenAt
        drawsInWindow = 0
        recomposer.pauseCompositionFrameClock()
        frozen = recomposer
        covered.value = true
    }

    private fun thaw() {
        frozen?.resumeCompositionFrameClock()
        frozen = null
        covered.value = false
    }

    /** Still drawing while frozen: let what's mid-flight land, then try again. */
    private fun settleBusyWindow() {
        if (frozen == null) return
        // Busy again right after a settle's refreeze: the same episode, back off further. Busy after a quiet
        // stretch: a new episode, start over.
        if (SystemClock.uptimeMillis() - frozenAt > 2 * BusyWindowMs) busyThaws = 0
        thaw()
        busyThaws++
        schedule(SettleMs * (1L shl (busyThaws - 1).coerceAtMost(MaxBackoffShift)))
    }

    private fun installDrawWatch() {
        if (drawWatchInstalled) return
        val observer = activity.window.decorView.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnDrawListener(drawWatch)
        drawWatchInstalled = true
    }

    private fun removeDrawWatch() {
        if (!drawWatchInstalled) return
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnDrawListener(drawWatch)
        drawWatchInstalled = false
    }

    companion object {
        /** Live gates, in no particular order (main thread only). Removed on ON_DESTROY. */
        private val gates = mutableListOf<CoveredWindowAnimationGate>()

        /** The shell's window key (MainActivity carries none of its own). */
        const val ShellWindowKey = "shell"

        /** A reveal from a detail that does not know its window beneath: thaws every covered window. */
        const val AnyWindow = "*"

        /** [activity]'s covered flag; a window without a gate is never covered. */
        internal fun coveredState(activity: Activity?): State<Boolean> =
            gates.firstOrNull { it.activity === activity }?.covered ?: NeverCovered

        internal fun register(gate: CoveredWindowAnimationGate) {
            gates += gate
        }

        private val NeverCovered: State<Boolean> = mutableStateOf(false)

        /**
         * Longer than the forward hand-off on a slow launch (launch latency + the 200 ms bar hold + the 450 ms
         * recede ride the shell plays beneath an entering detail).
         */
        const val FreezeDelayMs = 2_500L

        /** A frozen window drawing this many frames inside [BusyWindowMs] is busy (≥ 16 fps sustained). */
        const val BusyDraws = 8
        const val BusyWindowMs = 500L

        /** How long a busy window runs before it is frozen again; doubles while the same episode stays busy. */
        const val SettleMs = 1_500L
        private const val MaxBackoffShift = 4
    }
}

/**
 * This window is frozen under a covering Yoin window ([CoveredWindowAnimationGate.covered]). Read it before
 * reading per-frame state another window drives (and hold the last value while it is true), or to land a motion
 * instantly instead of queuing it on the paused clock.
 */
internal val LocalWindowCovered = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * Installs [CoveredWindowAnimationGate] on a window a translucent Yoin detail Activity can cover: the shell and
 * every detail Activity. Call once from onCreate. [windowKey] is this window's key: [CoveredWindowAnimationGate
 * .ShellWindowKey] for the shell, the detail's own key (stamped on its intent at launch) for a detail.
 */
fun ComponentActivity.installCoveredWindowAnimationGate(windowKey: String) {
    val revealed = (application as YoinApplication).container.experienceSessionStore.windowBeneathRevealed
    val gate = CoveredWindowAnimationGate(this, windowKey, revealed)
    CoveredWindowAnimationGate.register(gate)
    lifecycle.addObserver(gate)
    lifecycleScope.launch { revealed.collect(gate::onRevealChanged) }
}
