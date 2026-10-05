package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.min

/**
 * I-2 · the last line held like a long note (owner pick, 2026-10-05): while the
 * outro runs ([lastLineOutroFor]) the current last line widens to the right
 * from its left edge — the same stretch language as PLAY (1.10), labels
 * (1.08) and titles (1.03–1.05) — and lets go at the hand-over.
 *
 * Never truncates (no mid-page truncation): a line already using more than
 * [LastLineStretchFullUsage] of the row widens by letter-spacing instead, which
 * re-wraps rather than running past the edge. Not the variable font's wdth
 * axis: CJK falls back to a system face without it, and a FontVariation per
 * frame mints a Typeface per value.
 *
 * Both ways let go on the same default spatial spring ([HeldLastLineText]):
 * the scale directly; the letter-spacing by cross-fading its frozen spaced
 * copy into the resting copy on that spring, never by snapping to 0.
 */
internal enum class LastLineStretchMode {
    /** No layout measured yet: hold still. */
    Unmeasured,

    /** Draw-time scaleX, no relayout. */
    Scale,

    /** The line is (nearly) full width: letter-spacing, quantized steps, re-wraps. */
    LetterSpacing,
}

internal fun lastLineStretchMode(widthUsage: Float): LastLineStretchMode = when {
    widthUsage <= 0f -> LastLineStretchMode.Unmeasured
    widthUsage > LastLineStretchFullUsage -> LastLineStretchMode.LetterSpacing
    else -> LastLineStretchMode.Scale
}

/**
 * scaleX for [progress] (a spring around the quantized outro progress; may
 * overshoot a little either side): `1 + min(0.08, row / longest line − 1)·p`,
 * so the widened line always fits its row.
 */
internal fun lastLineStretchScale(progress: Float, widthUsage: Float): Float {
    if (lastLineStretchMode(widthUsage) != LastLineStretchMode.Scale) return 1f
    val room = 1f / widthUsage - 1f
    return 1f + min(LastLineStretchMax, room) * progress
}

/** Letter-spacing (em) for the full-width fallback; [progress] is the quantized step. */
internal fun lastLineLetterSpacingEm(progress: Float): Float =
    LastLineStretchLetterSpacingEm * progress.coerceIn(0f, 1f)

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
 * The spaced copy's share of the letter-spacing release: 1 while the line is
 * held at [heldStep], falling with the release spring's [progress] to 0 — and,
 * unclamped, a hair past it on the spring's rebound. 0 with no spaced copy.
 */
internal fun heldLineSpacedWeight(progress: Float, heldStep: Float): Float =
    if (heldStep <= 0f) 0f else progress / heldStep

/**
 * Resting ÷ spaced width of the longest line, when the two copies wrap into the
 * same number of lines: the release then morphs their widths into each other
 * as they cross-fade, so the line visibly springs back. Re-wrapped copies
 * (different line breaks) can't be matched by a scale, so 1: a plain
 * cross-fade.
 */
internal fun heldLineMorphRatio(restWidth: Float, restLines: Int, spacedWidth: Float, spacedLines: Int): Float =
    if (restWidth <= 0f || spacedWidth <= 0f || restLines != spacedLines) {
        1f
    } else {
        (restWidth / spacedWidth).coerceIn(HeldLineMinMorphRatio, 1f)
    }

/**
 * The resting copy's scaleX in the release: as wide as the spaced copy at
 * [weight] 1, home at 0, a hair narrower on the rebound (weight < 0) — the
 * same exhale the scale mode shows.
 */
internal fun heldLineRestScaleX(weight: Float, ratio: Float): Float = 1f + (1f / ratio - 1f) * weight

/** The spaced copy's scaleX in the release: its own width at [weight] 1, squeezed to the resting width at 0. */
internal fun heldLineSpacedScaleX(weight: Float, ratio: Float): Float =
    ratio + (1f - ratio) * weight.coerceIn(0f, 1f)

/**
 * The held line's one motion. [progress] is the stretch (0 = rest): in scale
 * mode it springs to each quantized outro step; in letter-spacing mode it
 * snaps to the step (the spaced copy is re-laid out per step instead, ≤ 24
 * times) and [heldStep] records it. Letting go is the same default spatial
 * spring either way: progress → 0, the spaced copy staying laid out at
 * [heldStep] (no relayout) until the spring settles.
 */
