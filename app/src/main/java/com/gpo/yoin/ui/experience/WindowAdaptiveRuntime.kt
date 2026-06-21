package com.gpo.yoin.ui.experience

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.window.core.layout.WindowSizeClass

/**
 * The renderer-geometry dimension, ORTHOGONAL to [com.gpo.yoin.ui.nowplaying.NowPlayingStageMode].
 *
 * Stage mode (Compact / Expanded / Immersive) is the one-and-only interaction
 * state machine and is NEVER forked per size. [LayoutMode] only chooses WHICH
 * set of render targets a screen draws into, derived purely from window
 * size + fold posture. A handset (or a folded outer screen) is [Compact] and
 * must look/behave exactly as before this dimension existed.
 */
enum class LayoutMode {
    /** Phones, outer screens, split-screen narrow — current single-column UI. */
    Compact,

    /** Inner foldable / tablet (width >= Medium) — two-column player. */
    Wide,

    /** Horizontal-hinge half-fold (kickstand) — top/bottom split on the hinge. */
    Tabletop,
}

/**
 * Window configuration snapshot. Recomposes on fold / rotate / split-screen
 * because [rememberYoinWindowInfo] reads the observable [currentWindowAdaptiveInfo].
 *
 * @param hingeBounds the horizontal hinge rectangle in WINDOW coordinates when
 *   in [LayoutMode.Tabletop]; null otherwise. Used to split the kickstand layout.
 */
data class YoinWindowInfo(
    val layoutMode: LayoutMode,
    val isWidthAtLeastMedium: Boolean,
    val hingeBounds: Rect?,
)

/**
 * Injected once per Activity in `YoinActivityRoot` (next to [LocalMotionProfile]).
 * Default is [LayoutMode.Compact] so any composable read outside a provider — and
 * previews/tests — behaves like a handset.
 */
val LocalYoinWindowInfo = staticCompositionLocalOf {
    YoinWindowInfo(
        layoutMode = LayoutMode.Compact,
        isWidthAtLeastMedium = false,
        hingeBounds = null,
    )
}

/**
 * Derive [YoinWindowInfo] from the live window size + posture.
 *
 * Mapping (first match wins): a separating/occluding HORIZONTAL hinge with a
 * tabletop posture -> Tabletop; otherwise width >= Medium (600dp) -> Wide;
 * otherwise Compact.
 */
@Composable
fun rememberYoinWindowInfo(): YoinWindowInfo {
    val adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val widthAtLeastMedium = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    // A horizontal hinge (fold line runs left-to-right) splits top/bottom.
    val horizontalHinge = adaptiveInfo.windowPosture.hingeList.firstOrNull { hinge ->
        !hinge.isVertical
    }
    val layoutMode = when {
        adaptiveInfo.windowPosture.isTabletop && horizontalHinge != null -> LayoutMode.Tabletop
        widthAtLeastMedium -> LayoutMode.Wide
        else -> LayoutMode.Compact
    }
    return YoinWindowInfo(
        layoutMode = layoutMode,
        isWidthAtLeastMedium = widthAtLeastMedium,
        hingeBounds = horizontalHinge?.bounds,
    )
}
