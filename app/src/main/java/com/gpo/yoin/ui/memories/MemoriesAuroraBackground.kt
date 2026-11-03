package com.gpo.yoin.ui.memories

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Gradient wash for the Memories deck: three soft radial blooms in the
 * CURRENT memory's palette, each on its own orbit (the parallax gives the
 * field a layered, 3D-ish depth), with the hue sliding between the base and
 * accent tones.
 *
 * The blooms move with the finger, not the clock (gradient lab, owner
 * 2026-11-03): their orbit is driven by [drift] — the deck's pager position —
 * so swiping between memories turns the field and re-tints it, and a deck at
 * rest draws nothing new. The old 22 s / 7 s loops ran every frame for as
 * long as the deck was open. [drift] is read in [drawBehind] only.
 */
@Composable
internal fun Modifier.memoriesAuroraBackground(
    baseColor: Color,
    accentColor: Color,
    visible: Boolean,
): Modifier = memoriesAuroraBackground(colors = { baseColor to accentColor }, visible = visible, drift = { 0f })

/**
 * The same wash with its two colours and its [drift] read in the draw phase: the deck lerps them with the
 * pager's position every frame of a swipe, and only the draw is invalidated (no recomposition per frame).
 */
@Composable
internal fun Modifier.memoriesAuroraBackground(
    colors: () -> Pair<Color, Color>,
    visible: Boolean,
    drift: () -> Float,
): Modifier {
    val activeFraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        // Slow effects spring: a long, soft ambient envelope that still
        // obeys the effects-spring law for alpha changes.
        animationSpec = YoinMotion.slowEffectsSpec(role = YoinMotionRole.Expressive),
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
        // One memory turns the orbit a little under a fifth; the breath follows at its own pace.
        val position = drift()
        val flow = position * DRIFT_PER_PAGE
        val breath = 0.5f + 0.5f * sin(position * BREATH_PER_PAGE)

        val (baseColor, accentColor) = colors()
        val blooms = listOf(baseColor, accentColor, lerp(baseColor, accentColor, 0.5f))
        val anchors = listOf(
            Offset(w * 0.24f, h * 0.22f),
            Offset(w * 0.78f, h * 0.44f),
            Offset(w * 0.34f, h * 0.80f),
        )
        blooms.forEachIndexed { i, color ->
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
                blooms[(i + 1) % blooms.size],
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
private const val DRIFT_PER_PAGE = 0.18f
private const val BREATH_PER_PAGE = 1.9f
private val TWO_PI = (2.0 * PI).toFloat()
