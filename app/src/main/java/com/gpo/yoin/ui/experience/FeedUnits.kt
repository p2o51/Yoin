package com.gpo.yoin.ui.experience

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinPageWidths
import kotlin.math.floor

// ---------------------------------------------------------------------------
// Feed density (Home) — 数量跟宽度走，卡片内部不缩放
// ---------------------------------------------------------------------------

/**
 * The page frame a feed (Home) is laid into, judged height first (adaptive
 * principle 1): a landscape handset keeps its own frame whatever its width.
 */
enum class FeedFrameClass {
    /** Height < 480 ([YoinWindowInfo.isCompactHeight]): 24dp margins, full width. */
    Landscape,

    /** Compact / Medium: 16dp margins, content capped at 688 (the 720 Feed column). */
    Capped,

    /**
     * Tabletop: the same capped column, always centred. A Tabletop posture can
     * REST wider than 840dp, so the Capped frame's start alignment above 840
     * (meant for mid-spring widths only) would leave it lopsided.
     */
    CappedCentred,

    /** Wide (≥ 840) and tall: the canvas is filled edge to edge inside 32dp margins (A-prime). */
    Wide,
}

/** [FeedFrameClass] of a container already read into [info] (its own width, not the window's). */
fun feedFrameClass(info: YoinWindowInfo): FeedFrameClass = feedFrameClass(info.layoutMode, info.chromeForm)

/** The same judgement from the raw inputs — for the runtime, before the info exists. */
internal fun feedFrameClass(layoutMode: LayoutMode, chromeForm: ShellChromeForm): FeedFrameClass = when {
    chromeForm == ShellChromeForm.EdgeSplit -> FeedFrameClass.Landscape
    layoutMode == LayoutMode.Wide -> FeedFrameClass.Wide
    layoutMode == LayoutMode.Tabletop -> FeedFrameClass.CappedCentred
    else -> FeedFrameClass.Capped
}

/**
 * The feed's content width C inside a [containerWidth]-wide container: Wide
 * W − 64; Capped min(W, 720) − 32; Landscape W − 48. Never negative.
 */
fun feedContentWidth(containerWidth: Dp, frame: FeedFrameClass): Dp = when (frame) {
    FeedFrameClass.Landscape -> containerWidth - FeedLandscapeMargin * 2
    FeedFrameClass.Wide -> containerWidth - FeedWideMargin * 2
    FeedFrameClass.Capped,
    FeedFrameClass.CappedCentred,
    -> minOf(containerWidth, YoinPageWidths.Feed) - FeedCappedMargin * 2
}.coerceAtLeast(0.dp)

/**
 * The feed's discrete width class N (`YoinWindowInfo.feedUnits`): how many
 * 130dp+ units (10dp gaps) fit in [contentWidth]. Non-Wide: C < 310 → 1,
 * C < 448 → 2, else ⌊(C + 10) / 140⌋ in 3..4; Wide: ⌊(C + 10) / 140⌋ in 5..8.
 * As container widths: 342 / 480 / 582 / 600 / 840 / 894 / 1034 / 1174.
 *
 * Column widths are float sums; the same 0.01dp guard as [forPaneWidth]
 * keeps an ulp below a breakpoint from dropping a tier.
 */
fun feedUnitsFor(contentWidth: Dp, layoutMode: LayoutMode): Int {
    val c = contentWidth.value + FEED_EDGE_EPSILON
    val fitted = floor((c + FEED_UNIT_GAP) / FEED_UNIT_PITCH).toInt()
    return if (layoutMode == LayoutMode.Wide) {
        fitted.coerceIn(FEED_WIDE_MIN_UNITS, FEED_MAX_UNITS)
    } else {
        when {
            c < FEED_TWO_UNITS_MIN_CONTENT -> 1
            c < FEED_THREE_UNITS_MIN_CONTENT -> 2
            else -> fitted.coerceIn(3, FEED_CAPPED_MAX_UNITS)
        }
    }
}

/** N for a [containerWidth]-wide container in [layoutMode] / [chromeForm] — what the runtime stores. */
internal fun feedUnitsForContainer(containerWidth: Dp, layoutMode: LayoutMode, chromeForm: ShellChromeForm): Int =
    feedUnitsFor(feedContentWidth(containerWidth, feedFrameClass(layoutMode, chromeForm)), layoutMode)

/**
 * The N a directly-constructed [YoinWindowInfo] defaults to (tests, the
 * Compact fallback local): Compact 2, Medium / Tabletop 4, Wide 8 — so a
 * hand-built Medium never silently falls back to the phone composition.
 */
fun representativeFeedUnits(layoutMode: LayoutMode): Int = when (layoutMode) {
    LayoutMode.Compact -> 2
    LayoutMode.Medium, LayoutMode.Tabletop -> FEED_CAPPED_MAX_UNITS
    LayoutMode.Wide -> FEED_MAX_UNITS
}

