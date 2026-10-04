package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.CenteredBarBottomMargin
import com.gpo.yoin.ui.experience.CenteredBarButtonHeight
import com.gpo.yoin.ui.experience.CenteredBarHeight
import com.gpo.yoin.ui.experience.FloatingBarHeight
import com.gpo.yoin.ui.experience.CenteredBarHorizontalMargin
import com.gpo.yoin.ui.experience.CenteredBarMaxWidth
import com.gpo.yoin.ui.experience.PortraitBarVerticalMargin

/**
 * The floating bottom bar scaffold — outer margins, pill Surface, and inner
 * row metrics — shared VERBATIM by the shell Button Group and the detail
 * pages' bottom bar. Their opaque fills overlap during window handoff. The
 * bar casts no shadow: content runs on under it as the seams' bottom field
 * (dissolve-final §1.2), so the Surface reports itself as the window's bar.
 *
 * The content lambda receives the inner row's width and the slot's full
 * inner width (they differ only while [barWidth] narrows the surface), so
 * callers can size slots in absolute dp (the shell's morph interpolates
 * widths by hand — plain Row/Box only, exotic measure policies hang under
 * the shell's shared-transition lookahead). No implicit child spacing:
 * callers own their gaps.
 *
 * [centered] is ShellChromeForm.CenteredBar (断点交接 §2.3): the same bar,
 * horizontally centred, capped at [widthCap] (600dp; the merged pose over two
 * columns widens the cap), [bottomMargin] above the nav bar. [barWidth]
 * (optional) narrows the pill surface inside that slot — the Now Playing side
 * panel folds the bar down to its two nav buttons with it.
 *
 * [widthCap] and [barWidth] are read inside the slot's own measure /
 * subcomposition, never in the caller's composition: the bar's hottest
 * inputs are gesture-driven (predictive-back scrub, column spring), so a
 * frame of those must only re-run the bar's leaf scope.
 */
@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    bottomMargin: Dp = CenteredBarBottomMargin,
    widthCap: () -> Dp = { CenteredBarMaxWidth },
    barWidth: ((slotWidth: Dp) -> Dp)? = null,
    // Drawn over the slot; receives the surface width so a caller can anchor
    // to a slot inside the (centred, possibly narrowed) surface.
    overlay: @Composable BoxWithConstraintsScope.(surfaceWidth: Dp) -> Unit = {},
    content: @Composable RowScope.(innerWidth: Dp, slotInnerWidth: Dp) -> Unit,
) {
    val slot = if (centered) {
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = CenteredBarHorizontalMargin,
                end = CenteredBarHorizontalMargin,
                top = PortraitBarVerticalMargin,
                bottom = bottomMargin,
            )
            .wrapContentWidth(align = Alignment.CenterHorizontally)
            .lazyMaxWidth(widthCap)
    } else {
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = PortraitBarVerticalMargin)
    }
    BoxWithConstraints(modifier = slot) {
        val slotWidth = this.maxWidth
        val surfaceWidth = barWidth?.invoke(slotWidth)?.coerceAtMost(slotWidth) ?: slotWidth
        val innerWidth = surfaceWidth - FloatingBarRowPadding * 2
        val slotInnerWidth = slotWidth - FloatingBarRowPadding * 2
        // The centred form is a step lower (60 = 44 + 8 × 2); portrait keeps 68.
        val rowHeight = if (centered) CenteredBarHeight else FloatingBarHeight
        val verticalPadding = (rowHeight - floatingBarButtonHeight(centered)) / 2
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (barWidth != null) Modifier.width(surfaceWidth) else Modifier.fillMaxWidth())
                .seamBarSource(MaterialTheme.shapes.extraLarge, barColor),
            shape = MaterialTheme.shapes.extraLarge,
            color = barColor,
            tonalElevation = 8.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = FloatingBarRowPadding, vertical = verticalPadding),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content(innerWidth, slotInnerWidth)
            }
        }
        overlay(surfaceWidth)
    }
}

/** `widthIn(max = …)` whose value is read at measure time, not composition. */
private fun Modifier.lazyMaxWidth(maxWidth: () -> Dp): Modifier = layout { measurable, constraints ->
    val maxPx = maxWidth().roundToPx().coerceAtLeast(constraints.minWidth)
    val placeable = measurable.measure(
        constraints.copy(maxWidth = minOf(constraints.maxWidth, maxPx)),
    )
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** The inner row's horizontal padding, each side. */
val FloatingBarRowPadding = 10.dp

/** Height of fixed-height children inside the portrait bar's inner row. */
val FloatingBarButtonHeight = 48.dp

/** The row's button height per form: 48 in the portrait bar, 44 in the centred one. */
fun floatingBarButtonHeight(centered: Boolean): Dp =
    if (centered) CenteredBarButtonHeight else FloatingBarButtonHeight

/** Gap between bar islands (split button / pill / nav buttons). */
val FloatingBarItemGap = 8.dp

/**
 * Detail chrome: the Play split button's fixed width — its natural 60dp-era
 * width minus ~25% (user call). Fixed rather than intrinsic so both windows'
 * bars and the shell morph's width lerp agree without measuring; the Play
 * half stretches inside it, so font scale squeezes padding, not layout.
 */
val FloatingBarSplitWidth = 156.dp

/** CenteredBar detail pose: the now-playing pill's fixed width (§2.3). */
val FloatingBarDetailPillWidth = 200.dp
