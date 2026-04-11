package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/** Rendering style for the audio visualizer. */
enum class VisualizerStyle {
    /** Full-width, subtle, flowing — for Now Playing background. */
    Ambient,

    /** Smaller area, slightly more visible — for Home screen. */
    Compact,
}

/**
 * Canvas-based audio visualizer that renders smooth frequency bars.
 *
 * Bar heights are driven by spring animations for an organic "breathing" feel.
 *
 * @param visualizerData current FFT / waveform snapshot
 * @param color          bar colour (typically primary with low alpha)
 * @param style          [VisualizerStyle.Ambient] or [VisualizerStyle.Compact]
 */
@Composable
fun AudioVisualizer(
    visualizerData: VisualizerData,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
    style: VisualizerStyle = VisualizerStyle.Ambient,
) {
    val barCount = when (style) {
        VisualizerStyle.Ambient -> AMBIENT_BAR_COUNT
        VisualizerStyle.Compact -> COMPACT_BAR_COUNT
    }
    val heightDp: Dp = when (style) {
        VisualizerStyle.Ambient -> AMBIENT_HEIGHT
        VisualizerStyle.Compact -> COMPACT_HEIGHT
    }

    val animatedHeights = remember(barCount) {
        List(barCount) { Animatable(0f) }
    }
    val barHeightSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Standard)

    // Spring-animate each bar towards its target value
    LaunchedEffect(visualizerData.fft) {
        val fft = visualizerData.fft
        if (fft.isEmpty()) return@LaunchedEffect
        animatedHeights.forEachIndexed { index, animatable ->
            val sampleIndex = (index.toFloat() / barCount * fft.size)
                .toInt()
                .coerceIn(0, fft.lastIndex)
            val target = fft[sampleIndex]
            animatable.animateTo(
                targetValue = target,
                animationSpec = barHeightSpec,
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        val totalWidth = size.width
        val maxHeight = size.height
        val gap = when (style) {
            VisualizerStyle.Ambient -> GAP_AMBIENT_PX
            VisualizerStyle.Compact -> GAP_COMPACT_PX
        }
        val barWidth = (totalWidth - gap * (barCount - 1)) / barCount
        val cornerRadius = barWidth / 2f

        animatedHeights.forEachIndexed { index, animatable ->
            val barHeight = (animatable.value * maxHeight).coerceAtLeast(MIN_BAR_HEIGHT_PX)
            val x = index * (barWidth + gap)
            val y = maxHeight - barHeight

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadius),
            )
        }
    }
}

private const val AMBIENT_BAR_COUNT = 32
private const val COMPACT_BAR_COUNT = 24
private val AMBIENT_HEIGHT = 120.dp
private val COMPACT_HEIGHT = 64.dp
private const val GAP_AMBIENT_PX = 4f
private const val GAP_COMPACT_PX = 3f
private const val MIN_BAR_HEIGHT_PX = 2f

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AudioVisualizerAmbientPreview() {
    val sampleFft = FloatArray(32) { i ->
        val t = i.toFloat() / 32
        (kotlin.math.sin(t * Math.PI * 2).toFloat() * 0.4f + 0.5f).coerceIn(0f, 1f)
    }
    YoinTheme {
        AudioVisualizer(
            visualizerData = VisualizerData(fft = sampleFft),
            style = VisualizerStyle.Ambient,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AudioVisualizerCompactPreview() {
    val sampleFft = FloatArray(24) { i ->
        val t = i.toFloat() / 24
        (kotlin.math.sin(t * Math.PI * 3).toFloat() * 0.35f + 0.4f).coerceIn(0f, 1f)
    }
    YoinTheme {
        AudioVisualizer(
            visualizerData = VisualizerData(fft = sampleFft),
            style = VisualizerStyle.Compact,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AudioVisualizerEmptyPreview() {
    YoinTheme {
        AudioVisualizer(
            visualizerData = VisualizerData.Empty,
            style = VisualizerStyle.Ambient,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
