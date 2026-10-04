package com.gpo.yoin.ui.experience

import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.window.WindowSdkExtensions
import androidx.window.core.layout.WindowSizeClass
import androidx.window.embedding.ActivityEmbeddingController
import androidx.window.embedding.SplitController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The renderer-geometry dimension, ORTHOGONAL to [com.gpo.yoin.ui.nowplaying.NowPlayingStageMode].
 *
 * Stage mode (Compact / Expanded / Immersive) is the one-and-only interaction
 * state machine and is NEVER forked per size. [LayoutMode] only chooses WHICH
 * set of render targets a screen draws into, derived purely from window
 * WIDTH + fold posture. A handset (or a folded outer screen) is [Compact] and
 * must look/behave exactly as before this dimension existed.
 *
 * Height is a separate reading ([ShellChromeForm]): a landscape handset is
 * Medium/Wide by width but short, so every page checks
 * [YoinWindowInfo.isCompactHeight] BEFORE it looks at [LayoutMode]
 * (断点交接 §14.6 —— 844 宽的手机横屏不能落到桌面档).
 */
enum class LayoutMode {
    /** Phones, outer screens, split-screen narrow (< 600dp) — single-column UI. */
    Compact,

    /**
     * 600–840dp：折叠屏内屏、平板竖屏、分屏半窗。页面层面只做密度/留白调整
     * （限宽、多一列网格）；Now Playing 在这一档是「先开手机宽侧栏、再全屏成
     * 放大的手机」（Spotify 式，2026-09-30 断点交接 §3.4），不再是双栏。
     */
    Medium,

    /** >= 840dp（Expanded）：整窗或窗格本身 >= 840 —— 双栏播放器 / 桌面档。 */
    Wide,

    /** Horizontal-hinge half-fold (kickstand) — top/bottom split on the hinge. */
    Tabletop,
}

/**
 * Where the shell's Button Group lives (断点交接 §1). ONE source for the shell,
 * every detail Activity and every page — nobody re-derives it from dp.
 *
 * Height is judged FIRST: a window shorter than 480dp is a landscape handset
 * (or a short split half) whatever its width, and gets [EdgeSplit].
 */
enum class ShellChromeForm {
    /** Width < 600 and height ≥ 480, plus Tabletop: the portrait bottom bar, unchanged. */
    PortraitBar,

    /**
     * Height < 480: the group splits into two 64dp capsules on the left screen
     * edge, living in the camera-cutout band — navigation / Play above the
     * cutout, now playing below it (§2.2).
     */
    EdgeSplit,

    /** Width ≥ 600 and height ≥ 480: the portrait bar, centred and capped at 600dp (§2.3). */
    CenteredBar,
}

/** Pure mapping behind [ShellChromeForm] — see the enum. */
internal fun resolveShellChromeForm(
    isTabletop: Boolean,
    widthAtLeastMedium: Boolean,
    heightAtLeastMedium: Boolean,
): ShellChromeForm = when {
    isTabletop -> ShellChromeForm.PortraitBar
    !heightAtLeastMedium -> ShellChromeForm.EdgeSplit
    widthAtLeastMedium -> ShellChromeForm.CenteredBar
    else -> ShellChromeForm.PortraitBar
}

/**
 * Window configuration snapshot. Recomposes on fold / rotate / split-screen
 * because [rememberYoinWindowInfo] reads the observable [currentWindowAdaptiveInfo].
 *
 * @param hingeBounds the horizontal hinge rectangle in WINDOW coordinates when
 *   in [LayoutMode.Tabletop]; null otherwise. Used to split the kickstand layout.
 * @param chromeForm where the Button Group lives; see [ShellChromeForm].
 * @param feedUnits the Home feed's discrete width class N ([feedUnitsFor]),
 *   computed from the CONTAINER's resting width by [rememberYoinWindowInfo] /
 *   [forPaneWidth]; a directly-constructed instance defaults from [layoutMode]
 *   ([representativeFeedUnits]).
 * @param feedCoverColumns how many phone-sized cover columns the feed seats
 *   ([feedCoverColumnsFor]) — Jump Back In's column count; same sourcing as
 *   [feedUnits] ([representativeFeedCoverColumns] by default).
 */
