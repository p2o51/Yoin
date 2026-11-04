package com.gpo.yoin.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    // Dots as circles, not as one many-oval Path used as a clip: Skia rasterises
    // a clip that complex on the CPU every frame and uploads it as a mask, and
    // the tablet's GPU sat waiting on that for the whole ~250 ms reveal (Vulkan
    // flush >10 ms a frame, gone with the dissolve off). Circles and rects draw
    // analytically on the GPU; layers with DstIn / DstOut do what the clips did.
    private val mask = DotList()
    private val ink = DotList()
    private val bloom = DotList()
    private val strips = ArrayList<Rect>()
    private val layerPaint = Paint()
    private val maskInPaint = Paint().apply { blendMode = BlendMode.DstIn }
    private val maskOutPaint = Paint().apply { blendMode = BlendMode.DstOut }

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
            mask.clear()
            ink.clear()
            bloom.clear()
            strips.clear()

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
                    mask.add(center, radius)
                    // A very thin colored fringe and a low-opacity outer bloom,
                    // both spatially confined to the moving front. No white wash.
                    ink.add(center, radius + cellWidth * .06f * envelope)
                    bloom.add(center, radius + cellWidth * .32f * envelope)
                }
                if (solidEdge.isFinite()) {
                    // Merge the completed region into row strips: only the moving
                    // band needs individual circles, rather than thousands of overlaps.
                    // Strips are drawn one by one now, each antialiased: overlap
                    // neighbours by a pixel so their edges don't leave hairline
                    // seams (the mask is opaque, so overlaps can't darken).
                    val edge = solidEdge.coerceIn(0f, size.width)
                    strips.add(
                        Rect(
                            if (fromRight) edge else 0f,
                            y - cellHeight * .5f - STRIP_OVERLAP_PX,
                            if (fromRight) size.width else edge,
                            y + cellHeight * .5f + STRIP_OVERLAP_PX
                        )
                    )
                    mask.add(Offset(solidEdge, y), fullRadius)
                }
            }
            val tint = Brush.linearGradient(
                colors = listOf(primary, tertiary),
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height)
            )
            // The incoming image is translucent while it develops. Keep tint
            // outside its mask, otherwise joined dots leave a second color edge.
            tintedOutsideMask(bloom, tint, alpha = .045f)
            tintedOutsideMask(ink, tint, alpha = .14f)
            // The incoming image, kept only inside the mask (the old clipPath).
            val bounds = Rect(Offset.Zero, size)
            val canvas = drawContext.canvas
            canvas.saveLayer(bounds, layerPaint.apply { alpha = 1f })
            content()
            canvas.saveLayer(bounds, maskInPaint)
            drawMask()
            canvas.restore()
            canvas.restore()
        }

    /** [dots] as one union, filled with [tint] at [alpha], minus the mask. */
    private fun DrawScope.tintedOutsideMask(dots: DotList, tint: Brush, alpha: Float) {
        if (dots.isEmpty()) return
        val bounds = Rect(Offset.Zero, size)
        val canvas = drawContext.canvas
        // Opaque union first, then tint and alpha once: overlaps don't darken.
        canvas.saveLayer(bounds, layerPaint.apply { this.alpha = alpha })
        dots.draw(this)
        drawRect(tint, blendMode = BlendMode.SrcIn)
        canvas.saveLayer(bounds, maskOutPaint)
        drawMask()
        canvas.restore()
        canvas.restore()
    }

    private fun DrawScope.drawMask() {
        strips.forEach { drawRect(Color.Black, topLeft = it.topLeft, size = it.size) }
        mask.draw(this)
    }

    /** Circle centres and radii in flat arrays, reused frame to frame. */
    private class DotList {
        private var data = FloatArray(3 * 256)
        private var count = 0

        fun clear() {
            count = 0
        }

        fun isEmpty() = count == 0

        fun add(center: Offset, radius: Float) {
            if (radius <= 0f) return
            if ((count + 1) * 3 > data.size) data = data.copyOf(data.size * 2)
            data[count * 3] = center.x
            data[count * 3 + 1] = center.y
            data[count * 3 + 2] = radius
            count++
        }

        fun draw(scope: DrawScope) {
            for (i in 0 until count) {
                scope.drawCircle(Color.Black, radius = data[i * 3 + 2], center = Offset(data[i * 3], data[i * 3 + 1]))
            }
        }
    }

    companion object {
        private const val TAU = (PI * 2).toFloat()
        private const val STRIP_OVERLAP_PX = 1f

        // Keep the old cover fully opaque underneath; softly developing the
        // incoming print avoids a hard new-image/old-image boundary.
        fun opacity(progress: Float): Float = .62f + .38f * smoothstep(progress.coerceIn(0f, 1f))

        private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)
    }
}
