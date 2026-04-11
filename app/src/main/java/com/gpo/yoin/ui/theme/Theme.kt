package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Composition local for the resolved color scheme (may be palette-driven).
 */
val LocalYoinColors = staticCompositionLocalOf { YoinDarkColorScheme }

/**
 * App-wide theme wrapper.
 *
 * @param colorSchemeOverride Hard override for previews / tests.
 * @param coverBitmap When non-null, album art resolves to a single seed color and
 *   the entire [MaterialTheme.colorScheme] animates to an expressive `SPEC_2025`
 *   scheme. When `null` (playback stopped), colors animate back to the default
 *   dynamic / dark scheme.
 */
@Composable
fun YoinTheme(
    colorSchemeOverride: ColorScheme? = null,
    motionSchemeOverride: MotionScheme? = null,
    coverBitmap: Bitmap? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolvedDarkTheme by rememberUpdatedState(darkTheme)
    val defaultScheme = colorSchemeOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) YoinDarkColorScheme else YoinLightColorScheme
    }

    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (resolvedDarkTheme) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
            },
            navigationBarStyle = if (resolvedDarkTheme) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
            },
        )
    }

    // Resolve a single seed from cover art, then build a SPEC_2025 expressive scheme.
    var extractedScheme by remember { mutableStateOf<ColorScheme?>(null) }
    LaunchedEffect(coverBitmap, darkTheme) {
        extractedScheme = CoverSeedExtractor.extractSeedArgb(coverBitmap)?.let { seedArgb ->
            ExpressiveColorSchemeFactory.fromSeed(
                seedArgb = seedArgb,
                isDark = darkTheme,
            )
        }
    }

    val targetScheme = extractedScheme ?: defaultScheme
    val motionScheme = motionSchemeOverride ?: MotionScheme.expressive()

    // Animate every token via the current effects motion bucket — zero hard color cuts.
    val colorScheme = animateColorScheme(
        targetColorScheme = targetScheme,
        darkTheme = darkTheme,
        motionScheme = motionScheme,
    )

    CompositionLocalProvider(LocalYoinColors provides colorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = YoinShapes,
            typography = YoinTypography,
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
