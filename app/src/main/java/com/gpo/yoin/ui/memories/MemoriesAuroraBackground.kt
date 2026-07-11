package com.gpo.yoin.ui.memories

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Ambient moving-gradient wash for the Memories deck: three soft radial
 * blooms in the CURRENT memory's palette drift slowly across the page, each
 * on its own orbit speed (the parallax gives the field a layered, 3D-ish
 * depth), with the hue sliding between the base and accent tones so the
 * colour itself is visibly in motion. Swiping to another memory re-tints the
 * whole atmosphere through the palette's own 380ms hand-off.
 *
 * Performance follows the Now Playing aurora's discipline: the drift/breath
 * loops run ONLY while [visible] (the deck is on screen), all animated values
 * are read inside [drawBehind] so motion invalidates the draw phase only, and
 * hiding the deck cancels the loops entirely.
 */
@Composable
internal fun Modifier.memoriesAuroraBackground(
    baseColor: Color,
    accentColor: Color,
    visible: Boolean,
): Modifier {
    val flowPhase = remember { Animatable(0f) }
    val breathPhase = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        launch {
            flowPhase.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 22000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
        launch {
            breathPhase.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 7000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        }
    }
    val activeFraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "memoriesAuroraActive",
    )
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    return drawBehind {
        val frac = activeFraction
        if (frac < 0.01f) return@drawBehind
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind

        val maxDim = max(w, h)
        val blend = if (isDark) BlendMode.Screen else BlendMode.SrcOver
        val alphaCap = if (isDark) MEMORIES_AURORA_ALPHA_DARK else MEMORIES_AURORA_ALPHA_LIGHT
        val flow = flowPhase.value
        val breath = breathPhase.value

        val colors = listOf(baseColor, accentColor, lerp(baseColor, accentColor, 0.5f))
        val anchors = listOf(
            Offset(w * 0.24f, h * 0.22f),
            Offset(w * 0.78f, h * 0.44f),
            Offset(w * 0.34f, h * 0.80f),
        )
        colors.forEachIndexed { i, color ->
            val anchor = anchors[i]
            val angle = TWO_PI * flow * (0.6f + 0.4f * i) + i * TWO_PI / 3f
            val center = Offset(
                x = anchor.x + cos(angle) * w * 0.20f,
                y = anchor.y + sin(angle * 1.3f) * h * 0.16f,
            )
            val wobble = sin(TWO_PI * breath + i.toFloat())
            val depth = 1f - i * 0.18f
            val cycled = lerp(
                color,
                colors[(i + 1) % colors.size],
                0.5f + 0.5f * sin(TWO_PI * flow + i.toFloat()),
            )
            val radius = maxDim * (0.5f + 0.12f * wobble) * depth
            val coreAlpha = alphaCap * frac * (0.8f + 0.2f * wobble) * (0.72f + 0.28f * depth)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cycled.copy(alpha = coreAlpha), cycled.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
                blendMode = blend,
            )
        }
    }
}

private const val MEMORIES_AURORA_ALPHA_DARK = 0.26f
private const val MEMORIES_AURORA_ALPHA_LIGHT = 0.2f
private val TWO_PI = (2.0 * PI).toFloat()
