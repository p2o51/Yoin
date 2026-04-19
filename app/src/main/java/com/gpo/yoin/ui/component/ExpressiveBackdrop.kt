package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.copy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

internal enum class ExpressiveBackdropVariant {
    Bun,
    Ghostish,
    Circle,
    SoftBoom,
}

private const val ExpressiveBackdropShapeScale = 0.88f
private const val ExpressiveBackdropArtworkShiftFraction = 0.08f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun expressiveBackdropShape(variant: ExpressiveBackdropVariant): Shape = when (variant) {
    ExpressiveBackdropVariant.Bun -> MaterialShapes.Bun.toShape()
    ExpressiveBackdropVariant.Ghostish -> MaterialShapes.Ghostish.toShape()
    ExpressiveBackdropVariant.Circle -> MaterialShapes.Circle.toShape()
    ExpressiveBackdropVariant.SoftBoom -> MaterialShapes.SoftBoom.toShape()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberCircleArrowMorph(): Morph = remember {
    Morph(
        start = MaterialShapes.Circle.normalized(),
        end = MaterialShapes.Arrow.normalized(),
    )
}

private class ExpressiveMorphShape(
    private val basePath: Path,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val transformedPath = basePath.copy()
        val matrix = Matrix()
        matrix.scale(x = size.width, y = size.height)
        transformedPath.transform(matrix)

        val boundsCenter = transformedPath.getBounds().center
        transformedPath.translate(
            Offset(
                x = (size.width / 2f) - boundsCenter.x,
                y = (size.height / 2f) - boundsCenter.y,
            ),
        )
        return Outline.Generic(transformedPath)
    }
}

/**
 * All playback-driven halo animation (morph / scale / color lerp) is
 * intentionally disabled project-wide. The halo now renders as a flat,
 * static shape fill: no pulse on `isPlaybackActive`, no spring
 * overshoot, no color drift. This was the right trade for shipping —
 * the halo's scale-time geometry kept bleeding onto the sibling cover's
 * shape edge (the Jump Back In playback edge-nip saga) and every
 * attempted clip / reorder / parameter tweak left some visual
 * regression on the table. Static halo = no geometry bleed + no frame
 * cost.
 *
 * `accentColor`, `isPlaybackActive`, `playbackSignal`, and
 * `interactionSource` are kept in the signature so call sites don't
 * have to change; they're currently ignored. If the pulse is wanted
 * back, restore the previous `animateFloatAsState(morphProgress/scale)`
 * + `animateColorAsState` body and wrap the draw in
 * `.graphicsLayer(scaleX/scaleY, clip=true).clip(shape).background(animatedColor)`
 * with `graphicsLayer` as the outermost modifier (git history on this
 * file shows the last known-good animated variant).
 */
@Composable
internal fun ExpressiveBackdrop(
    baseColor: Color,
    @Suppress("UNUSED_PARAMETER") accentColor: Color,
    modifier: Modifier = Modifier,
    variant: ExpressiveBackdropVariant = ExpressiveBackdropVariant.Bun,
    @Suppress("UNUSED_PARAMETER") interactionSource: MutableInteractionSource? = null,
    @Suppress("UNUSED_PARAMETER") isPlaybackActive: Boolean = false,
    @Suppress("UNUSED_PARAMETER") playbackSignal: Float = 0f,
) {
    val shape = expressiveBackdropShape(variant)
    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor.copy(alpha = 0.9f)),
    )
}

@Composable
internal fun ExpressiveBackdropArtwork(
    model: String?,
    contentDescription: String?,
    variant: ExpressiveBackdropVariant,
    modifier: Modifier = Modifier,
    shape: Shape,
    fallbackIcon: ImageVector,
    interactionSource: MutableInteractionSource? = null,
    isPlaybackActive: Boolean = false,
    playbackSignal: Float = 0f,
    fillFraction: Float = 1f,
    backdropScale: Float = ExpressiveBackdropShapeScale,
    artworkShiftFraction: Float = ExpressiveBackdropArtworkShiftFraction,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
    tonalElevation: Dp = 1.dp,
    shadowElevation: Dp = 0.dp,
    extractBackdropColors: Boolean = true,
) {
    val backdropColors = rememberExpressiveBackdropColors(
        model = model,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    val scaledFillFraction = (fillFraction * ExpressiveBackdropArtworkScale).coerceIn(0.36f, 1f)

    BoxWithConstraints(modifier = modifier) {
        val opticalShift = minOf(maxWidth, maxHeight) * artworkShiftFraction
        ExpressiveBackdrop(
            baseColor = backdropColors.baseColor,
            accentColor = backdropColors.accentColor,
            variant = variant,
            interactionSource = interactionSource,
            isPlaybackActive = isPlaybackActive,
            playbackSignal = playbackSignal,
            modifier = Modifier
                .fillMaxSize(backdropScale)
                .align(Alignment.Center)
                .offset(x = -opticalShift, y = -opticalShift),
        )
        ExpressiveMediaArtwork(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize(scaledFillFraction)
                .align(Alignment.Center)
                .offset(
                    x = opticalShift + offsetX * 0.25f,
                    y = opticalShift + offsetY * 0.25f,
                ),
            shape = shape,
            fallbackIcon = fallbackIcon,
            interactionSource = interactionSource,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ExpressiveBackdropPreview() {
    YoinTheme {
        Row(
            modifier = Modifier
                .background(color = MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveBackdropVariant.entries.forEach { variant ->
                Box(modifier = Modifier.size(92.dp)) {
                    ExpressiveBackdrop(
                        baseColor = MaterialTheme.colorScheme.secondaryContainer,
                        accentColor = MaterialTheme.colorScheme.tertiaryContainer,
                        variant = variant,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
