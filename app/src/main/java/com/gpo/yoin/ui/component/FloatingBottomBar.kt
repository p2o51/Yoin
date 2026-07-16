package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion

/**
 * The floating bottom bar scaffold — outer margins, pill Surface, and inner
 * row metrics — shared VERBATIM by the shell Button Group and the detail
 * pages' bottom bar. The shell⇄detail hand-off crossfades one window's bar
 * onto the other's, so the two must be pixel twins; any metric change here
 * moves both together.
 *
 * Inner row height is 48dp (68dp bar minus 10dp vertical padding) — size
 * fixed-height children with [FloatingBarButtonHeight].
 */
@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val surfaceColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "floatingBarSurfaceColor",
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = surfaceColor,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
        overlay()
    }
}

/** Height of fixed-height children inside the bar's inner row. */
val FloatingBarButtonHeight = 48.dp