@Immutable
data class YoinWindowInfo(
    val layoutMode: LayoutMode,
    val isWidthAtLeastMedium: Boolean,
    val isHeightAtLeastMedium: Boolean,
    val hingeBounds: Rect?,
    val chromeForm: ShellChromeForm = ShellChromeForm.PortraitBar,
    val feedUnits: Int = representativeFeedUnits(layoutMode),
    val feedCoverColumns: Int = representativeFeedCoverColumns(layoutMode),
) {
    /** Shorter than 480dp — a landscape handset. Pages check this before [layoutMode]. */
    val isCompactHeight: Boolean get() = chromeForm == ShellChromeForm.EdgeSplit
}

/**
 * Injected once per Activity in `YoinActivityRoot` (next to [LocalMotionProfile]).
 * Default is [LayoutMode.Compact] so any composable read outside a provider — and
 * previews/tests — behaves like a handset.
 */
val LocalYoinWindowInfo = staticCompositionLocalOf {
    YoinWindowInfo(
        layoutMode = LayoutMode.Compact,
        isWidthAtLeastMedium = false,
        isHeightAtLeastMedium = true,
        hingeBounds = null,
    )
}

/**
 * The WINDOW's info for chrome that belongs to the window — the bar
 * (adaptive principle 2) — when a narrower container re-provided
 * [LocalYoinWindowInfo] for its content (the side panel's beside-content).
 * Null = no one narrowed it; read [LocalYoinWindowInfo].
 */
val LocalWindowChromeInfo = staticCompositionLocalOf<YoinWindowInfo?> { null }

/** The window-level info the bar must follow, whatever column it is composed in. */
val windowChromeInfo: YoinWindowInfo
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalWindowChromeInfo.current ?: LocalYoinWindowInfo.current

/**
 * Space a page leaves for the Button Group (断点交接 §1): bottom for the two
 * bar forms, start (+ the right cutout at end) for [ShellChromeForm.EdgeSplit].
 * Provided per Activity by `YoinActivityRoot`; pages add it to their scrolling
 * content, never to full-bleed backgrounds.
 */
val LocalShellChromeInsets = staticCompositionLocalOf { PaddingValues(0.dp) }

