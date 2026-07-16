package com.gpo.yoin.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath

/**
 * A rounded rectangle with iOS-style continuous corners: extra cubic segments
 * carry the curvature smoothly from the straight edges into the corner arcs
 * instead of jumping from zero curvature to 1/radius (what Apple calls
 * "continuous" corner curvature).
 *
 * Built on graphics-shapes' [CornerRounding] smoothing. Unlike
 * [androidx.compose.foundation.shape.RoundedCornerShape] this produces an
 * [Outline.Generic] path, so reserve it for shapes whose SIZE is stable —
 * per-frame-resized clips (shared-element flights, carousel masks, reshape
 * morphs) should keep plain RoundedCornerShape, whose Outline.Rounded is
 * cheaper to rebuild and clip every frame.
 *
 * Extends [CornerBasedShape] so it can slot into Material3 `Shapes` later if
 * the global theme ever migrates.
 */
class ContinuousRoundedCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    val smoothing: Float = ContinuousSmoothing,
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart,
) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0.0f) {
            return Outline.Rectangle(size.toRect())
        }
        val ltr = layoutDirection == LayoutDirection.Ltr
        val topLeft = if (ltr) topStart else topEnd
        val topRight = if (ltr) topEnd else topStart
        val bottomRight = if (ltr) bottomEnd else bottomStart
        val bottomLeft = if (ltr) bottomStart else bottomEnd
        val polygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            // rectangle()'s vertex order is bottom-right, bottom-left,
            // top-left, top-right (y grows downward on screen).
            perVertexRounding = listOf(
                CornerRounding(bottomRight, smoothing),
                CornerRounding(bottomLeft, smoothing),
                CornerRounding(topLeft, smoothing),
                CornerRounding(topRight, smoothing),
            ),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ContinuousRoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart,
        smoothing = smoothing,
    )

    override fun lerp(other: Any?, t: Float): Any? {
        val target = when (other) {
            is ContinuousRoundedCornerShape -> other
            // A plain rounded/rect target is a continuous shape with 0 smoothing.
            is androidx.compose.foundation.shape.RoundedCornerShape -> ContinuousRoundedCornerShape(
                topStart = other.topStart,
                topEnd = other.topEnd,
                bottomEnd = other.bottomEnd,
                bottomStart = other.bottomStart,
                smoothing = 0f,
            )
            RectangleShape, null -> ContinuousRoundedCornerShape(
                topStart = ZeroCornerSize,
                topEnd = ZeroCornerSize,
                bottomEnd = ZeroCornerSize,
                bottomStart = ZeroCornerSize,
                smoothing = smoothing,
            )
            else -> return null
        }
        return ContinuousRoundedCornerShape(
            topStart = lerpCornerSize(topStart, target.topStart, t),
            topEnd = lerpCornerSize(topEnd, target.topEnd, t),
            bottomEnd = lerpCornerSize(bottomEnd, target.bottomEnd, t),
            bottomStart = lerpCornerSize(bottomStart, target.bottomStart, t),
            smoothing = lerp(smoothing, target.smoothing, t),
        )
    }

    override fun toString(): String =
        "ContinuousRoundedCornerShape(topStart = $topStart, topEnd = $topEnd, " +
            "bottomEnd = $bottomEnd, bottomStart = $bottomStart, smoothing = $smoothing)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContinuousRoundedCornerShape) return false
        if (topStart != other.topStart) return false
        if (topEnd != other.topEnd) return false
        if (bottomEnd != other.bottomEnd) return false
        if (bottomStart != other.bottomStart) return false
        if (smoothing != other.smoothing) return false
        return true
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }
}

/**
 * Default corner smoothing (0 = circular arc, 1 = fully cubic). 0.6 tracks the
 * visual character of SwiftUI's `.continuous` corner style.
 */
const val ContinuousSmoothing = 0.6f

fun ContinuousRoundedCornerShape(
    size: Dp,
    smoothing: Float = ContinuousSmoothing,
): ContinuousRoundedCornerShape {
    val corner = CornerSize(size)
    return ContinuousRoundedCornerShape(corner, corner, corner, corner, smoothing)
}

fun ContinuousRoundedCornerShape(
    topStart: Dp,
    topEnd: Dp,
    bottomEnd: Dp,
    bottomStart: Dp,
    smoothing: Float = ContinuousSmoothing,
): ContinuousRoundedCornerShape = ContinuousRoundedCornerShape(
    topStart = CornerSize(topStart),
    topEnd = CornerSize(topEnd),
    bottomEnd = CornerSize(bottomEnd),
    bottomStart = CornerSize(bottomStart),
    smoothing = smoothing,
)

private val ZeroCornerSize = CornerSize(0)

// foundation's CornerSize lerp is internal; same trivial px-space blend.
private fun lerpCornerSize(a: CornerSize, b: CornerSize, t: Float): CornerSize =
    object : CornerSize {
        override fun toPx(shapeSize: Size, density: Density): Float =
            lerp(a.toPx(shapeSize, density), b.toPx(shapeSize, density), t)
    }
