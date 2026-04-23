package com.gpo.yoin.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft-mask the edges of a scrollable/pager container so content fades out
 * into the surrounding surface instead of hard-cutting at the bounds.
 *
 * Any combination of [top], [bottom], [start], [end] can be supplied — zero
 * values are cheap no-ops. Uses `BlendMode.DstIn` over an offscreen layer so
 * the fades operate on alpha (works over gradients / images, not just solid
 * colors).
 *
 * Call this on the outermost Box that wraps the scroll container. The
 * Modifier caches nothing per-frame beyond the gradient brushes; safe to
 * reuse across recompositions.
 */
fun Modifier.edgeFade(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
): Modifier = composed {
    val density = LocalDensity.current
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    val startPx = with(density) { start.toPx() }
    val endPx = with(density) { end.toPx() }

    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (topPx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = topPx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomPx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - bottomPx,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (startPx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startX = 0f,
                        endX = startPx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (endPx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startX = size.width - endPx,
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
