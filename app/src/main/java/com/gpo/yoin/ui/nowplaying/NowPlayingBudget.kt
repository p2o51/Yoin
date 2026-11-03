package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.tokens.YoinTokens
import com.gpo.yoin.shared.nowplaying.DualPaneBudget as SharedDualPaneBudget
import com.gpo.yoin.shared.nowplaying.DualPaneMetrics as SharedDualPaneMetrics
import com.gpo.yoin.shared.nowplaying.DualPanePose as SharedDualPanePose
import com.gpo.yoin.shared.nowplaying.DualPaneTitleForm as SharedDualPaneTitleForm
import com.gpo.yoin.shared.nowplaying.EnlargedSpec as SharedEnlargedSpec
import com.gpo.yoin.shared.nowplaying.LyricFrame as SharedLyricFrame
import com.gpo.yoin.shared.nowplaying.LyricWindowMetrics as SharedLyricWindowMetrics
import com.gpo.yoin.shared.nowplaying.NpGap as SharedNpGap
import com.gpo.yoin.shared.nowplaying.StageColumnMetrics as SharedStageColumnMetrics
import com.gpo.yoin.shared.nowplaying.StageColumnPose as SharedStageColumnPose
import com.gpo.yoin.shared.nowplaying.coverFocusOffered as sharedCoverFocusOffered
import com.gpo.yoin.shared.nowplaying.resolveDualPaneBudget as sharedResolveDualPaneBudget
import com.gpo.yoin.shared.nowplaying.resolveLandscapeCover as sharedResolveLandscapeCover
import com.gpo.yoin.shared.nowplaying.resolveStageColumnBudget as sharedResolveStageColumnBudget
import com.gpo.yoin.shared.nowplaying.squeezeGaps as sharedSqueezeGaps
import com.gpo.yoin.shared.nowplaying.stageColumnBottomInset as sharedStageColumnBottomInset

/*
 * Now Playing's height budget (adaptive-principles §6): every fixed-height
 * Now Playing body resolves its slots from measured content minimums plus
 * compressible gaps — never from hand-summed reserves in the layout.
 *
 * The ladder itself lives in :shared (com.gpo.yoin.shared.nowplaying, the
 * same code the iOS app runs; its doc has the rules). This file is the Dp
 * face Compose reads: the same types as before, converted at the boundary.
 * The shared code does Dp's exact Float arithmetic, so every slot is
 * bit-identical to the Dp code it replaced (NowPlayingBudgetParityTest).
 *
 * Pure Dp math, JVM-tested (NowPlayingBudgetTest).
 */

/** A gap with a resting [nominal] and the [min] it compresses to. */
@Immutable
data class NpGap(val nominal: Dp, val min: Dp) {
    val slack: Dp get() = shared().slack.dp

    /** 0 = nominal, 1 = fully compressed. */
    fun at(squeeze: Float): Dp = shared().at(squeeze).dp

    internal fun shared() = SharedNpGap(nominal.value, min.value)
}

/**
 * The lyric window's own geometry (LyricsDisplay): the active line sits at
 * [LyricsAnchorFraction] of the viewport, below a [LyricsListPadding] inset,
 * and the bottom [LyricsFadeBottom] dissolves. "N clean lines" = the active
 * line plus N − 1 following lines, none of them inside the bottom fade.
 */
@Immutable
data class LyricWindowMetrics(val activeLine: Dp, val inactiveLine: Dp) {
    fun cleanLines(n: Int): Dp = SharedLyricWindowMetrics(activeLine.value, inactiveLine.value).cleanLines(n).dp
}

/** What the lyric slot shows. */
enum class LyricFrame {
    /** The scrolling lyric window. */
    List,

    /** 16:9 rest: one tappable current line; the tabs fold away. */
    OneLine,

    /** Focus: the current line under the grown cover (never dropped). */
    Preview,
}

/** Content minimums of the single column, measured at the current font scale. */
@Immutable
data class StageColumnMetrics(
    /** Text-tab row text height (labelLarge line). */
    val tabText: Dp,
    /** The two transport rows (+ the time-label row when it drops below the wave). */
    val transport: Dp,
    /** Artist + 2 + title lines. */
    val hero: Dp,
    /** Title and artist on one row: the title's line. */
    val heroOneLine: Dp,
    /** The pill row (44, 48 with the Cast button). */
    val pills: Dp,
    /** The one-line lyric's text box (bold bodyLarge line + its 4dp pads). */
    val oneLineContent: Dp,
    val lyric: LyricWindowMetrics,
)

