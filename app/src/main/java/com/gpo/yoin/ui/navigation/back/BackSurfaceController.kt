package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.theme.YoinMotion

@Stable
class BackSurfaceController(
    private val animator: suspend (Float, Float, (Float) -> Unit) -> Unit = { from, to, onValue ->
        animate(
            initialValue = from,
            targetValue = to,
            animationSpec = YoinMotion.predictiveBackSettleSpring(),
        ) { value, _ ->
            onValue(value)
        }
    },
) {
    var fraction by mutableFloatStateOf(0f)
        private set

    private var transitionInFlight = false
    private var committed = false

    fun updateFromSystemBack(raw: Float) {
        committed = false
        fraction = normalizeBackProgress(raw)
    }

    fun updateFromDrag(fraction: Float) {
        committed = false
        this.fraction = fraction.coerceIn(0f, 1f)
    }

    fun snapTo(fraction: Float) {
        committed = false
        this.fraction = fraction.coerceIn(0f, 1f)
    }

    suspend fun animateCommit(onCommitted: () -> Unit) {
        if (transitionInFlight || committed) return

        transitionInFlight = true
        try {
            if (fraction < 1f) {
                animator(fraction, 1f) { value ->
                    fraction = value
                }
            } else {
                fraction = 1f
            }
            committed = true
            onCommitted()
        } finally {
            transitionInFlight = false
        }
    }

    suspend fun commitImmediately(onCommitted: () -> Unit) {
        if (transitionInFlight || committed) return

        committed = true
        onCommitted()
    }

    suspend fun animateCancel() {
        if (transitionInFlight) return

        committed = false
        if (fraction <= 0f) {
            fraction = 0f
            return
        }

        transitionInFlight = true
        try {
            animator(fraction, 0f) { value ->
                fraction = value
            }
            fraction = 0f
        } finally {
            transitionInFlight = false
        }
    }

    fun reset() {
        committed = false
        fraction = 0f
    }
}

@Composable
fun rememberBackSurfaceController(): BackSurfaceController = remember {
    BackSurfaceController()
}
