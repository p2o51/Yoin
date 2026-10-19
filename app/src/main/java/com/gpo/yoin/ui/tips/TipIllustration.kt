package com.gpo.yoin.ui.tips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.HomeBubbleTip
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * How a tip is done, drawn: an abstract phone (blocks, no real UI) and a
 * touch — a dot that taps, holds (its ring fills) or drags — acting the
 * gesture out on a loop. It plays only while [playing] (its page is the
 * settled one) and the clock is read in the draw phase only. Reduced motion
 * shows the moment the gesture lands, still. Decorative: the text says it.
 */
@Composable
internal fun TipIllustration(
    tip: HomeBubbleTip,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val clock = remember { mutableFloatStateOf(StillAt) }
    LaunchedEffect(playing, reduced) {
        if (!playing || reduced) {
            clock.floatValue = StillAt
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> clock.floatValue = ((now - start) / 1_000_000f % LoopMs) / LoopMs }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IllustrationHeight)
            .background(colors.surfaceContainerLow, RoundedCornerShape(28.dp))
            .clearAndSetSemantics {},
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            val t = clock.floatValue
            val phone = phoneFrame(size)
            if (tip == HomeBubbleTip.Name) {
                drawLingering(t, colors)
                return@Canvas
            }
            drawRoundRect(colors.surface, phone.topLeft, phone.size, CornerRadius(phone.width * 0.14f))
            drawRoundRect(
                colors.outlineVariant,
                phone.topLeft,
                phone.size,
                CornerRadius(phone.width * 0.14f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val scene = Scene(this, phone, colors, t)
            clipRect(phone.left, phone.top, phone.right, phone.bottom) {
                when (tip) {
                    HomeBubbleTip.EditHome -> scene.editHome()
                    HomeBubbleTip.PullForMemories -> scene.pullForMemories()
                    HomeBubbleTip.CoverMenu -> scene.coverHold(toggleRow = false)
                    HomeBubbleTip.SongMenu -> scene.songMenu()
                    HomeBubbleTip.PlayModes -> scene.playModes()
                    HomeBubbleTip.NoteMoments -> scene.noteMoments()
                    HomeBubbleTip.LibrarySearch -> scene.librarySearch()
                    HomeBubbleTip.KeepScreenOn -> scene.coverHold(toggleRow = true)
                    HomeBubbleTip.ScrollEdge -> scene.scrollEdge()
                    HomeBubbleTip.Name -> Unit
                }
            }
        }
    }
}

/** A 9:17 phone standing in the middle of the panel. */
private fun DrawScope.phoneFrame(size: Size): Rect {
    val h = size.height
    val w = h * 9f / 17f
    val left = (size.width - w) / 2f
    return Rect(left, 0f, left + w, h)
}

/** Progress of [t] through [from]..[to], eased (standard decelerate), 0..1. */
private fun seg(t: Float, from: Float, to: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}

/** 0 → 1 → 0 across [from]..[to]: a dot's presence. */
private fun window(t: Float, from: Float, to: Float, fade: Float = 0.06f): Float =
    seg(t, from, from + fade) * (1f - seg(t, to - fade, to))

