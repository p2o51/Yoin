package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/** Original independent visibility clock; shared bounds retain their native springs. */
@Composable
fun OverlayChromeVisibility(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    enabled: Boolean = true,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = !expanded && enabled,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}

/** The pre-rewrite NP entrance/exit, kept together for reference-frame verification. */
@Composable
fun OverlayPlayerVisibility(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it } +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        content = content,
    )
}
