package com.gpo.yoin.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A fine, staggered screen-print texture carried by a curved reveal front.
 * All geometry follows the artwork's effect spring, including a frozen interrupted frame.
 */
internal class ArtworkHalftone {
    private val mask = Path()
    private val ink = Path()
    private val bloom = Path()

    fun draw(scope: DrawScope, progress: Float, direction: Int, primary: Color, tertiary: Color, content: () -> Unit) =
        with(scope) {
            val pitch = 6.dp.toPx()
            val columns = ceil(size.width / pitch).toInt().coerceAtLeast(1)
            val rows = ceil(size.height / (pitch * .866f)).toInt().coerceAtLeast(1)
            val cellWidth = size.width / columns
            val cellHeight = size.height / rows
            val fullRadius = hypot(cellWidth, cellHeight) * .53f
            val front = progress * 1.65f - .325f
            val fromRight = direction >= 0
            mask.rewind()
            ink.rewind()
            bloom.rewind()

            // Overscan keeps half-dots and the mask continuous at the artwork's edges.
            for (row in -1..rows) {
                val y = (row + .5f) * cellHeight
                val v = y / size.height
                // The field itself rolls while it travels. Its rate stays below
                // the reveal's forward rate so completed cells never reopen.
                val bend = .09f * sin((v * .95f + progress * .6f + .07f) * TAU) +
                    .045f * sin((v * 1.85f - progress * .4f - .1f) * TAU)
                val stagger = if (row and 1 == 0) 0f else .5f
                var solidEdge = if (fromRight) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
                for (column in -1..columns) {
                    val x = (column + .5f + stagger) * cellWidth
                    val u = x / size.width
                    val order = if (fromRight) 1f - u else u
                    val drift = .025f * sin((order * .8f + v * .8f + progress * .2f) * TAU) +
                        .015f * sin((order * 2.3f + v * 3.1f) * TAU)
                    val arrival = .14f + order * .72f + bend + drift
                    val local = ((front - arrival) / .52f + .5f).coerceIn(0f, 1f)
                    if (local <= 0f) continue
                    if (local >= 1f) {
                        solidEdge = if (fromRight) minOf(solidEdge, x) else maxOf(solidEdge, x)
                        continue
                    }

                    val envelope = sin(local * PI).toFloat()
                    val flowX = sin((v * 1.4f + order * .45f - progress * .8f) * TAU)
                    val flowY = sin((v * .7f - order * 1.3f + progress * .65f) * TAU)
                    val center = Offset(
                        x + cellWidth * .42f * flowX * envelope,
                        y + cellHeight * .38f * flowY * envelope
                    )
                    val growth = ((local - .12f) / .88f).coerceIn(0f, 1f)
                    val radius = fullRadius * (.2f * local + .8f * smoothstep(growth))
                    mask.dot(center, radius)
                    // A very thin colored fringe and a low-opacity outer bloom,
                    // both spatially confined to the moving front. No white wash.
                    ink.dot(center, radius + cellWidth * .06f * envelope)
                    bloom.dot(center, radius + cellWidth * .32f * envelope)
                }
                if (solidEdge.isFinite()) {
                    // Merge the completed region into row strips: only the moving
                    // band needs individual circles, rather than thousands of overlaps.
                    val edge = solidEdge.coerceIn(0f, size.width)
                    mask.addRect(
                        Rect(
                            if (fromRight) edge else 0f,
                            y - cellHeight * .5f,
                            if (fromRight) size.width else edge,
                            y + cellHeight * .5f
                        )
                    )
                    mask.dot(Offset(solidEdge, y), fullRadius)
                }
            }
            val tint = Brush.linearGradient(
                colors = listOf(primary, tertiary),
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height)
            )
            // The incoming image is translucent while it develops. Keep tint
            // outside its mask, otherwise joined dots leave a second color edge.
            clipPath(mask, clipOp = ClipOp.Difference) {
                drawPath(bloom, tint, alpha = .045f)
                drawPath(ink, tint, alpha = .14f)
            }
            clipPath(mask) { content() }
        }

    private fun Path.dot(center: Offset, radius: Float) {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }

    companion object {
        private const val TAU = (PI * 2).toFloat()

        // Keep the old cover fully opaque underneath; softly developing the
        // incoming print avoids a hard new-image/old-image boundary.
        fun opacity(progress: Float): Float = .62f + .38f * smoothstep(progress.coerceIn(0f, 1f))

        private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)
    }
}