/**
 * How many phone-sized cover columns (≥ 112dp, 12dp gaps) the feed's
 * [contentWidth] seats — `YoinWindowInfo.feedCoverColumns`, read by Jump Back
 * In's templated grid: ⌊(C + 12) / 124⌋ in 3..10. Covers then land at
 * 100–128dp, the phone's own size, instead of growing with a 4 / 6-column
 * grid (owner 2026-10-04: a 130–160dp cover reads too big on a foldable and
 * a landscape tablet). As content widths: 360 / 484 / 608 / 732 / 856 / 980 /
 * 1104 / 1228.
 */
fun feedCoverColumnsFor(contentWidth: Dp): Int {
    val c = contentWidth.value + FEED_EDGE_EPSILON
    return floor((c + FEED_COVER_GAP) / (FEED_COVER_MIN_COLUMN + FEED_COVER_GAP)).toInt()
        .coerceIn(FEED_COVER_MIN_COLUMNS, FEED_COVER_MAX_COLUMNS)
}

/** Cover columns for a [containerWidth]-wide container — what the runtime stores. */
internal fun feedCoverColumnsForContainer(
    containerWidth: Dp,
    layoutMode: LayoutMode,
    chromeForm: ShellChromeForm,
): Int = feedCoverColumnsFor(feedContentWidth(containerWidth, feedFrameClass(layoutMode, chromeForm)))

/** The cover columns a directly-constructed [YoinWindowInfo] defaults to: Compact 3, Medium / Tabletop 5, Wide 9. */
fun representativeFeedCoverColumns(layoutMode: LayoutMode): Int = when (layoutMode) {
    LayoutMode.Compact -> FEED_COVER_MIN_COLUMNS
    LayoutMode.Medium, LayoutMode.Tabletop -> 5
    LayoutMode.Wide -> 9
}

/** The feed's horizontal page margins (start / end; content = W − start − end). */
@Immutable
data class FeedFrameInsets(val start: Dp, val end: Dp)

/**
 * Page margins of a [frame] in a [containerWidth]-wide container: Landscape
 * 24 / 24; Wide 32 / 32; Capped: start = 16 + max(0, (min(W, 840) − 720) / 2),
 * content = min(W − start − 16, 688), end = what is left. At rest this is
 * today's centred 720 column (753 → 32.5, 800 → 56, 839 → 75.5); a container
 * wider than any resting Capped width (mid-spring, after the tier flipped)
 * stays START-aligned (1280 → 76 + 688) instead of centring an island.
 */
fun feedFrameInsets(containerWidth: Dp, frame: FeedFrameClass): FeedFrameInsets = when (frame) {
    FeedFrameClass.Landscape -> FeedFrameInsets(FeedLandscapeMargin, FeedLandscapeMargin)
    FeedFrameClass.Wide -> FeedFrameInsets(FeedWideMargin, FeedWideMargin)
    FeedFrameClass.Capped -> {
        val w = containerWidth.coerceAtLeast(0.dp)
        val centring = (minOf(w, FeedCappedRestMax) - YoinPageWidths.Feed) / 2
        val start = FeedCappedMargin + centring.coerceAtLeast(0.dp)
        val content = minOf(w - start - FeedCappedMargin, FeedCappedMaxContent).coerceAtLeast(0.dp)
        FeedFrameInsets(start = start, end = (w - start - content).coerceAtLeast(0.dp))
    }
    FeedFrameClass.CappedCentred -> {
        val w = containerWidth.coerceAtLeast(0.dp)
        val start = FeedCappedMargin + ((w - YoinPageWidths.Feed) / 2).coerceAtLeast(0.dp)
        val content = minOf(w - start - FeedCappedMargin, FeedCappedMaxContent).coerceAtLeast(0.dp)
        FeedFrameInsets(start = start, end = (w - start - content).coerceAtLeast(0.dp))
    }
}

private val FeedLandscapeMargin = 24.dp
private val FeedWideMargin = 32.dp
private val FeedCappedMargin = 16.dp

/** 720 − 2 × 16: the Capped frame's widest content. */
private val FeedCappedMaxContent = YoinPageWidths.Feed - FeedCappedMargin * 2

/** The widest resting Capped container (Medium ends at 840); beyond it margins stop centring. */
private val FeedCappedRestMax = 840.dp

/** Gap between units; a unit is at least 130 wide, so one unit + gap = 140. */
private const val FEED_UNIT_GAP = 10f
private const val FEED_UNIT_PITCH = 140f

/** Two 150dp units + gap / three ~143dp units + gaps: below these N is 1 / 2. */
private const val FEED_TWO_UNITS_MIN_CONTENT = 310f
private const val FEED_THREE_UNITS_MIN_CONTENT = 448f

private const val FEED_CAPPED_MAX_UNITS = 4
private const val FEED_WIDE_MIN_UNITS = 5
private const val FEED_MAX_UNITS = 8

/** A phone cover column: 393dp phone → (361 − 24) / 3 ≈ 112dp. */
private const val FEED_COVER_MIN_COLUMN = 112f
private const val FEED_COVER_GAP = 12f
private const val FEED_COVER_MIN_COLUMNS = 3
private const val FEED_COVER_MAX_COLUMNS = 10

/** Float-sum guard, the same 0.01dp as [forPaneWidth]: far below a pixel. */
private const val FEED_EDGE_EPSILON = 0.01f
