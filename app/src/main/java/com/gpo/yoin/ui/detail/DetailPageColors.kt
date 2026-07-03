package com.gpo.yoin.ui.detail

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors

/** Cover-derived accent for [ExpressivePageBackground], gated on palette resolve. */
@Composable
internal fun rememberDetailPageAccent(coverUrl: String?): Color? {
    val colors = rememberExpressiveBackdropColors(
        model = coverUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.surfaceContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.secondaryContainer,
    )
    return colors.accentColor.takeIf { colors.isResolvedFromPalette }
}

/** Shared floating-toolbar bar tint: halfway between surface and cover secondary container. */
@Composable
internal fun rememberDetailToolbarTint(headerScheme: ColorScheme): Color =
    lerp(
        MaterialTheme.colorScheme.surfaceContainer,
        headerScheme.secondaryContainer,
        0.5f,
    )
