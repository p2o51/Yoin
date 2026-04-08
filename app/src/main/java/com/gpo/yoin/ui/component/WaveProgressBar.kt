package com.gpo.yoin.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.formatTime
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

private val TOUCH_HEIGHT = 34.dp
private val BAR_HEIGHT = 8.dp
private val KNOB_RADIUS = 7.dp

@Composable
fun WaveProgressBar(
    progress: Float,
    buffered: Float,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var previewFraction by remember { mutableStateOf<Float?>(null) }
    var trackWidthPx by remember { mutableIntStateOf(1) }
    var labelWidthPx by remember { mutableIntStateOf(0) }

    val waveTransition = rememberInfiniteTransition(label = "waveProgress")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val settledProgress = progress.coerceIn(0f, 1f)
    val displayProgress = when {
        isDragging -> dragFraction
        previewFraction != null -> previewFraction!!.coerceIn(0f, 1f)
        else -> settledProgress
    }
    val displayBuffered = buffered.coerceIn(displayProgress, 1f)
    val thumbOuterColor = MaterialTheme.colorScheme.surface

    LaunchedEffect(previewFraction, isDragging) {
        if (!isDragging && previewFraction != null) {
            delay(700)
            previewFraction = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(durationMs) {
                detectTapGestures(
                    onPress = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        dragFraction = fraction
                        previewFraction = fraction
                        val released = tryAwaitRelease()
                        if (released) {
                            onSeek(fraction)
                        } else if (!isDragging) {
                            previewFraction = null
                        }
                    },
                )
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        previewFraction = dragFraction
                    },
                    onDragEnd = {
                        onSeek(dragFraction)
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                        previewFraction = null
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                        previewFraction = dragFraction
                    },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val activePreviewFraction = previewFraction
        if (activePreviewFraction != null) {
            PreviewTimeBubble(
                timeText = formatTime((durationMs.toFloat() * activePreviewFraction).roundToLong()),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { labelWidthPx = it.width }
                    .offset {
                        val x = (trackWidthPx * activePreviewFraction) - (labelWidthPx / 2f)
                        IntOffset(
                            x = x.roundToInt().coerceIn(0, (trackWidthPx - labelWidthPx).coerceAtLeast(0)),
                            y = 0,
                        )
                    },
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOUCH_HEIGHT)
                .padding(top = 18.dp)
                .onSizeChanged { trackWidthPx = it.width },
        ) {
            val barHeight = BAR_HEIGHT.toPx()
            val radius = barHeight / 2f
            val knobRadius = KNOB_RADIUS.toPx()
            val centerY = size.height / 2f
            val top = centerY - barHeight / 2f

            drawRoundRect(
                color = trackColor.copy(alpha = 0.32f),
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(radius, radius),
            )

            drawRoundRect(
                color = progressColor.copy(alpha = 0.12f),
                topLeft = Offset(0f, top),
                size = Size(size.width * displayBuffered, barHeight),
                cornerRadius = CornerRadius(radius, radius),
            )

            val playedWidth = (size.width * displayProgress).coerceIn(0f, size.width)
            if (playedWidth > 0f) {
                drawRoundRect(
                    color = progressColor.copy(alpha = 0.12f),
                    topLeft = Offset(0f, top),
                    size = Size(playedWidth, barHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )

                val amplitude = if (isPlaying) barHeight * 0.38f else 0f
                val path = Path().apply {
                    moveTo(0f, centerY)
                    var x = 0f
                    val step = 4.dp.toPx()
                    val wavelength = 28.dp.toPx()
                    while (x <= playedWidth) {
                        val y = centerY + sin((x / wavelength) * (2f * PI).toFloat() + wavePhase) * amplitude
                        lineTo(x, y)
                        x += step
                    }
                }

                drawPath(
                    path = path,
                    color = progressColor,
                    style = Stroke(
                        width = barHeight * 0.75f,
                        cap = StrokeCap.Round,
                    ),
                )
            }

            val thumbX = when {
                size.width <= knobRadius * 2f -> size.width / 2f
                else -> (size.width * displayProgress).coerceIn(knobRadius, size.width - knobRadius)
            }
            drawCircle(
                color = thumbOuterColor,
                radius = knobRadius,
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = progressColor,
                radius = (knobRadius - 2.dp.toPx()).coerceAtLeast(0f),
                center = Offset(thumbX, centerY),
            )
        }
    }
}

@Composable
private fun PreviewTimeBubble(
    timeText: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun WaveProgressBarPreview() {
    YoinTheme {
        WaveProgressBar(
            progress = 0.4f,
            buffered = 0.7f,
            durationMs = 240_000L,
            onSeek = { _ -> },
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
            durationMs = 240_000L,
            onSeek = { _ -> },
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
            durationMs = 240_000L,
            onSeek = { _ -> },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