/**
 * Derive [YoinWindowInfo] from the live window size + posture.
 *
 * Mapping (first match wins): a separating/occluding HORIZONTAL hinge with a
 * tabletop posture -> Tabletop; width >= Expanded (840dp) -> Wide; width >=
 * Medium (600dp) -> Medium; otherwise Compact. [ShellChromeForm] is read off
 * height first ([resolveShellChromeForm]). [YoinWindowInfo.feedUnits] comes
 * from the live window width (the same `currentWindowDpSize` source as
 * `rememberWindowWidthDp`). The instance is remembered on its discrete
 * values, so a resize that crosses no breakpoint keeps the SAME object.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberYoinWindowInfo(): YoinWindowInfo {
    val adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val windowWidth = currentWindowDpSize().width
    val widthAtLeastMedium = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    val widthAtLeastExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    val heightAtLeastMedium = adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )
    // A horizontal hinge (fold line runs left-to-right) splits top/bottom.
    val horizontalHinge = adaptiveInfo.windowPosture.hingeList.firstOrNull { hinge ->
        !hinge.isVertical
    }
    val isTabletop = adaptiveInfo.windowPosture.isTabletop && horizontalHinge != null
    val layoutMode = when {
        isTabletop -> LayoutMode.Tabletop
        widthAtLeastExpanded -> LayoutMode.Wide
        widthAtLeastMedium -> LayoutMode.Medium
        else -> LayoutMode.Compact
    }
    val chromeForm = resolveShellChromeForm(
        isTabletop = isTabletop,
        widthAtLeastMedium = widthAtLeastMedium,
        heightAtLeastMedium = heightAtLeastMedium,
    )
    val feedUnits = feedUnitsForContainer(windowWidth, layoutMode, chromeForm)
    val feedCoverColumns = feedCoverColumnsForContainer(windowWidth, layoutMode, chromeForm)
    val hingeBounds = horizontalHinge?.bounds
    return remember(
        layoutMode,
        widthAtLeastMedium,
        heightAtLeastMedium,
        hingeBounds,
        chromeForm,
        feedUnits,
        feedCoverColumns,
    ) {
        YoinWindowInfo(
            layoutMode = layoutMode,
            isWidthAtLeastMedium = widthAtLeastMedium,
            isHeightAtLeastMedium = heightAtLeastMedium,
            hingeBounds = hingeBounds,
            chromeForm = chromeForm,
            feedUnits = feedUnits,
            feedCoverColumns = feedCoverColumns,
        )
    }
}

/**
 * Whether a shell → detail launch can hand the Button Group across windows
 * (DetailLaunchMode.FullChoreography): both windows must draw the SAME group
 * geometry — the portrait bar on a Compact (non-Tabletop) window, or the
 * edge-split capsules. The centred bar's detail pose re-lays the bar (extras
 * + a fixed pill), so it launches as a plain push.
 */
val YoinWindowInfo.hasChromeHandoff: Boolean
    get() = when (chromeForm) {
        ShellChromeForm.EdgeSplit -> true
        ShellChromeForm.PortraitBar -> layoutMode == LayoutMode.Compact
        ShellChromeForm.CenteredBar -> false
    }

/**
 * The window info a COLUMN of this window reads (adaptive principle 1 / 5:
 * content reads its own container, and any container that gives width away
 * — the Now Playing side panel, the detail pane — re-provides this for the
 * subtree it narrows). Pure: the same breakpoints as [rememberYoinWindowInfo]
 * applied to [widthDp]; height, hinge and the height-first chrome judgement
 * are the window's. A Tabletop window keeps its posture whatever the column.
 * [YoinWindowInfo.feedUnits] is re-read from [widthDp] with the column's own
 * mode and chrome form (a Tabletop column stays in the 1–4 range).
 */
fun YoinWindowInfo.forPaneWidth(widthDp: Dp): YoinWindowInfo {
    // Column widths are float sums (region − (pane + gutter)); an ulp must not
    // drop a 600dp column a tier. 0.01dp is far below a pixel.
    val width = widthDp.value + 0.01f
    val widthAtLeastMedium = width >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
    val widthAtLeastExpanded = width >= WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    val isTabletop = layoutMode == LayoutMode.Tabletop
    val columnMode = when {
        isTabletop -> LayoutMode.Tabletop
        widthAtLeastExpanded -> LayoutMode.Wide
        widthAtLeastMedium -> LayoutMode.Medium
        else -> LayoutMode.Compact
    }
    val columnChromeForm = resolveShellChromeForm(
        isTabletop = isTabletop,
        widthAtLeastMedium = widthAtLeastMedium,
        heightAtLeastMedium = isHeightAtLeastMedium,
    )
    return copy(
        layoutMode = columnMode,
        isWidthAtLeastMedium = widthAtLeastMedium,
        chromeForm = columnChromeForm,
        // feedUnitsFor carries the same 0.01dp guard, so the raw width goes in.
        feedUnits = feedUnitsForContainer(widthDp, columnMode, columnChromeForm),
        feedCoverColumns = feedCoverColumnsForContainer(widthDp, columnMode, columnChromeForm),
    )
}

/**
 * Whether this window lays its detail pages out as a second COLUMN beside the
 * shell (adaptive principle 3: a split is columns of one window, never a
 * second window). Wide and tall — the same line the two-column player uses;
 * a Wide-but-short window (a landscape handset at 844dp) keeps pushing pages.
 */
