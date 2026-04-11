package com.gpo.yoin.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Smoothly transitions **every** token in [targetColorScheme] using the theme's
 * default effects motion bucket.
 *
 * When the target changes — e.g. new album cover → new palette — all colors animate
 * in concert, producing a seamless global wash across the entire app.
 */
@Composable
fun animateColorScheme(
    targetColorScheme: ColorScheme,
    darkTheme: Boolean,
    motionScheme: MotionScheme,
): ColorScheme {
    val spec = YoinMotion.defaultEffectsSpec<Color>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = motionScheme,
    )

    // ── Primary ──────────────────────────────────────────────────────────
    val primary by animateColorAsState(targetColorScheme.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(targetColorScheme.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(
        targetColorScheme.primaryContainer,
        spec,
        label = "primaryContainer",
    )
    val onPrimaryContainer by animateColorAsState(
        targetColorScheme.onPrimaryContainer,
        spec,
        label = "onPrimaryContainer",
    )
    val inversePrimary by animateColorAsState(
        targetColorScheme.inversePrimary,
        spec,
        label = "inversePrimary",
    )

    // ── Secondary ────────────────────────────────────────────────────────
    val secondary by animateColorAsState(targetColorScheme.secondary, spec, label = "secondary")
    val onSecondary by animateColorAsState(
        targetColorScheme.onSecondary,
        spec,
        label = "onSecondary",
    )
    val secondaryContainer by animateColorAsState(
        targetColorScheme.secondaryContainer,
        spec,
        label = "secondaryContainer",
    )
    val onSecondaryContainer by animateColorAsState(
        targetColorScheme.onSecondaryContainer,
        spec,
        label = "onSecondaryContainer",
    )

    // ── Tertiary ─────────────────────────────────────────────────────────
    val tertiary by animateColorAsState(targetColorScheme.tertiary, spec, label = "tertiary")
    val onTertiary by animateColorAsState(
        targetColorScheme.onTertiary,
        spec,
        label = "onTertiary",
    )
    val tertiaryContainer by animateColorAsState(
        targetColorScheme.tertiaryContainer,
        spec,
        label = "tertiaryContainer",
    )
    val onTertiaryContainer by animateColorAsState(
        targetColorScheme.onTertiaryContainer,
        spec,
        label = "onTertiaryContainer",
    )

    // ── Background & Surface ─────────────────────────────────────────────
    val background by animateColorAsState(
        targetColorScheme.background,
        spec,
        label = "background",
    )
    val onBackground by animateColorAsState(
        targetColorScheme.onBackground,
        spec,
        label = "onBackground",
    )
    val surface by animateColorAsState(targetColorScheme.surface, spec, label = "surface")
    val onSurface by animateColorAsState(targetColorScheme.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(
        targetColorScheme.surfaceVariant,
        spec,
        label = "surfaceVariant",
    )
    val onSurfaceVariant by animateColorAsState(
        targetColorScheme.onSurfaceVariant,
        spec,
        label = "onSurfaceVariant",
    )
    val surfaceTint by animateColorAsState(
        targetColorScheme.surfaceTint,
        spec,
        label = "surfaceTint",
    )

    // ── Inverse ──────────────────────────────────────────────────────────
    val inverseSurface by animateColorAsState(
        targetColorScheme.inverseSurface,
        spec,
        label = "inverseSurface",
    )
    val inverseOnSurface by animateColorAsState(
        targetColorScheme.inverseOnSurface,
        spec,
        label = "inverseOnSurface",
    )

    // ── Error ────────────────────────────────────────────────────────────
    val error by animateColorAsState(targetColorScheme.error, spec, label = "error")
    val onError by animateColorAsState(targetColorScheme.onError, spec, label = "onError")
    val errorContainer by animateColorAsState(
        targetColorScheme.errorContainer,
        spec,
        label = "errorContainer",
    )
    val onErrorContainer by animateColorAsState(
        targetColorScheme.onErrorContainer,
        spec,
        label = "onErrorContainer",
    )

    // ── Outline ──────────────────────────────────────────────────────────
    val outline by animateColorAsState(targetColorScheme.outline, spec, label = "outline")
    val outlineVariant by animateColorAsState(
        targetColorScheme.outlineVariant,
        spec,
        label = "outlineVariant",
    )

    // ── Scrim ────────────────────────────────────────────────────────────
    val scrim by animateColorAsState(targetColorScheme.scrim, spec, label = "scrim")

    // ── Surface variants ─────────────────────────────────────────────────
    val surfaceBright by animateColorAsState(
        targetColorScheme.surfaceBright,
        spec,
        label = "surfaceBright",
    )
    val surfaceDim by animateColorAsState(
        targetColorScheme.surfaceDim,
        spec,
        label = "surfaceDim",
    )
    val surfaceContainer by animateColorAsState(
        targetColorScheme.surfaceContainer,
        spec,
        label = "surfaceContainer",
    )
    val surfaceContainerHigh by animateColorAsState(
        targetColorScheme.surfaceContainerHigh,
        spec,
        label = "surfaceContainerHigh",
    )
    val surfaceContainerHighest by animateColorAsState(
        targetColorScheme.surfaceContainerHighest,
        spec,
        label = "surfaceContainerHighest",
    )
    val surfaceContainerLow by animateColorAsState(
        targetColorScheme.surfaceContainerLow,
        spec,
        label = "surfaceContainerLow",
    )
    val surfaceContainerLowest by animateColorAsState(
        targetColorScheme.surfaceContainerLowest,
        spec,
        label = "surfaceContainerLowest",
    )

    return if (darkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )
    }
}

// ── Preview ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AnimatedColorSchemePreview() {
    val animated = animateColorScheme(
        targetColorScheme = YoinDarkColorScheme,
        darkTheme = true,
        motionScheme = MotionScheme.expressive(),
    )
    MaterialTheme(colorScheme = animated) {
        val cs = MaterialTheme.colorScheme
        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Primary" to cs.primary,
                "Secondary" to cs.secondary,
                "Surface" to cs.surface,
                "Background" to cs.background,
            ).forEach { (label, color) ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color, shape = YoinShapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}
