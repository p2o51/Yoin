package com.gpo.yoin.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Entrance animations project-wide have been disabled — items render
 * statically instead of fading / scaling / translating in. The helpers
 * below stay as no-ops so call sites in Home / Library / ArtistDetail
 * don't need to change. If an animated entrance is wanted back for a
 * specific surface later, restore the previous `Animatable` + `graphicsLayer`
 * implementation (`rememberExpressiveEntranceProgress` animating 0 → 1
 * over a slowSpatialSpec, `expressiveEntrance` mapping progress to alpha +
 * scale + translationY).
 */

@Composable
internal fun rememberExpressiveEntranceProgress(
    @Suppress("UNUSED_PARAMETER") key: Any,
    @Suppress("UNUSED_PARAMETER") delayMillis: Long = 0L,
): Float = 1f

@Composable
internal fun Modifier.expressiveEntrance(
    @Suppress("UNUSED_PARAMETER") progress: Float,
    @Suppress("UNUSED_PARAMETER") initialOffsetY: Dp = 18.dp,
    @Suppress("UNUSED_PARAMETER") initialScale: Float = 0.94f,
): Modifier = this
