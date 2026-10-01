package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The floating bottom bar scaffold — outer margins, pill Surface, and inner
 * row metrics — shared VERBATIM by the shell Button Group and the detail
 * pages' bottom bar. Their opaque fills overlap during window handoff, but
 * only the frontmost overlapping host casts the shared 12dp shadow.
 *
 * The content lambda receives the inner row's width so callers can size
 * slots in absolute dp (the shell's morph interpolates widths by hand —
 * plain Row/Box only, exotic measure policies hang under the shell's
 * shared-transition lookahead). No implicit child spacing: callers own
 * their gaps.
 */
@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable RowScope.(innerWidth: Dp) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val innerWidth = maxWidth - 20.dp // row's 10dp horizontal padding × 2
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .bottomBarShadow(MaterialTheme.shapes.extraLarge, elevation = 12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
