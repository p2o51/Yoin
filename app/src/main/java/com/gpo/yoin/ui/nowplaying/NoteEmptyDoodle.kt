package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Note page's empty state (2026-10-08 owner: a doodle instead of a
 * prompt). Three drawings, one picked at random per song by the caller:
 * - [Stroke]: a wave pulled out of the playhead's dot that lands as a note —
 *   the same idea as the progress wave and its note anchors;
 * - [Paper]: a tilted sticky note whose third line stops halfway, a caret waiting;
 * - [Mark]: the app icon's three arrows sketched in a loose circle.
 *
 * Strokes draw themselves in order on effects springs (no overshoot: a stroke
 * never draws past its end); filled shapes pop in on the expressive spatial
 * spring. Once drawn it rests — the caret blinks a few times, then stays — so
 * an idle empty page asks for no frames. Colours are the theme's roles, so the
 * doodle follows dynamic colour and the playing palette like the rest of NP.
 */
internal enum class NoteDoodle { Stroke, Paper, Mark }

@Composable
internal fun NoteEmptyDoodle(doodle: NoteDoodle, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val marks = remember(doodle) { doodleMarks(doodle) }
    // Previews and screenshot tests show the finished drawing.
    val drawnAtStart = if (LocalInspectionMode.current) 1f else 0f
    val progress = remember(doodle) { marks.map { Animatable(drawnAtStart) } }
    val longStroke = YoinMotion.slowEffectsSpec<Float>()
    val shortStroke = YoinMotion.defaultEffectsSpec<Float>()
    val pop = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val blink = remember(doodle) { Animatable(1f) }

    LaunchedEffect(doodle) {
        coroutineScope {
            marks.forEachIndexed { index, mark ->
                launch {
                    delay(mark.delayMs)
                    val spec: FiniteAnimationSpec<Float> = when {
                        mark is DoodleMark.Fill -> pop
                        mark is DoodleMark.Line && mark.long -> longStroke
                        else -> shortStroke
                    }
                    progress[index].animateTo(1f, spec)
                }
            }
        }
        if (doodle == NoteDoodle.Paper) {
            repeat(CaretBlinks) {
                delay(CaretBlinkMs)
                blink.snapTo(0f)
                delay(CaretBlinkMs)
                blink.snapTo(1f)
            }
        }
    }

    Canvas(modifier = modifier) {
        val unit = size.width / ViewBoxWidth
        scale(unit, unit, pivot = Offset.Zero) {
            marks.forEachIndexed { index, mark ->
                val p = progress[index].value
                if (p <= 0f) return@forEachIndexed
                when (mark) {
                    is DoodleMark.Line -> drawLine(mark, p, mark.ink.color(scheme))
                    is DoodleMark.Fill -> drawFill(mark, p, mark.ink.color(scheme))
                    is DoodleMark.Caret -> drawPath(
                        path = mark.path,
                        color = mark.ink.color(scheme).copy(alpha = p * blink.value),
                        style = Stroke(width = mark.width, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLine(mark: DoodleMark.Line, progress: Float, color: Color) {
    val measure = PathMeasure().apply { setPath(mark.path, false) }
    val drawn = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), drawn, true)
    drawPath(
        path = drawn,
        color = color,
        style = Stroke(width = mark.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawFill(mark: DoodleMark.Fill, progress: Float, color: Color) {
    val bounds = mark.path.getBounds()
    withTransform({
        scale(progress, progress, pivot = bounds.center)
        rotate(mark.rotationDegrees, pivot = bounds.center)
    }) {
        drawPath(path = mark.path, color = color.copy(alpha = progress.coerceIn(0f, 1f)))
    }
}

private enum class DoodleInk {
    Primary, Secondary, Tertiary, Paper, Outline;

    fun color(scheme: ColorScheme): Color = when (this) {
        Primary -> scheme.primary
        Secondary -> scheme.secondary
        Tertiary -> scheme.tertiary
        // A tint of paper, not a block of accent: tertiaryContainer is loud on
        // some playing palettes, so it is mixed into the page's own surface.
        Paper -> lerp(scheme.surfaceContainerHigh, scheme.tertiaryContainer, PaperTint)
        Outline -> scheme.outlineVariant
    }
}

private sealed interface DoodleMark {
    val delayMs: Long
    val ink: DoodleInk
    val path: Path

    class Line(d: String, override val ink: DoodleInk, val width: Float, override val delayMs: Long, val long: Boolean = false) :
        DoodleMark {
        override val path: Path = PathParser().parsePathString(d).toPath()
    }

    class Fill(d: String, override val ink: DoodleInk, override val delayMs: Long, val rotationDegrees: Float = 0f) :
        DoodleMark {
        override val path: Path = PathParser().parsePathString(d).toPath()
    }

    class Caret(d: String, override val ink: DoodleInk, val width: Float, override val delayMs: Long) : DoodleMark {
        override val path: Path = PathParser().parsePathString(d).toPath()
    }
}

// All coordinates are in a 260 × 170 box (the approved web prototype's viewBox).
private fun doodleMarks(doodle: NoteDoodle): List<DoodleMark> = when (doodle) {
    NoteDoodle.Stroke -> listOf(
        DoodleMark.Fill("M25 104 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0 Z", DoodleInk.Tertiary, delayMs = 0),
        DoodleMark.Line(
            "M30 104 C44 78 58 128 74 104 S102 78 116 104 S144 128 158 102 S184 80 198 100",
            DoodleInk.Primary, width = 3.2f, delayMs = 250, long = true,
        ),
        DoodleMark.Fill(
            "M196 122 a10 7.5 0 1 0 20 0 a10 7.5 0 1 0 -20 0 Z",
            DoodleInk.Secondary, delayMs = 1_300, rotationDegrees = -22f,
        ),
        DoodleMark.Line("M215 119 L214 76", DoodleInk.Secondary, width = 3f, delayMs = 1_400),
        DoodleMark.Line("M214 76 C226 80 232 90 226 102", DoodleInk.Secondary, width = 3f, delayMs = 1_700),
        DoodleMark.Line("M232 66 l8 -8", DoodleInk.Tertiary, width = 2.4f, delayMs = 2_000),
        DoodleMark.Line("M240 80 l10 -2", DoodleInk.Tertiary, width = 2.4f, delayMs = 2_100),
    )
    NoteDoodle.Paper -> listOf(
        DoodleMark.Fill("M56 26 L200 18 L208 132 L66 142 Z", DoodleInk.Paper, delayMs = 0),
        DoodleMark.Line("M56 26 L200 18 L208 132 L184 134", DoodleInk.Secondary, width = 2.4f, delayMs = 100, long = true),
        DoodleMark.Line("M184 134 L66 142 L56 26", DoodleInk.Secondary, width = 2.4f, delayMs = 550, long = true),
        DoodleMark.Line("M184 134 C186 124 190 116 207 114", DoodleInk.Secondary, width = 2.4f, delayMs = 900),
        DoodleMark.Line("M78 58 C108 52 146 56 182 50", DoodleInk.Primary, width = 3f, delayMs = 1_150),
        DoodleMark.Line("M80 82 C116 78 150 81 186 76", DoodleInk.Primary, width = 3f, delayMs = 1_500),
        DoodleMark.Line("M82 106 C98 103 112 105 126 102", DoodleInk.Primary, width = 3f, delayMs = 1_850),
        DoodleMark.Caret("M134 92 L135 114", DoodleInk.Tertiary, width = 3f, delayMs = 2_150),
    )
    NoteDoodle.Mark -> listOf(
        DoodleMark.Line(
            "M66 96 C58 52 120 30 168 44 C210 56 214 116 176 134 C138 150 76 140 66 104",
            DoodleInk.Outline, width = 2.4f, delayMs = 0, long = true,
        ),
        DoodleMark.Line("M128 88 L88 56", DoodleInk.Primary, width = 5f, delayMs = 600),
        DoodleMark.Line("M88 56 L106 58", DoodleInk.Primary, width = 5f, delayMs = 950),
        DoodleMark.Line("M88 56 L91 74", DoodleInk.Primary, width = 5f, delayMs = 1_050),
        DoodleMark.Line("M128 88 L178 80", DoodleInk.Secondary, width = 5f, delayMs = 1_200),
        DoodleMark.Line("M178 80 L163 70", DoodleInk.Secondary, width = 5f, delayMs = 1_550),
        DoodleMark.Line("M178 80 L165 93", DoodleInk.Secondary, width = 5f, delayMs = 1_650),
        DoodleMark.Line("M128 88 L119 136", DoodleInk.Tertiary, width = 5f, delayMs = 1_800),
        DoodleMark.Line("M119 136 L108 123", DoodleInk.Tertiary, width = 5f, delayMs = 2_150),
        DoodleMark.Line("M119 136 L131 125", DoodleInk.Tertiary, width = 5f, delayMs = 2_250),
    )
}

/** The drawings' coordinate box; callers size the canvas at this aspect ratio. */
internal const val NoteDoodleViewBoxWidth = 260f
internal const val NoteDoodleViewBoxHeight = 170f
private const val ViewBoxWidth = NoteDoodleViewBoxWidth

private const val CaretBlinks = 3
private const val PaperTint = 0.45f
private const val CaretBlinkMs = 500L

@Preview(name = "Note doodle · stroke", showBackground = true)
@Composable
private fun NoteDoodleStrokePreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { NoteEmptyDoodle(NoteDoodle.Stroke, Modifier.size(240.dp, 157.dp)) } }
}

@Preview(name = "Note doodle · paper", showBackground = true)
@Composable
private fun NoteDoodlePaperPreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { NoteEmptyDoodle(NoteDoodle.Paper, Modifier.size(240.dp, 157.dp)) } }
}

@Preview(name = "Note doodle · mark", showBackground = true)
@Composable
private fun NoteDoodleMarkPreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { NoteEmptyDoodle(NoteDoodle.Mark, Modifier.size(240.dp, 157.dp)) } }
}