val YoinWindowInfo.hasDetailPane: Boolean
    get() = layoutMode == LayoutMode.Wide && isHeightAtLeastMedium

/**
 * Whether content sizes its cells to the column instead of the phone's fixed
 * dp (Home's Jump Back In covers). Height first (principle 1): a landscape
 * handset reads Wide by width but keeps the phone grid. Then the feed's width
 * class, not the mode: N ≥ 3 ([feedUnits]) — Medium, Tabletop and Wide, and
 * a Compact container from 480dp (phone composition stops at N = 2).
 */
val YoinWindowInfo.contentFollowsColumns: Boolean
    get() = !isCompactHeight && feedUnits >= 3

// ---------------------------------------------------------------------------
// Edge-split geometry (ShellChromeForm.EdgeSplit, 断点交接 §2.2)
// ---------------------------------------------------------------------------

/** Inset of both capsules from their edge (the cutout's), inside the cutout band. */
val EdgeSplitGroupInset = 8.dp

/** Capsule width: 6 padding + 52 button + 6 padding. */
val EdgeSplitGroupWidth = 64.dp

/** Air between the capsules and the page content. */
val EdgeSplitContentGap = 12.dp

/** Content inset on the capsules' edge: 8 + 64 + 12 = 84. */
val EdgeSplitContentStart = EdgeSplitGroupInset + EdgeSplitGroupWidth + EdgeSplitContentGap

private val EdgeSplitTopMargin = 14.dp
private val EdgeSplitBottomMargin = 16.dp
private val EdgeSplitCutoutGap = 8.dp

/** Stand-in cutout (centred, 36dp tall) when the left edge has none — keeps the lower capsule put. */
private val EdgeSplitNominalCutoutHeight = 36.dp

/**
 * The two capsules' vertical extents, in window dp.
 *
 * @param hasLeftCutout the upper capsule stops 8dp above a real cutout; with
 *   none (or a corner cutout, which is simply kept clear of) it grows down to
 *   8dp above the lower capsule and gains a slot ([roomy]) — Shuffle in
 *   detail form.
 */
@Immutable
data class EdgeSplitSegments(
    val upperTop: Dp,
    val upperBottom: Dp,
    val lowerTop: Dp,
    val lowerBottom: Dp,
    val hasLeftCutout: Boolean,
) {
    val upperHeight: Dp get() = upperBottom - upperTop
    val lowerHeight: Dp get() = lowerBottom - lowerTop

    /** Upper capsule has the extra slot of a closed gap. */
    val roomy: Boolean get() = !hasLeftCutout
}

/**
 * Cut the left edge into the two capsules around the cutout (pure, unit-tested).
 *
 * Upper = [topInset] (at least 14) → cutout.top − 8; lower = cutout.bottom + 8 →
 * window − 16 − [bottomInset]. Without a left cutout the lower capsule keeps the
 * position a centred 36dp cutout would give it and the upper one closes the gap.
 */
fun computeEdgeSplitSegments(
    windowHeight: Dp,
    topInset: Dp,
    bottomInset: Dp,
    leftCutoutTop: Dp?,
    leftCutoutBottom: Dp?,
): EdgeSplitSegments {
    var top = maxOf(EdgeSplitTopMargin, topInset + 4.dp)
    var bottom = windowHeight - EdgeSplitBottomMargin - bottomInset
    if (leftCutoutTop != null && leftCutoutBottom != null) {
        val upperBottom = leftCutoutTop - EdgeSplitCutoutGap
        val lowerTop = leftCutoutBottom + EdgeSplitCutoutGap
        if (upperBottom - top >= EdgeSplitMinCapsule && bottom - lowerTop >= EdgeSplitMinCapsule) {
            return EdgeSplitSegments(
                upperTop = top,
                upperBottom = upperBottom,
                lowerTop = lowerTop,
                lowerBottom = bottom,
                hasLeftCutout = true,
            )
        }
        // A corner cutout (top or bottom of this edge) leaves no room for one
        // capsule on its side: keep clear of it and split what is left as if
        // the edge had no cutout.
        val middle = (top + bottom) / 2
        if (leftCutoutTop > middle) {
            bottom = minOf(bottom, upperBottom)
        } else {
            top = maxOf(top, lowerTop)
        }
    }
    val lowerTop = (top + bottom) / 2 + EdgeSplitNominalCutoutHeight / 2 + EdgeSplitCutoutGap
    return EdgeSplitSegments(
        upperTop = top,
        upperBottom = lowerTop - EdgeSplitCutoutGap,
        lowerTop = lowerTop,
        lowerBottom = bottom,
        hasLeftCutout = false,
    )
}

