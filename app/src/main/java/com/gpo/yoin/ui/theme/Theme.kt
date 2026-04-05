package com.gpo.yoin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val FallbackDarkColorScheme = darkColorScheme()

/**
 * Composition local for Palette-driven color override.
 * When a song is playing, this will be replaced with cover-extracted colors (Phase 12).
 */
val LocalYoinColors = staticCompositionLocalOf { FallbackDarkColorScheme }

@Composable
fun YoinTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FallbackDarkColorScheme
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
