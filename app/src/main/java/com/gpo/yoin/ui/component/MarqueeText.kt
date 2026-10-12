package com.gpo.yoin.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Single-line text that scrolls when it doesn't fit, with soft fades at its clip edges.
 * [running] = false holds an overflowing line at its start until its page has settled.
 */
@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true
) {
    // Keep the fade's initial phase and basicMarquee's offset together, including replacements
    // with the same measured width. Color changes alone must not restart a playing title.
    key(text, style, LocalDensity.current, LocalLayoutDirection.current, running) {
        val viewport = remember { MarqueeViewport() }
        LaunchedEffect(viewport) {
            snapshotFlow { viewport.widths }.collectLatest { widths ->
                viewport.movingWidths = null
                if (running && widths.width > widths.height && widths.height > 0) {
                    delay(MarqueeInitialDelayMs.toLong())
                    viewport.movingWidths = widths
                }
            }
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    viewport.containerWidth = placeable.width
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .marqueeFade(viewport)
                .basicMarquee(
                    iterations = if (running) Int.MAX_VALUE else 0,
                    repeatDelayMillis = 1800,
                    initialDelayMillis = MarqueeInitialDelayMs
                )
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    viewport.contentWidth = placeable.width
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
        )
    }
}

/**
 * basicMarquee measures Text unbounded once, then constrains its own viewport. Reuse those
 * actual sizes instead of subcomposing every card and measuring its text a second time.
 * These states are observed only by drawing/the phase coroutine, never by composition.
 */
private class MarqueeViewport {
    var widths by mutableStateOf(IntSize.Zero)
        private set
    var movingWidths by mutableStateOf<IntSize?>(null)

    var contentWidth = 0
        set(value) {
            if (field == value) return
            field = value
            widths = IntSize(value, containerWidth)
        }
    var containerWidth = 0
        set(value) {
            if (field == value) return
            field = value
            widths = IntSize(contentWidth, value)
        }
}

private fun Modifier.marqueeFade(viewport: MarqueeViewport): Modifier = drawWithCache {
    val edge = minOf(18.dp.toPx(), size.width / 2f)
    val colors = listOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f).map { Color.Black.copy(alpha = it) }
    val leftMask = Brush.horizontalGradient(colors, startX = 0f, endX = edge)
    val rightMask = Brush.horizontalGradient(colors.reversed(), startX = size.width - edge, endX = size.width)
    val layerPaint = Paint()
    onDrawWithContent {
        val widths = viewport.widths
        if (widths.width <= widths.height || edge <= 0f) {
            drawContent()
        } else {
            // Isolate DstIn from the card/background, only when a mask is needed. A fitting
            // title does not allocate an additional offscreen fade layer.
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
            try {
                drawContent()
                val moving = viewport.movingWidths == widths
                if (moving || layoutDirection == LayoutDirection.Rtl) {
                    drawRect(leftMask, size = Size(edge, size.height), blendMode = BlendMode.DstIn)
                }
                if (moving || layoutDirection == LayoutDirection.Ltr) {
                    drawRect(
                        rightMask,
                        topLeft = Offset(size.width - edge, 0f),
                        size = Size(edge, size.height),
                        blendMode = BlendMode.DstIn
                    )
                }
            } finally {
                canvas.restore()
            }
        }
    }
}

private const val MarqueeInitialDelayMs = 1200
