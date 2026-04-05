package com.gpo.yoin.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Composition local for Palette-driven color override.
 * When a song is playing, this will be replaced with cover-extracted colors (Phase 12).
 */
val LocalYoinColors = staticCompositionLocalOf { YoinDarkColorScheme }

@Composable
fun YoinTheme(
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = colorSchemeOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        YoinDarkColorScheme
    }

    CompositionLocalProvider(LocalYoinColors provides colorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YoinTypography,
            shapes = YoinShapes,
            content = content,
        )
    }
}

// ── Preview ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinThemeColorsPreview() {
    YoinTheme(colorSchemeOverride = YoinDarkColorScheme) {
        val cs = MaterialTheme.colorScheme
        val swatches = listOf(
            "Primary" to cs.primary,
            "OnPrimary" to cs.onPrimary,
            "PrimaryCont." to cs.primaryContainer,
            "Secondary" to cs.secondary,
            "Tertiary" to cs.tertiary,
            "Surface" to cs.surface,
            "SurfaceVar." to cs.surfaceVariant,
            "Error" to cs.error,
            "Outline" to cs.outline,
            "Background" to cs.background,
        )

        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            swatches.forEach { (label, color) ->
                ColorSwatch(label = label, color = color)
            }
        }
    }
}

@Composable
private fun ColorSwatch(label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color, shape = YoinShapes.medium),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