/** Below this a capsule can't hold its buttons; the cutout is treated as a corner. */
private val EdgeSplitMinCapsule = 128.dp

/**
 * Where the cutout sits right now, from the insets — never from
 * `Display.rotation`: a 90° ↔ 270° flip keeps size and orientation (no
 * configuration change) and only the insets move (§14.8). Reading the Compose
 * [WindowInsets.displayCutout] values subscribes this to every inset change;
 * the bounding rects are re-read whenever they move.
 */
@Immutable
data class EdgeCutouts(
    /** Left-edge cutout extent in window dp, or null. */
    val leftTop: Dp?,
    val leftBottom: Dp?,
    /** Right cutout inset (content must not draw into it). */
    val rightInset: Dp,
    /** Right-edge cutout extent in window dp, or null (the phone turned the other way). */
    val rightTop: Dp? = null,
    val rightBottom: Dp? = null,
    /** Left cutout inset. */
    val leftInset: Dp = 0.dp,
) {
    /** The edge the capsules live on: the cutout's (adaptive principle: chrome sits in the band). */
    val side: EdgeSplitSide
        get() = resolveEdgeSplitSide(
            hasLeftCutout = leftTop != null || leftInset > 0.dp,
            hasRightCutout = rightTop != null || rightInset > 0.dp,
        )

    /** The cutout extent on [side]'s edge, or nulls. */
    val sideTop: Dp? get() = if (side == EdgeSplitSide.Right) rightTop else leftTop
    val sideBottom: Dp? get() = if (side == EdgeSplitSide.Right) rightBottom else leftBottom
}

/**
 * Which edge of a short window holds the Button Group's capsules: the edge
 * with the camera cutout, so the chrome sits in the band content can't use
 * (断点交接 §2.2). A phone turned the other way moves its cutout — and the
 * capsules, the now-playing pill with them — to the right. No cutout (or one
 * on both edges): the left, as designed.
 */
enum class EdgeSplitSide { Left, Right }

fun resolveEdgeSplitSide(hasLeftCutout: Boolean, hasRightCutout: Boolean): EdgeSplitSide =
    if (hasRightCutout && !hasLeftCutout) EdgeSplitSide.Right else EdgeSplitSide.Left

@Composable
fun rememberEdgeCutouts(): EdgeCutouts {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout
    val left = cutoutInsets.getLeft(density, layoutDirection)
    val right = cutoutInsets.getRight(density, layoutDirection)
    val top = cutoutInsets.getTop(density)
    val bottom = cutoutInsets.getBottom(density)
    return remember(view, density, left, right, top, bottom) {
        val rects = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
        val windowWidth = view.rootView.width.takeIf { it > 0 } ?: view.width
        // The band on the LEFT edge: touches x≈0 and stays in the left half.
        val leftRect = if (left > 0) {
            rects.firstOrNull { it.left <= 1 && (windowWidth == 0 || it.right < windowWidth / 2) }
        } else {
            null
        }
        // …and on the RIGHT edge (the phone turned the other way).
        val rightRect = if (right > 0 && windowWidth > 0) {
            rects.firstOrNull { it.right >= windowWidth - 1 && it.left > windowWidth / 2 }
        } else {
            null
        }
        with(density) {
            EdgeCutouts(
                leftTop = leftRect?.top?.toDp(),
                leftBottom = leftRect?.bottom?.toDp(),
                rightInset = right.toDp(),
                rightTop = rightRect?.top?.toDp(),
                rightBottom = rightRect?.bottom?.toDp(),
                leftInset = left.toDp(),
            )
        }
    }
}

