package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * I-2 · the last line held like a long note (owner pick, 2026-10-05): while the
 * outro runs ([lastLineOutroFor]) the current last line widens to the right
 * from its left edge — the same stretch language as PLAY (1.10), labels
 * (1.08) and titles (1.03–1.05) — and lets go at the hand-over.
 *
 * Always a draw-time scaleX of the whole line, for every script: no relayout,
 * so nothing jumps between frames and the line never re-wraps. It widens by
 * the full [LastLineStretchMax] however full its row is (owner 2026-11-03:
 * "空间不够…它就直接可以延伸到屏幕外面去"): past the row's end it runs on
 * toward, and off, the screen edge.
 */
internal fun lastLineStretchScale(progress: Float): Float = 1f + LastLineStretchMax * progress

/**
 * The held line's one motion: [progress] (0 = rest) springs to each quantized
 * outro step on the slow spatial spring and lets go on the default one,
 * rebound included.
 */
@Stable
internal class HeldLineMotion {
    val progress = Animatable(0f, visibilityThreshold = HeldLineVisibilityThreshold)

    suspend fun follow(target: Float, widen: AnimationSpec<Float>, release: AnimationSpec<Float>) {
        progress.animateTo(target, if (target > 0f) widen else release)
    }
}

/**
 * The song's last timed line under its I-2 stretch ([stretch]: the quantized
 * outro progress, 0 = at rest): one text, widened at draw time from its left
 * edge.
 */
@Composable
internal fun HeldLastLineText(
    text: String,
    style: TextStyle,
    color: Color,
    stretch: Float,
    modifier: Modifier = Modifier,
) {
    val motion = remember { HeldLineMotion() }
    val widen = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val release = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(motion, stretch) {
        motion.follow(stretch, widen, release)
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.graphicsLayer {
            scaleX = lastLineStretchScale(motion.progress.value)
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    )
}

/** Peak widening of the held last line. */
internal const val LastLineStretchMax = 0.08f

/** [HeldLineMotion.progress]'s settle threshold. */
internal const val HeldLineVisibilityThreshold = 0.001f
