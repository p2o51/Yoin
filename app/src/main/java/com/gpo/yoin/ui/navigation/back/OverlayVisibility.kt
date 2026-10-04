package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.ui.component.SeamBarFieldShown
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Original independent visibility clock; shared bounds retain their native springs.
 * The bar's halftone field (seams' bottom field) follows the same target, so it
 * fades as Now Playing starts to rise and grows back once it has collapsed.
 */
@Composable
fun OverlayChromeVisibility(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    enabled: Boolean = true,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    SeamBarFieldShown(shown = !expanded && enabled)
    AnimatedVisibility(
        visible = !expanded && enabled,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}

/**
 * The NP entrance/exit. Rises from the bottom; with [fromEnd] (the side panel
 * family on Medium and Wide, adaptive principle 4) it slides in from — and
 * retreats to — the END edge, by [endTravel] of the full width (the panel's
 * own width, so it moves exactly like the content it yields to), on the
 * Standard spatial spring both ways and without a fade: the content beside
 * it narrows / widens on that same spring and distance (NowPlayingPanelInset),
 * so the two edges stay together — and a translucent panel would show the
 * window behind it.
 */
@Composable
fun OverlayPlayerVisibility(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    fromEnd: Boolean = false,
    endTravel: (fullWidth: Int) -> Int = { it },
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val towardEnd: (Int) -> Int = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        { full -> -endTravel(full) }
    } else {
        endTravel
    }
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = if (fromEnd) {
            YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard, initialOffsetX = towardEnd)
        } else {
            YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard)
        },
        exit = if (fromEnd) {
            YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard, targetOffsetX = towardEnd)
        } else {
            YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it } +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        content = content,
    )
}
