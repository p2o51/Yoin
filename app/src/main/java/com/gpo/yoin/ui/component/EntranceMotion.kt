package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.LocalYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.delay

@Composable
internal fun rememberExpressiveEntranceProgress(
    key: Any,
    delayMillis: Long = 0L,
): Float {
    val motionProfile = LocalMotionProfile.current
    val motionRole = LocalYoinMotionRole.current
    val progress = remember(key) { Animatable(0f) }
    val fullSpec = YoinMotion.slowSpatialSpec<Float>(role = motionRole)
    val reducedSpec = YoinMotion.fastSpatialSpec<Float>(role = motionRole)
    // Intentionally key only on `key` (the stable id of the entry). Changes to
    // motionProfile or motionRole must not re-trigger the entrance animation,
    // because they can flip for unrelated reasons (device pressure heuristics,
    // theme role scope changes) during normal navigation and produce a visible
    // animation replay on cards that never moved.
    LaunchedEffect(key) {
        if (progress.value >= 1f) return@LaunchedEffect
        val effectiveDelayMillis = if (motionProfile == MotionProfile.Full) {
            delayMillis
        } else {
            (delayMillis / 3L).coerceAtMost(80L)
        }
        if (effectiveDelayMillis > 0L) delay(effectiveDelayMillis)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = if (motionProfile == MotionProfile.Full) {
                fullSpec
            } else {
                reducedSpec
            },
        )
    }
    return progress.value
}

@Composable
internal fun Modifier.expressiveEntrance(
    progress: Float,
    initialOffsetY: Dp = 18.dp,
    initialScale: Float = 0.94f,
): Modifier {
    val clampedProgress = progress.coerceIn(0f, 1f)
    if (clampedProgress >= 0.999f) {
        return this
    }
    val motionProfile = LocalMotionProfile.current
    val density = LocalDensity.current
    val reducedMotionFactor = if (motionProfile == MotionProfile.Full) 1f else 0.45f
    val initialOffsetPx = with(density) { (initialOffsetY * reducedMotionFactor).toPx() }
    val effectiveInitialScale = if (motionProfile == MotionProfile.Full) {
        initialScale
    } else {
        1f - ((1f - initialScale) * 0.42f)
    }
    return graphicsLayer {
        val scale = effectiveInitialScale + ((1f - effectiveInitialScale) * clampedProgress)
        alpha = clampedProgress
        scaleX = scale
        scaleY = scale
        translationY = (1f - clampedProgress) * initialOffsetPx
    }
}
