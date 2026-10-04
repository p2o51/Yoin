package com.gpo.yoin.ui.navigation.pane

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Geometry of the shell | detail columns (adaptive principle 3). Pure and
 * unit-tested: the shell lays the two columns out from ONE fraction, the
 * drag handle edits that fraction 1:1, and every clamp lives here — pages
 * never know how wide the window is, only how wide their column is.
 */

/** Air between the two columns; the drag handle sits in it (M3 list-detail spacing). */
val PaneGutterWidth = 24.dp

/** Both columns keep at least a phone column — the Now Playing panel's floor. */
val PaneMinWidth = 360.dp

/**
 * What two columns need side by side: the floor the Now Playing side panel
 * must leave beside itself while the detail column is open (adaptive
 * principle 4) — narrower than this, the panel cannot open there.
 */
val TwoColumnsMinWidth = PaneMinWidth * 2 + PaneGutterWidth

/** From this window width the shell column keeps at least 600dp (1280 → 600 | 24 | 656). */
private val ShellPreferredFromWindow = 1080.dp
private val ShellPreferredWidth = 600.dp
private const val DefaultShellFraction = 0.45f

/** The shell's share of the column content (window − gutter) before the user drags. */
fun defaultShellFraction(windowWidth: Dp): Float {
    val content = windowWidth - PaneGutterWidth
    if (content <= 0.dp) return DefaultShellFraction
    return if (windowWidth >= ShellPreferredFromWindow) {
        maxOf(DefaultShellFraction, ShellPreferredWidth / content)
    } else {
        DefaultShellFraction
    }
}

/** The drag range: neither column may fall under [PaneMinWidth]. */
fun shellFractionBounds(windowWidth: Dp): ClosedFloatingPointRange<Float> {
    val content = windowWidth - PaneGutterWidth
    if (content <= PaneMinWidth * 2) return 0.5f..0.5f
    val min = PaneMinWidth / content
    return min..(1f - min)
}

@Immutable
data class PaneBudget(
    val shellWidth: Dp,
    val paneWidth: Dp,
    /** The fraction actually applied (the requested one, clamped). */
    val shellFraction: Float,
)

/** Column widths for [windowWidth] at a requested shell [shellFraction] (clamped). */
fun resolvePaneBudget(windowWidth: Dp, shellFraction: Float): PaneBudget {
    val content = (windowWidth - PaneGutterWidth).coerceAtLeast(0.dp)
    val bounds = shellFractionBounds(windowWidth)
    val fraction = shellFraction.coerceIn(bounds.start, bounds.endInclusive)
    val shell = content * fraction
    return PaneBudget(
        shellWidth = shell,
        paneWidth = content - shell,
        shellFraction = fraction,
    )
}

/**
 * The user's split, edited 1:1 by the handle. A plain fraction (no spring):
 * the finger owns the divider while it is down and the clamp is the only
 * physics. Null until the first drag — the window's default applies.
 */
@Stable
class PaneSplitState internal constructor() {
    var shellFraction: Float? by mutableStateOf(null)
        private set

    /** True while the handle is held (the handle grows, columns re-lay per frame). */
    var dragging by mutableStateOf(false)
        internal set

    /**
     * The columns region's width at its last measure (written by the
     * columns layout, read by the handle when a drag delta lands). Not
     * snapshot state: nothing composes from it.
     */
    var lastRegionWidth: Dp = 0.dp
        internal set

    /** Resolve the applied fraction for [windowWidth]. */
    fun fractionFor(windowWidth: Dp): Float =
        shellFraction ?: defaultShellFraction(windowWidth)

    /** Move the divider by [deltaDp] inside a window of [windowWidth] (1:1, clamped). */
    fun dragBy(deltaDp: Dp, windowWidth: Dp) {
        val content = windowWidth - PaneGutterWidth
        if (content <= 0.dp) return
        val current = fractionFor(windowWidth)
        val bounds = shellFractionBounds(windowWidth)
        shellFraction = (current + deltaDp / content).coerceIn(bounds.start, bounds.endInclusive)
    }
}