/** Resting / focus geometry of the single column. Every slot is exact: they sum to the height. */
@Immutable
data class StageColumnPose(
    val topBar: Dp,
    val cover: Dp,
    val coverGap: Dp,
    val tabs: Dp,
    val tabGap: Dp,
    val lyricSlot: Dp,
    val lyricFrame: LyricFrame,
    val controls: Dp,
    val hero: Dp,
    val accessory: Dp,
    /** Lower / upper gap compression of this pose, 0..1 (QA + tests). */
    val lower: Float,
    val upper: Float,
) {
    val total: Dp get() = topBar + cover + coverGap + tabs + tabGap + lyricSlot + controls + hero + accessory
}

@Immutable
data class StageColumnBudget(
    /** The column's width (the enlarged stage narrows with a height-bound cover). */
    val stageWidth: Dp,
    val rest: StageColumnPose,
    /** Null when the cover can't grow enough to be worth a tap (it is a plain image). */
    val focus: StageColumnPose?,
    /** Title and artist share one row (short windows); fixed for rest and focus alike. */
    val heroOneLine: Boolean = false,
) {
    val focusOffered: Boolean get() = focus != null
}

/**
 * The single column: Phone, Panel and (with [enlarged]) the enlarged phone.
 * [width] / [height] are the content box (system bars already removed).
 * [toolTabs] = the enlarged phone's 44dp tab row carrying the lyric tools.
 * [previous] keeps the lyric frame from flapping at the gate.
 */
fun resolveStageColumnBudget(
    width: Dp,
    height: Dp,
    metrics: StageColumnMetrics,
    enlarged: NowPlayingEnlargedSpec? = null,
    previous: LyricFrame? = null,
): StageColumnBudget {
    val budget = sharedResolveStageColumnBudget(
        width = width.value,
        height = height.value,
        metrics = SharedStageColumnMetrics(
            tabText = metrics.tabText.value,
            transport = metrics.transport.value,
            hero = metrics.hero.value,
            heroOneLine = metrics.heroOneLine.value,
            pills = metrics.pills.value,
            oneLineContent = metrics.oneLineContent.value,
            lyric = SharedLyricWindowMetrics(metrics.lyric.activeLine.value, metrics.lyric.inactiveLine.value),
        ),
        enlarged = enlarged?.let { SharedEnlargedSpec(it.columnWidth.value, it.ratingColumn.value, it.controlSize.value) },
        previous = previous?.let { SharedLyricFrame.valueOf(it.name) },
    )
    return StageColumnBudget(
        stageWidth = budget.stageWidth.dp,
        rest = budget.rest.toDp(),
        focus = budget.focus?.toDp(),
        heroOneLine = budget.heroOneLine,
    )
}

private fun SharedStageColumnPose.toDp() = StageColumnPose(
    topBar = topBar.dp,
    cover = cover.dp,
    coverGap = coverGap.dp,
    tabs = tabs.dp,
    tabGap = tabGap.dp,
    lyricSlot = lyricSlot.dp,
    lyricFrame = LyricFrame.valueOf(lyricFrame.name),
    controls = controls.dp,
    hero = hero.dp,
    accessory = accessory.dp,
    lower = lower,
    upper = upper,
)

// ── DualPane ────────────────────────────────────────────────────────────

/** How the DualPane identity block is set. Decided per window, never per song. */
enum class DualPaneTitleForm { TwoLines, OneLine, TopBar }

/** Content minimums of the DualPane left stack (measured at [DualPaneStackWidth]). */
@Immutable
data class DualPaneMetrics(
    val transport: Dp,
    /** Artist line + 2 + one / two title lines. */
    val titleOneLine: Dp,
    val titleTwoLines: Dp,
    val pills: Dp,
    /** Bottom system inset the pills clear. */
    val navBottom: Dp,
)

@Immutable
data class DualPanePose(
    val slot: Dp,
    val cover: Dp,
    val coverToRating: Dp,
    val ratingToTransport: Dp,
    val transportToTitle: Dp,
    /** 0 when the rating / title retreat (focus) or the title folded into the top bar. */
    val rating: Dp,
    val title: Dp,
)

@Immutable
data class DualPaneBudget(
    /** Outer frame: top padding, the gap under the top bar, bottom padding. */
    val frameTop: Dp,
    val barGap: Dp,
    val frameBottom: Dp,
    val titleForm: DualPaneTitleForm,
    val rest: DualPanePose,
    val focus: DualPanePose?,
) {
    val focusOffered: Boolean get() = focus != null
}

/**
 * The two-column player's left stack: it is ALWAYS measured at
 * [DualPaneStackWidth] — the cover never decides the controls' width (the
 * 170dp stack that dropped Next and Write). [contentHeight] is the window
 * minus the top system inset; [barHeight] the top bar; [rowWidth] the width
 * both columns share.
 */
