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

/**
 * The NP entrance/exit. Rises from the bottom; with [fromEnd] (the Medium
 * side panel family, 断点交接 §3.4) it slides in from — and retreats to — the
 * right edge on the same spatial spring.
 */
@Composable
fun OverlayPlayerVisibility(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    fromEnd: Boolean = false,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = if (fromEnd) {
            YoinMotion.slideInHorizontally(role = YoinMotionRole.Expressive) { it }
        } else {
            YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it }
        } + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = if (fromEnd) {
            YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard) { it }
        } else {
            YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it }
        } + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        content = content,
    )
}
