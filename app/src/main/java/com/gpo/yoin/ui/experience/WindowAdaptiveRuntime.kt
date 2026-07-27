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
    /** Phones, outer screens, split-screen narrow (< 600dp) — single-column UI. */
    Compact,

    /**
     * 600–840dp：大手机横屏、小平板、分屏半窗。页面层面这一档只做密度/留白
     * 调整（限宽、多一列网格），shell↔detail 也不分栏；但 Now Playing 从这一
     * 档起就是双栏（[isDualPaneNowPlaying]，scheme §5 option A，2026-07-27）——
     * NP 侧原先的 `== Wide` / `!= Wide` 门已全部改写为按该谓词走。
     */
    Medium,

    /** >= 840dp（Expanded）：真平板 / 展开折叠屏 / 桌面窗口 — 双栏播放器。 */
    Wide,

    /** Horizontal-hinge half-fold (kickstand) — top/bottom split on the hinge. */
    Tabletop,
}

/**
 * Now Playing renders the two-column player from Medium up (scheme §5 option A,
 * 2026-07-27): [LayoutMode] is pane-relative inside embedded splits, which made
 * a true [LayoutMode.Wide] reading rare — a shell/detail pane of a tablet
 * window usually reads Medium — so the dual-pane NP experience keys off this
 * predicate, never `== Wide`. Deliberately NOT `!= Compact`: [LayoutMode.Tabletop]
 * keeps its own top/bottom hinge layout, and every NP gate must leave Tabletop
 * exactly where it sat when the gates were written against `!= Wide` (Tabletop
 * passed those, and stays passing this one's negation).
 */
val LayoutMode.isDualPaneNowPlaying: Boolean
    get() = this == LayoutMode.Medium || this == LayoutMode.Wide

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
 * tabletop posture -> Tabletop; width >= Expanded (840dp) -> Wide; width >=
 * Medium (600dp) -> Medium; otherwise Compact.
 *
 * 2026-07-26 阈值审计：分栏（Wide）的决策点从 600 挪到 840，对齐官方
 * canonical layouts —— 600–840 那档窗口做密度调整而不是硬塞两栏。
 */
@Composable
fun rememberYoinWindowInfo(): YoinWindowInfo {
    val adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val widthAtLeastMedium = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    val widthAtLeastExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    // A horizontal hinge (fold line runs left-to-right) splits top/bottom.
    val horizontalHinge = adaptiveInfo.windowPosture.hingeList.firstOrNull { hinge ->
        !hinge.isVertical
    }
    val layoutMode = when {
        adaptiveInfo.windowPosture.isTabletop && horizontalHinge != null -> LayoutMode.Tabletop
        widthAtLeastExpanded -> LayoutMode.Wide
        widthAtLeastMedium -> LayoutMode.Medium
        else -> LayoutMode.Compact
    }
    return YoinWindowInfo(
        layoutMode = layoutMode,
        isWidthAtLeastMedium = widthAtLeastMedium,
        hingeBounds = horizontalHinge?.bounds,
    )
}
