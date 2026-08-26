package com.gpo.yoin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.remember
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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

internal fun Modifier.noRippleClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/**
 * Animated press feedback using standard spring physics (no overshoot)
 * to avoid layout shape edge clipping.
 */
internal fun Modifier.elasticPress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "elasticPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

internal fun Modifier.minimumTouchTarget(
    minSize: Dp = 44.dp,
): Modifier = sizeIn(minWidth = minSize, minHeight = minSize)

internal fun Modifier.horizontalFadeMask(edgeWidth: Dp = 20.dp): Modifier = composed {
    val density = LocalDensity.current
    val edgeWidthPx = remember(edgeWidth, density) {
        with(density) { edgeWidth.toPx() }
    }

    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val safeEdge = edgeWidthPx.coerceAtMost(size.width / 2f)
            if (safeEdge <= 0f || size.width <= 0f) return@drawWithContent

            // Smoothstep-eased ramps: a linear fade has a visible onset
            // line that reads as a milky band over light backgrounds.
            val eased = listOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f)
            val leftStops = eased.mapIndexed { index, alpha ->
                (safeEdge / size.width) * (index / 5f) to Color.Black.copy(alpha = alpha)
            }
            val rightStops = eased.mapIndexed { index, alpha ->
                1f - (safeEdge / size.width) * (index / 5f) to Color.Black.copy(alpha = alpha)
            }.reversed()
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = (leftStops + rightStops).toTypedArray(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Let a horizontally scrolling child escape its parent's horizontal content
 * padding and span the full width, so scrolled items run under the screen
 * edges instead of being chopped at a mid-page padding line. Pair with the
 * same value as the child's own `contentPadding` so resting items still align
 * with the page margin.
 */
internal fun Modifier.ignoreParentHorizontalPadding(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(maxWidth = constraints.maxWidth + extraPx),
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(x = -(extraPx / 2), y = 0)
    }
}

/**
 * Like [ignoreParentHorizontalPadding] but for the trailing edge only: let a
 * horizontally scrolling child that shares its row with something on the left
 * (e.g. the Recently Added album shelf beside the track grid) escape only the
 * parent's *right* content padding, so scrolled items run under the screen's
 * right edge while the left edge stays put. Pair with the same value as the
 * child's own trailing `contentPadding` so resting items align with the margin.
 */
internal fun Modifier.ignoreParentTrailingPadding(trailing: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = trailing.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(maxWidth = constraints.maxWidth + extraPx),
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(x = 0, y = 0)
    }
}

/**
 * Scroll-aware edge fade for horizontal lists: each edge fades ONLY while
 * more content lies beyond it, so nothing ever ends in a hard cut, and the
 * resting ends of the list stay crisp (a static mask would dim the first and
 * last items even when fully scrolled). Fades animate in/out with the scroll
 * position.
 */
internal fun Modifier.horizontalEdgeFadeOnScroll(
    state: ScrollableState,
    edgeWidth: Dp = 24.dp,
): Modifier = composed {
    val density = LocalDensity.current
    val edgeWidthPx = remember(edgeWidth, density) {
        with(density) { edgeWidth.toPx() }
    }
    val startFade by animateFloatAsState(
        targetValue = if (state.canScrollBackward) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "edgeFadeStart",
    )
    val endFade by animateFloatAsState(
        targetValue = if (state.canScrollForward) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "edgeFadeEnd",
    )

    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val safeEdge = edgeWidthPx.coerceAtMost(size.width / 3f)
            if (safeEdge <= 0f || size.width <= 0f) return@drawWithContent
            if (startFade > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        // Smoothstep-eased, alpha-lerped by the animated fade —
                        // no onset line, stable geometry while animating.
                        colors = listOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f).map { t ->
                            Color.Black.copy(alpha = (1f - startFade) + startFade * t)
                        },
                        startX = 0f,
                        endX = safeEdge,
                    ),
                    size = Size(safeEdge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (endFade > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f).map { t ->
                            Color.Black.copy(alpha = (1f - endFade) + endFade * t)
                        }.reversed(),
                        startX = size.width - safeEdge,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - safeEdge, 0f),
                    size = Size(safeEdge, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
