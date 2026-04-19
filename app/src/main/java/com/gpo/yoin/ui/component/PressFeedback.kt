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
 * Previously animated a bouncy-spring scale on press (1 → 0.97 → overshoot
 * back to 1). That overshoot was the root cause of the Home Jump Back In
 * "shape edge nipped during playback" saga when combined with the halo's
 * own playback pulse. Project-wide decision: kill on-card press feedback
 * entirely for now — the whole app behaves like a flat static surface on
 * press. If you want the elastic feedback back, restore the previous
 * `composed { ... graphicsLayer { scaleX = scale; scaleY = scale } }`
 * body.
 */
internal fun Modifier.elasticPress(
    @Suppress("UNUSED_PARAMETER") interactionSource: InteractionSource,
    @Suppress("UNUSED_PARAMETER") pressedScale: Float = 0.97f,
): Modifier = this

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

            val leftStop = safeEdge / size.width
            val rightStop = 1f - leftStop
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        leftStop to Color.Black,
                        rightStop to Color.Black,
                        1f to Color.Transparent,
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
