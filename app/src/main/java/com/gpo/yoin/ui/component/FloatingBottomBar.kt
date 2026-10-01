package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.CenteredBarBottomMargin
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
 * The content lambda receives the inner row's width so callers can size
 * slots in absolute dp (the shell's morph interpolates widths by hand —
 * plain Row/Box only, exotic measure policies hang under the shell's
 * shared-transition lookahead). No implicit child spacing: callers own
 * their gaps.
 *
 * [centered] is ShellChromeForm.CenteredBar (断点交接 §2.3): the same bar,
 * horizontally centred, capped at 600dp, [bottomMargin] above the nav bar.
 * [barWidth] (optional) narrows the pill surface inside that slot — the Now
 * Playing side panel folds the bar down to its two nav buttons with it.
 */
@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    bottomMargin: Dp = CenteredBarBottomMargin,
    barWidth: ((maxWidth: Dp) -> Dp)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable RowScope.(innerWidth: Dp) -> Unit,
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
            .widthIn(max = CenteredBarMaxWidth)
    } else {
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = PortraitBarVerticalMargin)
    }
    BoxWithConstraints(modifier = slot) {
        val surfaceWidth = barWidth?.invoke(maxWidth)?.coerceAtMost(maxWidth) ?: maxWidth
        val innerWidth = surfaceWidth - 20.dp // row's 10dp horizontal padding × 2
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
                    .height(68.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content(innerWidth)
            }
        }
        overlay()
    }
}

/** Height of fixed-height children inside the bar's inner row. */
val FloatingBarButtonHeight = 48.dp

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
