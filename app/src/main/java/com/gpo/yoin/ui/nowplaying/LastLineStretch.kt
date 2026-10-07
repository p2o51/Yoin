package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.min

/**
 * I-2 · the last line held like a long note (owner pick, 2026-10-05): while the
 * outro runs ([lastLineOutroFor]) the current last line widens to the right
 * from its left edge — the same stretch language as PLAY (1.10), labels
 * (1.08) and titles (1.03–1.05) — and lets go at the hand-over.
 *
 * Always a draw-time scaleX of the whole line, for every script: no relayout,
 * so nothing jumps between frames. (A nearly full Latin line used to widen by
 * letter-spacing, laid out again at each quantized step — the owner saw it
 * judder frame to frame, while CJK, scaled whole, stayed smooth.) A line that
 * already fills its row may still widen by [LastLineStretchGutterRoom] into
 * the page gutter: wider, never cut.
 */
internal fun lastLineStretchScale(progress: Float, widthUsage: Float): Float {
    if (widthUsage <= 0f) return 1f
    val room = maxOf(1f / widthUsage - 1f, LastLineStretchGutterRoom)
    return 1f + min(LastLineStretchMax, room) * progress
}

/** Longest laid-out line ÷ the width the text may use; 0 when unbounded or empty. */
internal fun TextLayoutResult.widthUsage(): Float {
    val available = layoutInput.constraints.maxWidth
    if (!layoutInput.constraints.hasBoundedWidth || available <= 0 || lineCount == 0) return 0f
    return longestLineWidth() / available
}

/** The widest laid-out line, in px. */
internal fun TextLayoutResult.longestLineWidth(): Float {
    var longest = 0f
    for (line in 0 until lineCount) {
        longest = maxOf(longest, getLineRight(line) - getLineLeft(line))
    }
    return longest
}

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
    var usage by remember { mutableFloatStateOf(0f) }
    val widen = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val release = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(motion, stretch) {
        motion.follow(stretch, widen, release)
    }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout -> usage = layout.widthUsage() },
        modifier = modifier.graphicsLayer {
            scaleX = lastLineStretchScale(motion.progress.value, usage)
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    )
}

/** Peak widening of the held last line. */
internal const val LastLineStretchMax = 0.08f

/** How far a line that already fills its row may still widen, into the gutter. */
internal const val LastLineStretchGutterRoom = 0.03f

/** [HeldLineMotion.progress]'s settle threshold. */
internal const val HeldLineVisibilityThreshold = 0.001f
