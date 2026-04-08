package com.gpo.yoin.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.gpo.yoin.ui.theme.YoinTheme

internal enum class ExpressiveBackdropVariant(
    val startVertices: Int,
    val endVertices: Int,
    val progress: Float,
    val rotation: Float,
    val roundingFraction: Float,
    val endRadiusFraction: Float,
) {
    Bloom(
        startVertices = 6,
        endVertices = 4,
        progress = 0.22f,
        rotation = -14f,
        roundingFraction = 0.18f,
        endRadiusFraction = 0.44f,
    ),
    Orbit(
        startVertices = 5,
        endVertices = 7,
        progress = 0.58f,
        rotation = 18f,
        roundingFraction = 0.2f,
        endRadiusFraction = 0.47f,
    ),
    Pebble(
        startVertices = 4,
        endVertices = 8,
        progress = 0.4f,
        rotation = -22f,
        roundingFraction = 0.22f,
        endRadiusFraction = 0.42f,
    ),
}

internal fun expressiveBackdropVariantAt(index: Int): ExpressiveBackdropVariant {
    val variants = ExpressiveBackdropVariant.entries
    return variants[index.mod(variants.size)]
}

@Suppress("MagicNumber")
@androidx.compose.runtime.Composable
internal fun ExpressiveBackdrop(
    baseColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    variant: ExpressiveBackdropVariant = ExpressiveBackdropVariant.Bloom,
) {
    Canvas(modifier = modifier) {
        val minDimension = size.minDimension
        val pivot = Offset(size.width / 2f, size.height / 2f)
        val primaryRounding = CornerRounding(
            radius = minDimension * variant.roundingFraction,
            smoothing = 0.82f,
        )
        val secondaryRounding = CornerRounding(
            radius = minDimension * variant.roundingFraction * 0.82f,
            smoothing = 1f,
        )
        val startPolygon = RoundedPolygon(
            numVertices = variant.startVertices,
            radius = minDimension * 0.48f,
            centerX = size.width * 0.43f,
            centerY = size.height * 0.48f,
            rounding = primaryRounding,
        )
        val endPolygon = RoundedPolygon(
            numVertices = variant.endVertices,
            radius = minDimension * variant.endRadiusFraction,
            centerX = size.width * 0.58f,
            centerY = size.height * 0.52f,
            rounding = secondaryRounding,
        )
        val morphPath = Morph(start = startPolygon, end = endPolygon)
            .toPath(progress = variant.progress)
            .asComposePath()

        rotate(degrees = variant.rotation, pivot = pivot) {
            drawPath(
                path = morphPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.92f),
                        accentColor.copy(alpha = 0.58f),
                    ),
                    center = Offset(size.width * 0.48f, size.height * 0.42f),
                    radius = minDimension * 0.9f,
                ),
            )
        }

        val orbitPolygon = RoundedPolygon(
            numVertices = variant.endVertices + 1,
            radius = minDimension * 0.18f,
            centerX = size.width * 0.73f,
            centerY = size.height * 0.24f,
            rounding = secondaryRounding,
        )
        drawPath(
            path = orbitPolygon.toPath().asComposePath(),
            color = accentColor.copy(alpha = 0.24f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@androidx.compose.runtime.Composable
private fun ExpressiveBackdropPreview() {
    YoinTheme {
        Row(
            modifier = Modifier
                .background(color = androidx.compose.material3.MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveBackdropVariant.entries.forEach { variant ->
                Box(modifier = Modifier.size(92.dp)) {
                    ExpressiveBackdrop(
                        baseColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                        accentColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
                        variant = variant,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}
