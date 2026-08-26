package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion

/**
 * Smoothstep-eased alpha stops for a fade mask. A plain two-stop linear
 * gradient has a C1 discontinuity where the fade begins — perceived as a
 * visible band / "milky veil" over light backgrounds. Easing the ramp makes
 * the fade start imperceptibly. [edgeAlpha] is the alpha AT the edge (0 =
 * fully faded out); the interior end is always opaque.
 *
 * Direction: stops run from the EDGE (faded) to the INTERIOR (opaque).
 */
private fun easedMaskColors(edgeAlpha: Float): List<Color> {
    // smoothstep(t) sampled at 6 points, lerped between edgeAlpha and 1.
    return listOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f).map { t ->
        Color.Black.copy(alpha = edgeAlpha + (1f - edgeAlpha) * t)
    }
}

/**
 * Soft-mask the edges of a scrollable/pager container so content fades out
 * into the surrounding surface instead of hard-cutting at the bounds.
 *
 * Any combination of [top], [bottom], [start], [end] can be supplied — zero
 * values are cheap no-ops. Uses `BlendMode.DstIn` over an offscreen layer so
 * the fades lower the CONTENT'S OWN alpha (no colour overlay — works over
 * gradients / images), with a smoothstep ramp so the fade has no visible
 * onset line.
 *
 * Call this on the outermost Box that wraps the scroll container. Prefer
 * [verticalEdgeFadeOnScroll] when a scroll state is available — a static fade
 * keeps dimming the last line even when the list is scrolled to its end.
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
    val edgeColors = remember { easedMaskColors(edgeAlpha = 0f) }
    val interiorColors = remember { easedMaskColors(edgeAlpha = 0f).reversed() }

    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (topPx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = edgeColors,
                        startY = 0f,
                        endY = topPx,
                    ),
                    size = Size(size.width, topPx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomPx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = interiorColors,
                        startY = size.height - bottomPx,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - bottomPx),
                    size = Size(size.width, bottomPx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (startPx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = edgeColors,
                        startX = 0f,
                        endX = startPx,
                    ),
                    size = Size(startPx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (endPx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = interiorColors,
                        startX = size.width - endPx,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - endPx, 0f),
                    size = Size(endPx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * Scroll-aware vertical twin of [edgeFade]: each edge fades ONLY while more
 * content lies beyond it, so a list scrolled to its end shows its last line
 * crisp instead of permanently dimmed (the "content stuck half-faded above
 * the bar" complaint). Fades ease in/out with the scroll position and use
 * the same smoothstep alpha ramp on the content's own transparency.
 */
fun Modifier.verticalEdgeFadeOnScroll(
    state: ScrollableState,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Modifier = composed {
    val density = LocalDensity.current
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    val topFade by animateFloatAsState(
        targetValue = if (state.canScrollBackward) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "verticalEdgeFadeTop",
    )
    val bottomFade by animateFloatAsState(
        targetValue = if (state.canScrollForward) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "verticalEdgeFadeBottom",
    )

    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            if (topPx > 0f && topFade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = easedMaskColors(edgeAlpha = 1f - topFade),
                        startY = 0f,
                        endY = topPx,
                    ),
                    size = Size(size.width, topPx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomPx > 0f && bottomFade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = easedMaskColors(edgeAlpha = 1f - bottomFade).reversed(),
                        startY = size.height - bottomPx,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - bottomPx),
                    size = Size(size.width, bottomPx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