/**
 * The window's TOP camera cutout (a bounding rect that touches the top edge
 * and is short), in window px; null when there is none. Re-read when the
 * cutout insets move (rotation, cutout mode). Home's Memories arrow hangs
 * just below it.
 */
@Composable
fun rememberTopCutoutBounds(): Rect? {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout
    val left = cutoutInsets.getLeft(density, layoutDirection)
    val right = cutoutInsets.getRight(density, layoutDirection)
    val top = cutoutInsets.getTop(density)
    return remember(view, left, right, top) {
        val windowHeight = view.rootView.height.takeIf { it > 0 } ?: view.height
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
            .firstOrNull { rect ->
                rect.top <= 0 && rect.height() > 0 && (windowHeight == 0 || rect.height() < windowHeight / 4)
            }
            ?.let { rect -> Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()) }
    }
}

/** The edge the capsules live on in this window, from the live cutout (see [EdgeSplitSide]). */
@Composable
fun rememberEdgeSplitSide(): EdgeSplitSide = rememberEdgeCutouts().side

/** [computeEdgeSplitSegments] for the live window, around the cutout on the group's edge. */
@Composable
fun rememberEdgeSplitSegments(windowHeight: Dp): EdgeSplitSegments {
    val density = LocalDensity.current
    val cutouts = rememberEdgeCutouts()
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return remember(windowHeight, statusTop, navBottom, cutouts) {
        computeEdgeSplitSegments(
            windowHeight = windowHeight,
            topInset = statusTop,
            bottomInset = navBottom,
            leftCutoutTop = cutouts.sideTop,
            leftCutoutBottom = cutouts.sideBottom,
        )
    }
}

/**
 * Nav-bar inset on the group's own edge (3-button nav on that side) — the
 * group steps inward by it.
 */
@Composable
fun rememberEdgeSplitEdgeShift(side: EdgeSplitSide): Dp {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return with(density) {
        if (side == EdgeSplitSide.Right) {
            WindowInsets.navigationBars.getRight(this, layoutDirection).toDp()
        } else {
            WindowInsets.navigationBars.getLeft(this, layoutDirection).toDp()
        }
    }
}

/**
 * [LocalShellChromeInsets] for a window: bar forms reserve the bar's height at
 * the bottom; [ShellChromeForm.EdgeSplit] reserves the 84dp band on the
 * capsules' edge (the cutout's, [EdgeSplitSide]) and the nav bar / any cutout
 * on the other. Physical (Absolute) sides: the cutout is physical.
 */
@Composable
fun rememberShellChromeInsets(windowInfo: YoinWindowInfo): PaddingValues {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return when (windowInfo.chromeForm) {
        ShellChromeForm.EdgeSplit -> {
            val cutouts = rememberEdgeCutouts()
            val navLeft = with(density) { WindowInsets.navigationBars.getLeft(this, layoutDirection).toDp() }
            val navRight = with(density) { WindowInsets.navigationBars.getRight(this, layoutDirection).toDp() }
            remember(navLeft, navRight, navBottom, cutouts) {
                if (cutouts.side == EdgeSplitSide.Right) {
                    PaddingValues.Absolute(
                        left = maxOf(cutouts.leftInset, navLeft),
                        right = EdgeSplitContentStart + navRight,
                        bottom = navBottom,
                    )
                } else {
                    PaddingValues.Absolute(
                        left = EdgeSplitContentStart + navLeft,
                        right = maxOf(cutouts.rightInset, navRight),
                        bottom = navBottom,
                    )
                }
            }
        }

        ShellChromeForm.CenteredBar -> {
            val margin =
                if (windowInfo.layoutMode == LayoutMode.Wide) CenteredBarBottomMarginWide else CenteredBarBottomMargin
            remember(navBottom, margin) {
                PaddingValues(bottom = CenteredBarHeight + margin + FloatingBarContentGap + navBottom)
            }
        }

        ShellChromeForm.PortraitBar -> remember(navBottom) {
            PaddingValues(bottom = FloatingBarHeight + PortraitBarVerticalMargin * 2 + navBottom)
        }
    }
}