private class Scene(
    val scope: DrawScope,
    val phone: Rect,
    val colors: ColorScheme,
    val t: Float,
) {
    fun x(f: Float) = phone.left + phone.width * f
    fun y(f: Float) = phone.top + phone.height * f
    fun w(f: Float) = phone.width * f
    fun h(f: Float) = phone.height * f

    fun block(l: Float, top: Float, wf: Float, hf: Float, color: Color, round: Float = 0.04f, dy: Float = 0f) {
        scope.drawRoundRect(color, Offset(x(l), y(top) + dy), Size(w(wf), h(hf)), CornerRadius(w(round)))
    }

    /** Header line + title bar, the way every Yoin page starts. */
    fun header(dy: Float = 0f) {
        block(0.1f, 0.07f, 0.3f, 0.025f, colors.outlineVariant, 0.02f, dy)
        block(0.1f, 0.11f, 0.45f, 0.04f, colors.onSurfaceVariant.copy(alpha = 0.55f), 0.02f, dy)
    }

    /**
     * The touch at ([fx], [fy]) of the phone: present over [show], pressed
     * (smaller, a ripple) from [down]; with [hold] it is held and its ring
     * fills by then.
     */
    fun finger(fx: Float, fy: Float, show: ClosedFloatingPointRange<Float>, down: Float, hold: Float? = null) {
        val alpha = window(t, show.start, show.endInclusive)
        if (alpha <= 0f) return
        val c = Offset(x(fx), y(fy))
        val r = w(0.13f)
        val pressed = seg(t, down, down + 0.05f)
        val primary = colors.primary
        scope.drawCircle(primary.copy(alpha = 0.22f * alpha), r * (1f - 0.15f * pressed), c)
        scope.drawCircle(primary.copy(alpha = 0.9f * alpha), r * 0.42f * (1f - 0.15f * pressed), c)
        if (hold == null) {
            val ripple = seg(t, down, down + 0.22f)
            if (ripple in 0.001f..0.999f) {
                scope.drawCircle(
                    primary.copy(alpha = (1f - ripple) * 0.5f * alpha),
                    r * (1f + ripple * 1.2f),
                    c,
                    style = Stroke(width = 2.dp.value * scope.density),
                )
            }
        } else {
            val ring = ((t - down) / (hold - down)).coerceIn(0f, 1f)
            if (t >= down) {
                scope.drawArc(
                    primary.copy(alpha = alpha),
                    startAngle = -90f,
                    sweepAngle = 360f * ring,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 1.25f, c.y - r * 1.25f),
                    size = Size(r * 2.5f, r * 2.5f),
                    style = Stroke(width = 2.5.dp.value * scope.density, cap = StrokeCap.Round),
                )
            }
        }
    }

    /** A bottom sheet rising by [p] with [rows] rows; [highlight] a row with a switch turning on. */
    fun sheet(p: Float, rows: Int = 3, highlight: Int? = null, switchOn: Float = 0f) {
        if (p <= 0f) return
        val top = 1f - 0.42f * p
        scope.drawRect(colors.scrim.copy(alpha = 0.25f * p), Offset(phone.left, phone.top), phone.size)
        block(0f, top, 1f, 0.5f, colors.surfaceContainerHigh, 0.12f)
        block(0.42f, top + 0.025f, 0.16f, 0.012f, colors.outlineVariant, 0.01f)
        repeat(rows) { i ->
            val ry = top + 0.07f + i * 0.1f
            val lit = highlight == i
            block(0.08f, ry, 0.84f, 0.075f, if (lit) colors.secondaryContainer else colors.surfaceContainerHighest, 0.06f)
            block(0.14f, ry + 0.028f, 0.32f, 0.02f, colors.onSurfaceVariant.copy(alpha = 0.5f), 0.01f)
            if (lit) {
                val track = colors.primary.copy(alpha = 0.25f + 0.75f * switchOn)
                block(0.68f, ry + 0.02f, 0.18f, 0.035f, track, 0.02f)
                val knobX = x(0.7f + 0.1f * switchOn)
                scope.drawCircle(colors.surface, w(0.025f), Offset(knobX + w(0.025f), y(ry + 0.0375f)))
            }
        }
    }

    // ── Scenes ──────────────────────────────────────────────────────────

    fun editHome() {
        header()
        val wiggle = window(t, 0.55f, 0.95f) * sin(t * 2f * PI.toFloat() * 6f) * 3f
        for (row in 0 until 3) for (col in 0 until 2) {
            val l = 0.1f + col * 0.42f
            val top = 0.22f + row * 0.24f
            val pivot = Offset(x(l + 0.19f), y(top + 0.1f))
            scope.rotate(if ((row + col) % 2 == 0) wiggle else -wiggle, pivot) {
                block(l, top, 0.38f, 0.2f, if (row == 0 && col == 0) colors.primaryContainer else colors.surfaceContainerHighest, 0.08f)
            }
        }
        finger(0.29f, 0.32f, 0.08f..0.62f, down = 0.15f, hold = 0.5f)
    }

    fun pullForMemories() {
        val pull = seg(t, 0.2f, 0.55f) * (1f - seg(t, 0.68f, 0.9f))
        val dy = h(0.22f) * pull
        // What the pull reveals: a memory, growing in above the page.
        scope.drawCircle(colors.tertiaryContainer, w(0.12f) * pull, Offset(x(0.5f), y(0.1f) + dy * 0.4f))
        header(dy)
        repeat(4) { i -> block(0.1f, 0.22f + i * 0.17f, 0.8f, 0.13f, colors.surfaceContainerHighest, 0.06f, dy) }
        val fy = 0.3f + 0.22f * seg(t, 0.2f, 0.55f)
        finger(0.5f, fy, 0.1f..0.66f, down = 0.17f, hold = null)
    }

    fun coverHold(toggleRow: Boolean) {
        block(0.14f, 0.1f, 0.72f, 0.38f, colors.primaryContainer, 0.06f)
        block(0.14f, 0.54f, 0.5f, 0.03f, colors.onSurfaceVariant.copy(alpha = 0.55f), 0.02f)
        block(0.14f, 0.62f, 0.3f, 0.07f, colors.primary.copy(alpha = 0.8f), 0.08f)
        block(0.48f, 0.62f, 0.12f, 0.07f, colors.primary.copy(alpha = 0.8f), 0.08f)
        block(0.14f, 0.78f, 0.72f, 0.012f, colors.outlineVariant, 0.01f)
        finger(0.5f, 0.29f, 0.06f..0.56f, down = 0.12f, hold = 0.45f)
        sheet(
            seg(t, 0.47f, 0.62f) * (1f - seg(t, 0.9f, 0.99f)),
            rows = 3,
            highlight = if (toggleRow) 1 else null,
            switchOn = seg(t, 0.7f, 0.78f),
        )
        if (toggleRow) finger(0.77f, 0.75f, 0.62f..0.86f, down = 0.69f, hold = null)
    }

    fun songMenu() {
        block(0.1f, 0.06f, 0.36f, 0.2f, colors.primaryContainer, 0.06f)
        block(0.52f, 0.12f, 0.36f, 0.035f, colors.onSurfaceVariant.copy(alpha = 0.55f), 0.02f)
        repeat(6) { i ->
            val lit = i == 2 && t in 0.12f..0.6f
            block(0.06f, 0.32f + i * 0.105f, 0.88f, 0.085f, if (lit) colors.secondaryContainer else colors.surfaceContainerHighest, 0.05f)
            block(0.12f, 0.355f + i * 0.105f, 0.4f, 0.018f, colors.onSurfaceVariant.copy(alpha = 0.45f), 0.01f)
        }
        finger(0.62f, 0.5625f, 0.06f..0.56f, down = 0.12f, hold = 0.45f)
        sheet(seg(t, 0.47f, 0.62f) * (1f - seg(t, 0.9f, 0.99f)), rows = 3, highlight = null)
    }

    fun playModes() {
        header()
        block(0.1f, 0.22f, 0.8f, 0.3f, colors.surfaceContainerHighest, 0.06f)
        // The round button; a tap steps it on (the glyph turns), a hold splits
        // it into shuffle | repeat, which merge back at the end.
        val split = seg(t, 0.62f, 0.72f) * (1f - seg(t, 0.88f, 0.97f))
        val cy = y(0.7f)
        val r = w(0.16f)
        val cx = x(0.5f)
        val spread = w(0.19f) * split
        if (split <= 0.01f) {
            scope.drawCircle(colors.tertiaryContainer, r, Offset(cx, cy))
            shuffleGlyph(Offset(cx, cy), r * 0.5f, 180f * seg(t, 0.2f, 0.3f))
        } else {
            listOf(-1f, 1f).forEach { side ->
                val c = Offset(cx + side * spread, cy)
                scope.drawRoundRect(
                    colors.tertiaryContainer,
                    Offset(c.x - r, c.y - r),
                    Size(r * 2f, r * 2f),
                    CornerRadius(r * (1f - 0.5f * split)),
                )
                if (side < 0) shuffleGlyph(c, r * 0.5f, 0f) else repeatGlyph(c, r * 0.5f)
            }
        }
        finger(0.58f, 0.75f, 0.12f..0.34f, down = 0.19f, hold = null)
        finger(0.58f, 0.75f, 0.38f..0.68f, down = 0.42f, hold = 0.62f)
    }

    /** Shuffle, abstract: two strands that cross. */
    private fun shuffleGlyph(c: Offset, r: Float, degrees: Float) {
        scope.rotate(degrees, c) {
            val path = Path().apply {
                moveTo(c.x - r, c.y - r * 0.55f)
                cubicTo(c.x, c.y - r * 0.55f, c.x, c.y + r * 0.55f, c.x + r, c.y + r * 0.55f)
                moveTo(c.x - r, c.y + r * 0.55f)
                cubicTo(c.x, c.y + r * 0.55f, c.x, c.y - r * 0.55f, c.x + r, c.y - r * 0.55f)
            }
            drawPath(path, colors.onTertiaryContainer, style = Stroke(width = r * 0.26f, cap = StrokeCap.Round))
        }
    }

    /** Repeat, abstract: a closed loop. */
    private fun repeatGlyph(c: Offset, r: Float) {
        scope.drawRoundRect(
            colors.onTertiaryContainer,
            Offset(c.x - r, c.y - r * 0.6f),
            Size(r * 2f, r * 1.2f),
            CornerRadius(r * 0.6f),
            style = Stroke(width = r * 0.26f),
        )
    }

    fun noteMoments() {
        block(0.14f, 0.08f, 0.72f, 0.34f, colors.primaryContainer, 0.06f)
        block(0.14f, 0.48f, 0.5f, 0.03f, colors.onSurfaceVariant.copy(alpha = 0.55f), 0.02f)
        // Progress line with a moving playhead; the note pins where the tap landed.
        val lineY = y(0.62f)
        val playhead = 0.18f + 0.6f * t
        scope.drawLine(colors.outlineVariant, Offset(x(0.14f), lineY), Offset(x(0.86f), lineY), 3.dp.value * scope.density, StrokeCap.Round)
        scope.drawLine(colors.primary, Offset(x(0.14f), lineY), Offset(x(playhead), lineY), 3.dp.value * scope.density, StrokeCap.Round)
        val pinnedAt = 0.18f + 0.6f * 0.4f
        val drop = seg(t, 0.42f, 0.55f)
        if (t >= 0.42f) {
            val py = lineY - h(0.06f) * (1f - drop) - h(0.035f)
            scope.drawCircle(colors.tertiary, w(0.035f), Offset(x(pinnedAt), py))
            scope.drawLine(colors.tertiary, Offset(x(pinnedAt), py), Offset(x(pinnedAt), lineY), 2.dp.value * scope.density)
        }
        // The pills; Write is the one tapped.
        block(0.08f, 0.8f, 0.26f, 0.065f, colors.primary.copy(alpha = 0.8f), 0.06f)
        block(0.37f, 0.8f, 0.26f, 0.065f, colors.primary.copy(alpha = 0.8f), 0.06f)
        block(0.66f, 0.8f, 0.26f, 0.065f, colors.primary, 0.06f)
        finger(0.79f, 0.8325f, 0.3f..0.55f, down = 0.38f, hold = null)
    }

    fun librarySearch() {
        val open = seg(t, 0.52f, 0.66f) * (1f - seg(t, 0.9f, 0.99f))
        header()
        repeat(3) { i -> block(0.1f, 0.22f + i * 0.2f, 0.8f, 0.16f, colors.surfaceContainerHighest, 0.06f) }
        // The bar: Home | Library; the field rises out of Library.
        block(0.06f, 0.86f, 0.88f, 0.09f, colors.surfaceContainerHigh, 0.09f)
        block(0.09f, 0.875f, 0.4f, 0.06f, colors.secondaryContainer, 0.06f)
        block(0.51f, 0.875f, 0.4f, 0.06f, colors.surfaceContainerHighest, 0.06f)
        if (open > 0f) {
            val fieldTop = 0.86f - 0.12f * open
            block(0.06f + 0.45f * (1f - open), fieldTop, 0.88f - 0.45f * (1f - open), 0.09f, colors.primaryContainer, 0.09f)
            block(0.14f, fieldTop + 0.035f, 0.3f * open, 0.02f, colors.onPrimaryContainer.copy(alpha = 0.5f), 0.01f)
        }
        finger(0.71f, 0.905f, 0.06f..0.56f, down = 0.12f, hold = 0.46f)
    }

    fun scrollEdge() {
        // Content flows up under a fixed top bar; the seam is a soft wave.
        val scroll = seg(t, 0.15f, 0.7f) * (1f - seg(t, 0.85f, 1f))
        val dy = -h(0.3f) * scroll
        repeat(7) { i ->
            block(0.1f, 0.26f + i * 0.17f, 0.8f, 0.13f, if (i == 1) colors.primaryContainer else colors.surfaceContainerHighest, 0.06f, dy)
        }
        val barBottom = y(0.2f)
        val wave = Path().apply {
            moveTo(phone.left, phone.top)
            lineTo(phone.right, phone.top)
            lineTo(phone.right, barBottom)
            val steps = 24
            for (s in steps downTo 0) {
                val fx = s / steps.toFloat()
                val wy = barBottom + h(0.018f) * sin(fx * 4f * PI.toFloat() + t * 2f * PI.toFloat())
                lineTo(phone.left + phone.width * fx, wy)
            }
            close()
        }
        scope.drawPath(wave, colors.surface)
        header()
        finger(0.5f, 0.72f - 0.3f * seg(t, 0.15f, 0.55f), 0.08f..0.6f, down = 0.13f, hold = null)
    }
}

/** 余韻: rings that keep spreading from one note after it has sounded. */
private fun DrawScope.drawLingering(t: Float, colors: ColorScheme) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val max = size.height * 0.55f
    repeat(4) { i ->
        val p = (t + i / 4f) % 1f
        drawCircle(
            colors.primary.copy(alpha = (1f - p) * 0.6f),
            max * p,
            c,
            style = Stroke(width = (1f - p) * 4.dp.toPx() + 1.dp.toPx()),
        )
    }
    drawCircle(colors.primary, 8.dp.toPx(), c)
}

private const val LoopMs = 3_200f

/** Reduced motion and pages at rest: the frame where the gesture has landed. */
private const val StillAt = 0.6f

private val IllustrationHeight = 264.dp

@Preview(showBackground = true)
@Composable
private fun TipIllustrationPreview() {
    YoinTheme {
        TipIllustration(tip = HomeBubbleTip.CoverMenu, playing = false, modifier = Modifier.padding(16.dp))
    }
}
