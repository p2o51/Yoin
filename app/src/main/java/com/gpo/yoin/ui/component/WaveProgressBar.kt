package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.sin

private val BAR_HEIGHT = 6.dp
private const val WAVE_AMPLITUDE_FRACTION = 0.4f
private const val WAVE_PERIODS = 3f

/**
 * Custom wave-shaped progress bar with buffering indicator and drag-to-seek.
 *
 * @param progress current playback position as 0.0–1.0
 * @param buffered buffered position as 0.0–1.0
 * @param onSeek called with a 0.0–1.0 fraction when the user taps or drags
 */
@Composable
fun WaveProgressBar(
    progress: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    bufferedColor: Color = MaterialTheme.colorScheme.outlineVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else progress,
        animationSpec = YoinMotion.spatialSpring(),
        label = "waveProgress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT * 4) // extra touch area
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeek(dragFraction)
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val barHeightPx = BAR_HEIGHT.toPx()
            val barY = (size.height - barHeightPx) / 2f
            val cornerRadiusPx = barHeightPx / 2f

            // Track background
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, barY),
                size = Size(size.width, barHeightPx),
                cornerRadius = CornerRadius(cornerRadiusPx),
            )

            // Buffered overlay
            val bufferedWidth = size.width * buffered.coerceIn(0f, 1f)
            if (bufferedWidth > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, barY),
                    size = Size(bufferedWidth, barHeightPx),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            }

            // Filled wave portion
            val progressWidth = size.width * animatedProgress.coerceIn(0f, 1f)
            if (progressWidth > 0f) {
                drawWaveFill(
                    progressWidth = progressWidth,
                    barY = barY,
                    barHeight = barHeightPx,
                    color = progressColor,
                )
            }
        }
    }
}

private fun DrawScope.drawWaveFill(
    progressWidth: Float,
    barY: Float,
    barHeight: Float,
    color: Color,
) {
    val amplitude = barHeight * WAVE_AMPLITUDE_FRACTION
    val path = Path().apply {
        moveTo(0f, barY + barHeight / 2f)

        // Top wave edge
        val steps = (progressWidth / 2f).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val x = (i.toFloat() / steps) * progressWidth
            val waveY = barY + (barHeight / 2f - amplitude) +
                amplitude * (1f - sin(x / progressWidth * WAVE_PERIODS * Math.PI.toFloat() * 2f))
            if (i == 0) moveTo(x, waveY) else lineTo(x, waveY)
        }

        // Close along bottom
        lineTo(progressWidth, barY + barHeight)
        lineTo(0f, barY + barHeight)
        close()
    }
    drawPath(path, color)
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun WaveProgressBarPreview() {
    YoinTheme {
        WaveProgressBar(
            progress = 0.4f,
            buffered = 0.7f,
            onSeek = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun WaveProgressBarEmptyPreview() {
    YoinTheme {
        WaveProgressBar(
            progress = 0f,
            buffered = 0f,
            onSeek = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun WaveProgressBarFullPreview() {
    YoinTheme {
        WaveProgressBar(
            progress = 1f,
            buffered = 1f,
            onSeek = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