fun resolveDualPaneBudget(
    rowWidth: Dp,
    contentHeight: Dp,
    barHeight: Dp,
    metrics: DualPaneMetrics,
): DualPaneBudget {
    val budget: SharedDualPaneBudget = sharedResolveDualPaneBudget(
        rowWidth = rowWidth.value,
        contentHeight = contentHeight.value,
        barHeight = barHeight.value,
        metrics = SharedDualPaneMetrics(
            transport = metrics.transport.value,
            titleOneLine = metrics.titleOneLine.value,
            titleTwoLines = metrics.titleTwoLines.value,
            pills = metrics.pills.value,
            navBottom = metrics.navBottom.value,
        ),
    )
    return DualPaneBudget(
        frameTop = budget.frameTop.dp,
        barGap = budget.barGap.dp,
        frameBottom = budget.frameBottom.dp,
        titleForm = when (budget.titleForm) {
            SharedDualPaneTitleForm.TwoLines -> DualPaneTitleForm.TwoLines
            SharedDualPaneTitleForm.OneLine -> DualPaneTitleForm.OneLine
            SharedDualPaneTitleForm.TopBar -> DualPaneTitleForm.TopBar
        },
        rest = budget.rest.toDp(),
        focus = budget.focus?.toDp(),
    )
}

private fun SharedDualPanePose.toDp() = DualPanePose(
    slot = slot.dp,
    cover = cover.dp,
    coverToRating = coverToRating.dp,
    ratingToTransport = ratingToTransport.dp,
    transportToTitle = transportToTitle.dp,
    rating = rating.dp,
    title = title.dp,
)

// ── Landscape ───────────────────────────────────────────────────────────

/** The landscape handset's cover side at rest and in focus (null = no focus). */
@Immutable
data class LandscapeCoverBudget(val rest: Dp, val focus: Dp?)

/**
 * LandscapeNP: at rest the cover takes the height the rating row leaves,
 * capped so the right column keeps a phone's width; in focus the rating row
 * retreats and the cover takes the whole height while the right column
 * keeps the landscape focus minimum.
 */
fun resolveLandscapeCover(width: Dp, height: Dp): LandscapeCoverBudget {
    val budget = sharedResolveLandscapeCover(width.value, height.value)
    return LandscapeCoverBudget(rest = budget.rest.dp, focus = budget.focus?.dp)
}

// ── Shared ──────────────────────────────────────────────────────────────

/**
 * The column's bottom edge: the system inset, but never less than the
 * column's bottom floor. Windows that report no navigation bar — a bubble, a
 * desktop window, a cover screen with the gesture hint hidden — otherwise
 * set the pills flush on the rounded window edge, which cuts them.
 */
fun stageColumnBottomInset(systemBottom: Dp): Dp = sharedStageColumnBottomInset(systemBottom.value).dp

/** Focus is worth a tap only when the cover grows by max(40dp, 12%). */
fun coverFocusOffered(rest: Dp, focus: Dp): Boolean = sharedCoverFocusOffered(rest.value, focus.value)

/** How far each tier compresses (0..1) and what is still missing after both. */
@Immutable
data class Squeeze(val lower: Float, val upper: Float, val unmet: Dp)

/** Spend [deficit] from the LOWER gaps first, then the UPPER ones, each tier on one shared fraction. */
fun squeezeGaps(deficit: Dp, lower: List<NpGap>, upper: List<NpGap>): Squeeze {
    val squeeze = sharedSqueezeGaps(deficit.value, lower.map { it.shared() }, upper.map { it.shared() })
    return Squeeze(lower = squeeze.lower, upper = squeeze.upper, unmet = squeeze.unmet.dp)
}

// ── Tokens (YoinTokens.NowPlaying) ──────────────────────────────────────

/** LyricsDisplay geometry (kept in step with LyricsDisplay.kt). */
internal val LyricsListPadding = YoinTokens.NowPlaying.LYRICS_LIST_PADDING.dp
internal val LyricsListLineGap = YoinTokens.NowPlaying.LYRICS_LIST_LINE_GAP.dp
internal val LyricsFadeBottom = YoinTokens.NowPlaying.LYRICS_FADE_BOTTOM.dp
internal val LyricsAnchorFraction = YoinTokens.NowPlaying.LYRICS_ANCHOR_FRACTION.toFloat()

/** Enlarged: three clean lines (= four visible). */
internal const val EnlargedCleanLines = YoinTokens.NowPlaying.ENLARGED_CLEAN_LINES

val CoverFocusMaxScale = YoinTokens.NowPlaying.COVER_FOCUS_MAX_SCALE.toFloat()

/** The DualPane left stack's measuring width (TabletNP: the 312dp cover side). */
val DualPaneStackWidth = YoinTokens.NowPlaying.DUAL_PANE_STACK_WIDTH.dp
