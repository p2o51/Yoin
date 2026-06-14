package com.gpo.yoin.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class ExpressiveBackdropVariant {
    Bun,
    Ghostish,
    Circle,
    SoftBoom,
}

private const val ExpressiveBackdropShapeScale = 0.88f
private const val ExpressiveBackdropArtworkShiftFraction = 0.08f

@Composable
internal fun ExpressiveBackdropArtwork(
    model: String?,
    contentDescription: String?,
    @Suppress("UNUSED_PARAMETER") variant: ExpressiveBackdropVariant,
    modifier: Modifier = Modifier,
    shape: Shape,
    fallbackIcon: ImageVector,
    interactionSource: MutableInteractionSource? = null,
    @Suppress("UNUSED_PARAMETER") isPlaybackActive: Boolean = false,
    @Suppress("UNUSED_PARAMETER") playbackSignal: Float = 0f,
    fillFraction: Float = 1f,
    @Suppress("UNUSED_PARAMETER") backdropScale: Float = ExpressiveBackdropShapeScale,
    @Suppress("UNUSED_PARAMETER") artworkShiftFraction: Float = ExpressiveBackdropArtworkShiftFraction,
    @Suppress("UNUSED_PARAMETER") offsetX: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") offsetY: Dp = 0.dp,
    tonalElevation: Dp = 1.dp,
    shadowElevation: Dp = 0.dp,
    extractBackdropColors: Boolean = true,
) {
    // Keep color extraction active to populate the palette cache for page-level backgrounds
    val backdropColors = rememberExpressiveBackdropColors(
        model = model,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )

    Box(modifier = modifier) {
        ExpressiveMediaArtwork(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize(fillFraction)
                .align(Alignment.Center),
            shape = shape,
            fallbackIcon = fallbackIcon,
            interactionSource = interactionSource,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
        )
    }
}
