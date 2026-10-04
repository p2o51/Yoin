package com.gpo.yoin.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A stroke along [shape]'s outline that can be dashed, solid, or anywhere in
 * between: [solidity] 0 = dashes of [dash] separated by [gap], 1 = one
 * unbroken line. In between, the gaps close while the dash rhythm holds, so a
 * dashed outline "inks in" rather than fading — the dashed ⇄ solid language
 * for a slot that is waiting vs. a slot that is filled.
 *
 * The dash period is stretched so a whole number of dashes fits the
 * perimeter: no half dash where the path starts and ends.
 *
 * Both lambdas are read in the draw phase only, so a gesture or animation
 * driving them never recomposes the host. The stroke sits inside the bounds
 * (inset by half its width), like [androidx.compose.foundation.border].
 * Never put this on cover art — covers carry no outline at all.
 */
fun Modifier.dashedOutline(
    shape: Shape,
    color: () -> Color,
    width: Dp = 1.dp,
    dash: Dp = 6.dp,
    gap: Dp = 4.dp,
    solidity: () -> Float = { 0f },
): Modifier = drawWithCache {
    val strokePx = width.toPx()
    val inset = strokePx / 2f
    val innerSize = Size(
        width = (size.width - strokePx).coerceAtLeast(0f),
        height = (size.height - strokePx).coerceAtLeast(0f),
    )
    val path = shape.createOutline(innerSize, layoutDirection, this).toPath()
    val perimeter = PathMeasure().apply { setPath(path, forceClosed = true) }.length
    val dashPx = dash.toPx()
    val gapPx = gap.toPx()
    // Whole dashes around the loop: stretch the period to divide the perimeter.
    val periods = max(1, (perimeter / (dashPx + gapPx)).roundToInt())
    val period = if (perimeter > 0f) perimeter / periods else dashPx + gapPx
    val dashShare = dashPx / (dashPx + gapPx)
    onDrawWithContent {
        drawContent()
        val ink = color()
        if (ink.alpha <= 0f || innerSize.minDimension <= 0f) return@onDrawWithContent
        val s = solidity().coerceIn(0f, 1f)
        // Gaps shrink toward zero; the dash grows into them, period unchanged.
        val onPx = period * (dashShare + (1f - dashShare) * s)
        val offPx = period - onPx
        val effect = if (offPx < 0.5f) {
            null
        } else {
            // Centre each dash on its slot so closing reads symmetric.
            PathEffect.dashPathEffect(floatArrayOf(onPx, offPx), phase = -offPx / 2f)
        }
        translate(left = inset, top = inset) {
            drawPath(
                path = path,
                color = ink,
                style = Stroke(width = strokePx, pathEffect = effect),
            )
        }
    }
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
}
