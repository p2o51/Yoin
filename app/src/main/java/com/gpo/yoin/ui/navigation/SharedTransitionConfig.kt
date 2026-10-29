package com.gpo.yoin.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberActiveOnlySharedContentConfig(
    animatedVisibilityScope: AnimatedVisibilityScope,
    enabled: Boolean = true,
): SharedTransitionScope.SharedContentConfig {
    val transition = animatedVisibilityScope.transition
    val currentEnabled = rememberUpdatedState(enabled)
    return remember(animatedVisibilityScope) {
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                // A reversed transition (Now Playing reopened mid-close) reads
                // currentState == targetState while its springs still run; the
                // state pair alone drops this side while its peer stays in, and
                // the peer is drawn alone at its resting bounds.
                get() = currentEnabled.value &&
                    (transition.currentState != transition.targetState || transition.isRunning)

            override val shouldKeepEnabledForOngoingAnimation: Boolean
                get() = false
        }
    }
}