@Stable
internal class HeldLineMotion {
    // A fine threshold: the cross-fade weight is progress ÷ the held step, so
    // the default 0.01 would end a release from an early step (1/24) with a
    // visible quarter-alpha snap.
    val progress = Animatable(0f, visibilityThreshold = HeldLineVisibilityThreshold)

    /** The letter-spacing step the spaced copy is laid out at; 0 = no spaced copy. */
    var heldStep by mutableFloatStateOf(0f)
        private set

    suspend fun follow(
        target: Float,
        letterSpacing: Boolean,
        widen: AnimationSpec<Float>,
        release: AnimationSpec<Float>,
    ) {
        when {
            target > 0f && letterSpacing -> {
                heldStep = target
                progress.snapTo(target)
            }
            target > 0f -> {
                heldStep = 0f
                progress.animateTo(target, widen)
            }
            else -> {
                progress.animateTo(0f, release)
                heldStep = 0f
            }
        }
    }
}

/**
 * The song's last timed line under its I-2 stretch ([stretch]: the quantized
 * outro progress, 0 = at rest).
 *
 *  - Scale: one text, widened at draw time (no relayout), on the slow spatial
 *    spring; let go on the default one, rebound included.
 *  - Letter-spacing (the line nearly fills its row): a spaced copy laid out
 *    per quantized step while it widens. At the hand-over it freezes and
 *    cross-fades into the resting copy on that same default spatial spring,
 *    the two morphing in width (when they wrap alike) — the line springs
 *    back instead of snapping to 0, with no per-frame relayout.
 *
 * The mode is decided on the resting copy's measure, so re-wrapping under
 * the spacing can never flip it.
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
    val metrics = remember { HeldLineMetrics() }
    val letterSpacing = lastLineStretchMode(metrics.usage) == LastLineStretchMode.LetterSpacing
    val widen = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val release = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(motion, stretch, letterSpacing) {
        motion.follow(stretch, letterSpacing, widen, release)
    }
    val heldStep = motion.heldStep
    Box(modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            onTextLayout = { layout ->
                metrics.usage = layout.widthUsage()
                metrics.restWidth = layout.longestLineWidth()
                metrics.restLines = layout.lineCount
            },
            modifier = Modifier.graphicsLayer {
                val held = motion.heldStep
                val progress = motion.progress.value
                if (held > 0f) {
                    val weight = heldLineSpacedWeight(progress, held)
                    alpha = 1f - weight.coerceIn(0f, 1f)
                    scaleX = heldLineRestScaleX(weight, metrics.ratio)
                } else {
                    scaleX = lastLineStretchScale(progress, metrics.usage)
                }
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        )
        if (heldStep > 0f) {
            Text(
                text = text,
                style = style.copy(letterSpacing = lastLineLetterSpacingEm(heldStep).em),
                color = color,
                onTextLayout = { layout ->
                    metrics.spacedWidth = layout.longestLineWidth()
                    metrics.spacedLines = layout.lineCount
                },
                // The resting copy carries the line's semantics; this one is paint only.
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        val weight = heldLineSpacedWeight(motion.progress.value, motion.heldStep)
                        alpha = weight.coerceIn(0f, 1f)
                        scaleX = heldLineSpacedScaleX(weight, metrics.ratio)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
            )
        }
    }
}

/** Both copies' measures, written by their text layouts and read at draw time. */
private class HeldLineMetrics {
    var usage by mutableFloatStateOf(0f)
    var restWidth by mutableFloatStateOf(0f)
    var restLines by mutableIntStateOf(0)
    var spacedWidth by mutableFloatStateOf(0f)
    var spacedLines by mutableIntStateOf(0)

    val ratio: Float
        get() = heldLineMorphRatio(restWidth, restLines, spacedWidth, spacedLines)
}

/** Peak widening of the held last line. */
internal const val LastLineStretchMax = 0.08f

/** Above this share of the row the line widens by letter-spacing instead of scale. */
internal const val LastLineStretchFullUsage = 0.92f

/** Peak letter-spacing of the full-width fallback. */
internal const val LastLineStretchLetterSpacingEm = 0.06f

/** A morph never squeezes the spaced copy below this share of its width (guards odd measures). */
internal const val HeldLineMinMorphRatio = 0.8f

/** [HeldLineMotion.progress]'s settle threshold (≤ 2.4% of the smallest held step). */
internal const val HeldLineVisibilityThreshold = 0.001f
