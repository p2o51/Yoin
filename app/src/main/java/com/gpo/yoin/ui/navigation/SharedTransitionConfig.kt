package com.gpo.yoin.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale

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
                get() = currentEnabled.value && transition.currentState != transition.targetState

            override val shouldKeepEnabledForOngoingAnimation: Boolean
                get() = false
        }
    }
}

/**
 * How the pill's title / artist and the hero's grow into each other: SCALED, each laid out once at its own
 * resting size. Remeasured to the flying bounds instead, the hero title was laid out ~150 x 20dp at the
 * start — narrow enough to marquee, so it was clipped to a strip and edge-faded — and popped to its full
 * block only when the bounds were wide enough at the end. Scaled by height from the start edge, the
 * glyphs grow with the bounds and land at their own size.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val NowPlayingHeroTextResize: SharedTransitionScope.ResizeMode =
    SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillHeight, Alignment.CenterStart)
