package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme

private val TOUCH_HEIGHT = 40.dp

/**
 * Interactive playback progress built on Material 3 Expressive's official
 * `LinearWavyProgressIndicator`.
 *
 * The only custom layer left here is the gesture overlay needed for seek interactions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        targetValue = if (isDragging) dragFraction else progress.coerceIn(0f, 1f),
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "waveProgress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WavyProgressIndicatorDefaults.LinearContainerHeight * 2)
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
                            dragFraction = (dragFraction + dragAmount / size.width)
                                .coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            LinearProgressIndicator(
                progress = { buffered.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                color = bufferedColor,
                trackColor = trackColor,
            )

            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                color = progressColor,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun WaveProgressBarPreview() {
    YoinTheme {
        WaveProgressBar(
            progress = 0.4f,
            buffered = 0.7f,
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
            onSeek = { _ -> },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
