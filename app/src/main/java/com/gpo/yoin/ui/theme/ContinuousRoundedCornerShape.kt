package com.gpo.yoin.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import kotlin.math.min

/**
 * iOS-style continuous ("squircle") rounded rectangle.
 *
 * Built on `androidx.graphics.shapes`: a rounded rectangle whose corners carry a
 * [smoothing] factor, so the transition from a straight edge into the corner arc
 * eases through two flanking curves instead of meeting the arc abruptly. A
 * `smoothing` of ~0.6 approximates iOS / Figma "60% corner smoothing".
 *
 * The corner geometry is rebuilt at the real [size] every call (not a cached,
 * scaled unit path), so the arc stays circular at any aspect ratio.
 *
 * Note: this produces an [Outline.Generic] (an arbitrary path). Fills — `Surface`
 * and `Modifier.background` — are anti-aliased, and elevation shadows render fine
 * because the shape is convex. Hardware clipping of a generic path is NOT
 * anti-aliased, so prefer this on filled / at-rest surfaces rather than on layers
 * that are actively scaling.
 */
class ContinuousRoundedCornerShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { radius.toPx() }
            .coerceAtMost(min(size.width, size.height) / 2f)
        if (radiusPx <= 0f || size.minDimension <= 0f) {
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
        // rectangle() is centred on the origin, so translate it back into the
        // (0,0)..(width,height) box the outline is expected to occupy.
        val polygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(radius = radiusPx, smoothing = smoothing),
        )
        val path = polygon.toPath().asComposePath().apply {
            translate(Offset(size.width / 2f, size.height / 2f))
        }
        return Outline.Generic(path)
    }
}