/** Outer height of the portrait bar's pill surface (row 68 = 48 button + 10 × 2). */
val FloatingBarHeight = 68.dp

/**
 * The centred bar (Medium / Wide, and the merged pose) is a step lower: 60 =
 * 44 button + 8 × 2. Tablets have the height to spare, but the full-size pill
 * read as a slab there (owner, 2026-10-02); the portrait bar is unchanged.
 */
val CenteredBarHeight = 60.dp

/** The centred bar's button height (its row minus 8dp above and below). */
val CenteredBarButtonHeight = 44.dp

/** The portrait bar's vertical margin (top and bottom). */
val PortraitBarVerticalMargin = 12.dp

/** CenteredBar: gap from the window bottom (above the nav bar). */
val CenteredBarBottomMargin = 24.dp
val CenteredBarBottomMarginWide = 28.dp

/** CenteredBar: horizontal margin inside a narrow pane (bar = pane − 40, max 600). */
val CenteredBarHorizontalMargin = 20.dp

/** CenteredBar: width cap. */
val CenteredBarMaxWidth = 600.dp

/**
 * The merged pose's cap (Wide shell with its detail column open): the one bar
 * spans both columns, so it may grow to the feed column's width — nav on the
 * shell's side, the page's Play on the detail's side, the pill bridging them.
 */
val MergedBarMaxWidth = 720.dp

/** Air between the last content row and the bar. */
private val FloatingBarContentGap = 12.dp

// ---------------------------------------------------------------------------
// Activity Embedding — Settings list-detail only (一律订阅，不在创建时算一次)
// ---------------------------------------------------------------------------

/**
 * Whether this Activity currently sits in an Activity Embedding split pane.
 * Subscribes — MainActivity handles its own configChanges and never recreates
 * on rotation, so a one-shot `isActivityEmbedded()` would go stale (§14.5).
 * Extension ≥ 6: [ActivityEmbeddingController.embeddedActivityWindowInfo];
 * older: [SplitController.splitInfoList].
 */
@Composable
fun rememberIsActivityEmbedded(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    // Configuration is read so a resize re-seeds the synchronous initial value.
    val configuration = LocalConfiguration.current
    val initial = remember(activity, configuration) {
        activity?.let {
            runCatching {
                ActivityEmbeddingController.getInstance(it).isActivityEmbedded(it)
            }.getOrDefault(false)
        } ?: false
    }
    val flow = remember(activity) { activity?.let(::embeddedFlow) ?: flowOf(false) }
    val embedded by flow.collectAsState(initial = initial)
    return embedded
}

// Lint does not read WindowSdkExtensions checks as guards; the extension-6
// call below sits behind one.
@SuppressLint("RequiresWindowSdk")
private fun embeddedFlow(activity: Activity): Flow<Boolean> =
    runCatching {
        if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
            ActivityEmbeddingController.getInstance(activity)
                .embeddedActivityWindowInfo(activity)
                .map { it.isEmbedded }
        } else {
            SplitController.getInstance(activity)
                .splitInfoList(activity)
                .map { splits -> splits.any { it.contains(activity) } }
        }.distinctUntilChanged()
    }.getOrElse { flowOf(false) }

private tailrec fun android.content.Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
