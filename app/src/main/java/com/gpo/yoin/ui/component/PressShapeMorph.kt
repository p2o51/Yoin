package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * M3 Expressive press morph: while [interactionSource] is pressed, the shape
 * tweens seamlessly from [base] to the rounded [MaterialShapes.Triangle]
 * token and springs back on release. MaterialShapes are all normalized
 * [RoundedPolygon]s, so any pair morphs cleanly — [Morph] matches their
 * curves and the spatial spring drives one progress float.
 *
 * The returned Shape is one remembered instance that reads the spring's
 * progress in createOutline, so the press only re-resolves the outline (draw
 * phase): background and graphicsLayer clip observe those reads. Nothing
 * recomposes per frame — the same approach as Compose 1.13's
 * MorphPolygonShape, which takes a PolygonShape and so can't take
 * MaterialShapes directly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberPressMorphShape(
    base: RoundedPolygon,
    interactionSource: InteractionSource,
    pressed: RoundedPolygon = MaterialShapes.Triangle,
): Shape {
    val isPressed by interactionSource.collectIsPressedAsState()
    val progress = animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = YoinMotion.fastSpatialSpec(role = YoinMotionRole.Expressive),
        label = "pressShapeMorph",
    )
    val morph = remember(base, pressed) { Morph(base, pressed) }
    return remember(morph, progress) { MorphProgressShape(morph) { progress.value } }
}

/**
 * A [Morph] at [progress], scaled from the unit square to the layout bounds.
 *
 * [progress] is read when the outline is resolved, not in composition: hand it
 * a lambda over animation state and the shape follows the animation with draw
 * / layer invalidations only. Keep one remembered instance per element.
 */
internal class MorphProgressShape(
    private val morph: Morph,
    private val progress: () -> Float,
) : Shape {
    /** A morph frozen at [progress]. */
    constructor(morph: Morph, progress: Float) : this(morph, { progress })

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        // MaterialShapes polygons are normalized to the unit square — scale
        // the morphed path up to the composable's bounds.
        val matrix = Matrix()
        matrix.scale(size.width, size.height, 1f)
        val path = morph.toPath(progress().coerceIn(0f, 1f)).asComposePath()
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
