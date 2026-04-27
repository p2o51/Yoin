package com.gpo.yoin.ui.component
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.formatTime
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val SeekTouchHeight = 18.dp
private val SeekWaveLength = 24.dp
private const val SeekWaveAmplitudeScale = 0.28f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    val settledProgress = progress.coerceIn(0f, 1f)
    val displayProgress = when {
        isDragging -> dragFraction
        previewFraction != null -> previewFraction!!.coerceIn(0f, 1f)
        else -> settledProgress
    }
    val indicatorProgress = if (isPlaying) displayProgress else settledProgress
    val baseIndicatorStroke = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val indicatorStroke = remember(baseIndicatorStroke) {
        Stroke(
            width = baseIndicatorStroke.width * 0.92f,
            cap = baseIndicatorStroke.cap,
        )
    }
    val trackStroke = remember(indicatorStroke) {
        Stroke(
            width = indicatorStroke.width,
            cap = StrokeCap.Butt,
        )
    }
    val resolvedTrackColor =
        if (buffered > settledProgress + 0.02f) {
            trackColor.copy(alpha = 0.2f)
        } else {
            trackColor.copy(alpha = 0.14f)
        }

    LaunchedEffect(previewFraction, isDragging) {
        if (!isDragging && previewFraction != null) {
            delay(650)
            previewFraction = null
        }
    }

    // Fade the wave amplitude in/out on play-state changes rather than
    // hard-toggling between zero and full. Also fixes the "flat bar on
    // first open" moment — if isPlaying briefly emits false before the
    // backend settles, the amplitude eases in rather than popping.
    val amplitudeMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "waveAmp",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
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

        LinearWavyProgressIndicator(
            progress = { indicatorProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(SeekTouchHeight)
                .align(Alignment.BottomCenter)
                .onSizeChanged { trackWidthPx = it.width },
            color = progressColor,
            trackColor = resolvedTrackColor,
            stroke = indicatorStroke,
            trackStroke = trackStroke,
            gapSize = 1.dp,
            stopSize = 0.dp,
            amplitude = { progressValue ->
                WavyProgressIndicatorDefaults.indicatorAmplitude(progressValue) *
                    SeekWaveAmplitudeScale *
                    amplitudeMultiplier
            },
            wavelength = SeekWaveLength,
            waveSpeed = if (isPlaying) SeekWaveLength else 0.dp,
        )
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
        tonalElevation = 4.dp,
        shadowElevation = 0.dp,
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
        Box(modifier = Modifier.padding(24.dp)) {
            WaveProgressBar(
                progress = 0.42f,
                buffered = 0.72f,
                durationMs = 220_000L,
                onSeek = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
