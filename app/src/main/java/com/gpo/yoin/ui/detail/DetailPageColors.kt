package com.gpo.yoin.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
