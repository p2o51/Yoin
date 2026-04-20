package com.gpo.yoin.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberActiveOnlySharedContentConfig(
    animatedVisibilityScope: AnimatedVisibilityScope,
): SharedTransitionScope.SharedContentConfig {
    val transition = animatedVisibilityScope.transition
    return remember(animatedVisibilityScope) {
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                get() = transition.currentState != transition.targetState

            override val shouldKeepEnabledForOngoingAnimation: Boolean
                get() = false
        }
    }
}
