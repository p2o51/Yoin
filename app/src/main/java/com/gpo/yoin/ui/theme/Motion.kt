package com.gpo.yoin.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Spring animation specifications following MD3 Expressive Motion Physics.
 *
 * - SpatialSpring: for position, size, and layout changes (page transitions, shared elements)
 * - EffectsSpring: for color, opacity, and visual property changes (theme transitions, fades)
 */
object YoinMotion {

    /** Spatial Spring — position and size changes. */
    fun <T> spatialSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Effects Spring — color and opacity changes. */
    fun <T> effectsSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Bouncy spatial spring — for playful interactions like button presses. */
    fun <T> bouncySpatialSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
