package com.gpo.yoin.ui.navigation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.CoveredWindowAnimationGate
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hides the shell's bottom bar while the Home feed scrolls down and brings it
 * back on the way up (owner 2026-11-16).
 *
 * One progress, [fraction] (0 shown, 1 hidden), and two inputs that both feed
 * it: the feed's scroll moves it 1:1 with the content (only scroll the list
 * really took, so a feed too short to scroll never hides the bar), and the end
 * of a gesture settles it on a spatial spring to whichever end it is nearer, or
 * the way a fling was heading. A pull past the top always reveals.
 *
 * The bar reads [fraction] in its layer only, so scrolling never recomposes it.
 */
@Stable
class ShellBarScrollHide internal constructor(
    private val scope: CoroutineScope,
    private val distancePx: Float,
) {
    /** The theme's spatial spring, refreshed from composition (motion role can change). */
    internal lateinit var settleSpec: FiniteAnimationSpec<Float>

    var fraction by mutableFloatStateOf(0f)
        private set

    /** Off outside the plain Home feed (edit mode, Memories, Now Playing, …). */
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) settleTo(0f)
        }

    private var settleJob: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled) return Offset.Zero
            // Content moving up (finger up / fling down the feed) hides; any
            // move down, or a pull past the top the list could not take, shows.
            val delta = consumed.y + available.y.coerceAtLeast(0f)
            if (delta != 0f) {
                settleJob?.cancel()
                fraction = (fraction - delta / distancePx).coerceIn(0f, 1f)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!enabled) return Velocity.Zero
            if (fraction <= 0f || fraction >= 1f) return Velocity.Zero
            val velocity = consumed.y + available.y
            val target = when {
                velocity < -FlingDecisionVelocity -> 1f
                velocity > FlingDecisionVelocity -> 0f
                else -> if (fraction >= 0.5f) 1f else 0f
            }
            settleTo(target)
            return Velocity.Zero
        }
    }

    /** Brings the bar back, e.g. when Home is shown again. */
    fun show() = settleTo(0f)

    /** Puts the bar back with no motion, for when nothing of this window is on screen. */
    fun showUnseen() {
        settleJob?.cancel()
        fraction = 0f
    }

    /**
     * A page above has started to reveal the window [revealedKey] names
     * ([com.gpo.yoin.ui.experience.ExperienceSessionStore.windowBeneathRevealed]).
     * When that is the shell, the bar is put back unseen: on a back's first
     * frame the page still covers the whole window, and the page's own bar
     * rests where this one does, so the hand-back finds a bar under it instead
     * of an empty spot that this bar then rises into after the page is gone.
     */
    fun onWindowRevealed(revealedKey: String?) {
        if (revealedKey == CoveredWindowAnimationGate.ShellWindowKey ||
            revealedKey == CoveredWindowAnimationGate.AnyWindow
        ) {
            showUnseen()
        }
    }

    private fun settleTo(target: Float) {
        if (fraction == target) return
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = fraction,
                targetValue = target,
                animationSpec = settleSpec,
            ) { value, _ -> fraction = value.coerceIn(0f, 1f) }
        }
    }

    companion object {
        /** How far the feed scrolls to take the bar fully out. */
        val HideDistance: Dp = 96.dp

        /** px/s past which a fling decides the bar's end over its position. */
        private const val FlingDecisionVelocity = 300f
    }
}

@Composable
fun rememberShellBarScrollHide(): ShellBarScrollHide {
    val scope = rememberCoroutineScope()
    val distancePx = with(LocalDensity.current) { ShellBarScrollHide.HideDistance.toPx() }
    val settleSpec = YoinMotion.spatialSpring<Float>()
    return remember(scope, distancePx) { ShellBarScrollHide(scope, distancePx) }
        .also { it.settleSpec = settleSpec }
}
