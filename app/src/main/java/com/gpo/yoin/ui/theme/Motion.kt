package com.gpo.yoin.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * Spring animation specifications following MD3 Expressive Motion Physics.
 *
 * - SpatialSpring: for position, size, and layout changes (page transitions, shared elements)
 * - EffectsSpring: for color, opacity, and visual property changes (theme transitions, fades)
 * - StiffSpatialSpring: for quick micro-interactions (icon press, toggle feedback)
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

    /** Stiff spatial spring — quick micro-interactions (icon press, toggle feedback). */
    fun <T> stiffSpatialSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    // ── Navigation transition helpers ──────────────────────────────────

    /** Slide-in from right + fade-in — forward navigation enter. */
    val navEnterForward: EnterTransition =
        slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } +
            fadeIn(spring(stiffness = Spring.StiffnessLow))

    /** Slide-out to left + fade-out — forward navigation exit. */
    val navExitForward: ExitTransition =
        slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { -it / 3 } +
            fadeOut(spring(stiffness = Spring.StiffnessLow))

    /** Slide-in from left + fade-in — back navigation enter (pop). */
    val navEnterBack: EnterTransition =
        slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { -it / 3 } +
            fadeIn(spring(stiffness = Spring.StiffnessLow))

    /** Slide-out to right + fade-out — back navigation exit (pop). */
    val navExitBack: ExitTransition =
        slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } +
            fadeOut(spring(stiffness = Spring.StiffnessLow))

    /** Slide-up + fade-in — fullscreen overlay enter (e.g., Now Playing). */
    val navEnterOverlay: EnterTransition =
        slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } +
            fadeIn(spring(stiffness = Spring.StiffnessLow))

    /** Slide-down + fade-out — fullscreen overlay exit (e.g., dismiss Now Playing). */
    val navExitOverlay: ExitTransition =
        slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } +
            fadeOut(spring(stiffness = Spring.StiffnessLow))
}
