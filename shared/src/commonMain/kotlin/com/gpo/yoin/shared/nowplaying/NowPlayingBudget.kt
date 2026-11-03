package com.gpo.yoin.shared.nowplaying

import com.gpo.yoin.shared.tokens.YoinTokens.NowPlaying as T

/*
 * Now Playing's height budget (adaptive-principles §6): every fixed-height
 * Now Playing body resolves its slots HERE, from measured content minimums
 * plus compressible gaps — never from hand-summed reserves in the layout.
 *
 * One ladder for every family; recovery runs it backwards:
 *  0. the elastic remainder (lyric window above its minimum, centring weights)
 *  1. LOWER gaps (the air under the cover: transport / title / pills)
 *  2. UPPER gaps (top bar, cover gap, tab air, the one-line row's air)
 *  3. compact forms (the column's hero → one row; DualPane title 2 → 1 line)
 *  4. the lyric frame steps down (list → one tappable line)
 *  5. the cover shrinks from its cap to its floor
 *  6. the identity folds into the top bar (DualPane)
 * Never yields: the two transport rows, the three pills, one title line, the
 * lyric entry, 48dp touch targets.
 *
 * Tapping the cover is FOCUS (the shared Immersive stage): the rating, the
 * tabs, the lyric window and the gaps give way so the cover visibly grows —
 * the current lyric line always stays.
 * Focus is offered only when it grows by at least max(40dp, 12%); otherwise
 * the cover is a plain image (no click, no press, no semantics).
 *
 * Shared by both apps: Android wraps it in Dp (ui/nowplaying/NowPlayingBudget.kt),
 * iOS in CGFloat (NowPlayingView.swift). Lengths are dp / pt as Float, with
 * exactly the arithmetic Compose's Dp does (Float add / multiply, lerp =
 * (1 − f)·a + f·b), so Android's results are bit-identical to the Dp code it
 * replaced (NowPlayingBudgetParityTest).
 */

/** A gap with a resting [nominal] and the [min] it compresses to. */
data class NpGap(val nominal: Float, val min: Float) {
    val slack: Float get() = (nominal - min).coerceAtLeast(0f)

    /** 0 = nominal, 1 = fully compressed. */
    fun at(squeeze: Float): Float = lerp(nominal, min, squeeze.coerceIn(0f, 1f))
}

/**
 * The lyric window's own geometry (LyricsDisplay): the active line sits at
 * LYRICS_ANCHOR_FRACTION of the viewport, below a LYRICS_LIST_PADDING inset,
 * and the bottom LYRICS_FADE_BOTTOM dissolves. "N clean lines" = the active
 * line plus N − 1 following lines, none of them inside the bottom fade.
 */
data class LyricWindowMetrics(val activeLine: Float, val inactiveLine: Float) {
    fun cleanLines(n: Int): Float {
        val lines = activeLine + (inactiveLine + LyricsListLineGap) * (n - 1).coerceAtLeast(0)
        return (LyricsListPadding + lines + LyricsFadeBottom) / (1f - LyricsAnchorFraction)
    }
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
data class StageColumnMetrics(
    /** Text-tab row text height (labelLarge line). */
    val tabText: Float,
    /** The two transport rows (+ the time-label row when it drops below the wave). */
    val transport: Float,
    /** Artist + 2 + title lines. */
    val hero: Float,
    /** Title and artist on one row: the title's line. */
    val heroOneLine: Float,
    /** The pill row (44, 48 with the Cast button). */
    val pills: Float,
    /** The one-line lyric's text box (bold bodyLarge line + its 4dp pads). */
    val oneLineContent: Float,
    val lyric: LyricWindowMetrics,
)

/** The enlarged phone's column (NowPlayingEnlargedSpec on Android). */
data class EnlargedSpec(val columnWidth: Float, val ratingColumn: Float, val controlSize: Float)

/** Resting / focus geometry of the single column. Every slot is exact: they sum to the height. */
data class StageColumnPose(
    val topBar: Float,
    val cover: Float,
    val coverGap: Float,
    val tabs: Float,
    val tabGap: Float,
    val lyricSlot: Float,
    val lyricFrame: LyricFrame,
    val controls: Float,
    val hero: Float,
    val accessory: Float,
    /** Lower / upper gap compression of this pose, 0..1 (QA + tests). */
    val lower: Float,
    val upper: Float,
) {
    val total: Float get() = topBar + cover + coverGap + tabs + tabGap + lyricSlot + controls + hero + accessory
}

data class StageColumnBudget(
    /** The column's width (the enlarged stage narrows with a height-bound cover). */
    val stageWidth: Float,
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
 * [enlarged] carries the enlarged phone's 44dp tool-tab row.
 * [previous] keeps the lyric frame from flapping at the gate.
 */
fun resolveStageColumnBudget(
    width: Float,
    height: Float,
    metrics: StageColumnMetrics,
    enlarged: EnlargedSpec? = null,
    previous: LyricFrame? = null,
): StageColumnBudget {
    val ratingColumn = enlarged?.ratingColumn ?: PhoneRatingColumn
    val stageMaxWidth = enlarged?.let { minOf(width, it.columnWidth + ColumnPadding * 2) } ?: width
    val coverCap = if (enlarged == null) {
        (width - ColumnPadding * 2 - RatingGap - ratingColumn).coerceIn(0f, PhoneCoverMax)
    } else {
        (stageMaxWidth - ColumnPadding * 2 - RatingGap - ratingColumn).coerceAtLeast(0f)
    }
    val tabsWithTools = enlarged != null

    // Slots: nominal = the designed value (or content + min air when the
    // font is larger), min = content + min air.
    val topBar = NpGap(TopBarNominal, TopBarMin)
    val coverGap = NpGap(CoverGapNominal, CoverGapMin)
    val tabs = if (tabsWithTools) {
        NpGap(EnlargedToolTabRow, EnlargedToolTabRow)
    } else {
        NpGap(dpMax(TabRowNominal, metrics.tabText + TabRowAir), metrics.tabText)
    }
    val tabGap = if (tabsWithTools) NpGap(TabGapNominal, 0f) else NpGap(TabGapNominal, TabGapNominal)
    val controlsDesigned = ControlsSlotNominal + ((enlarged?.controlSize ?: ControlSize) - ControlSize) * 2
    val controls = slot(controlsDesigned, metrics.transport, ControlsAirMin)
    // The identity block: artist over title, or — when the window is short
    // (the 16:9 gate, or two lyric lines are only a hero away) — title and
    // artist on ONE row.
    val heroStacked = slot(HeroSlotNominal, metrics.hero, HeroAirMin)
    val heroOneLine = NpGap(metrics.heroOneLine + HeroOneLineAir, metrics.heroOneLine + HeroAirMin)
    val accessory = slot(AccessorySlotNominal, metrics.pills, AccessoryAirMin)
    val oneLine = NpGap(
        dpMax(OneLineRowNominal, metrics.oneLineContent + OneLineAirMin * 3),
        metrics.oneLineContent + OneLineAirMin,
    )

    val upperList = listOf(topBar, coverGap, tabs, tabGap)
    val upperOneLine = listOf(topBar, coverGap, oneLine)
    fun lowerGaps(hero: NpGap) = listOf(controls, hero, accessory)

    val listMin = metrics.lyric.cleanLines(if (enlarged != null) EnlargedCleanLines else PhoneCleanLines)

    fun pose(
        frame: LyricFrame,
        cover: Float,
        hero: NpGap,
        squeeze: Squeeze,
    ): StageColumnPose {
        val oneLineFrame = frame == LyricFrame.OneLine
        val top = topBar.at(squeeze.upper)
        val cg = coverGap.at(squeeze.upper)
        val tb = if (oneLineFrame) 0f else tabs.at(squeeze.upper)
        val tg = if (oneLineFrame) 0f else tabGap.at(squeeze.upper)
        val ctl = controls.at(squeeze.lower)
        val hr = hero.at(squeeze.lower)
        val acc = accessory.at(squeeze.lower)
        val lyric = (height - top - cover - cg - tb - tg - ctl - hr - acc).coerceAtLeast(0f)
        return StageColumnPose(
            topBar = top, cover = cover, coverGap = cg, tabs = tb, tabGap = tg,
            lyricSlot = lyric, lyricFrame = frame,
            controls = ctl, hero = hr, accessory = acc,
            lower = squeeze.lower, upper = squeeze.upper,
        )
    }

    fun nominalSum(gaps: List<NpGap>) = gaps.fold(0f) { acc, g -> acc + g.nominal }

    // ── Rest ────────────────────────────────────────────────────────────
    val hysteresis = if (previous == LyricFrame.OneLine) LyricFrameHysteresis else 0f

    // The list keeps the full cover by spending every gap first ("间距永远先于
    // 内容换形"), then the stacked hero folds to one row, and only then the
    // lyrics fold to one line. The enlarged phone instead lets its cover go
    // down to the comfort size before the lyric window steps down
    // (lyrics-first, §6).
    val listCoverFloor = if (enlarged != null) dpMin(coverCap, EnlargedCoverComfort) else coverCap
    fun listFits(hero: NpGap): Boolean = squeezeGaps(
        (nominalSum(lowerGaps(hero)) + nominalSum(upperList) + listMin + listCoverFloor + hysteresis - height)
            .coerceAtLeast(0f),
        lowerGaps(hero),
        upperList,
    ).unmet <= 0f
    val heroCompact: Boolean
    val rest = when {
        listFits(heroStacked) || listFits(heroOneLine) -> {
            heroCompact = !listFits(heroStacked)
            val hero = if (heroCompact) heroOneLine else heroStacked
            val listFixed = nominalSum(lowerGaps(hero)) + nominalSum(upperList)
            val cover = if (enlarged != null) {
                // Spend gaps to keep the cover at its cap, but no further than needed.
                val atCap = squeezeGaps(
                    (listFixed + listMin + coverCap - height).coerceAtLeast(0f),
                    lowerGaps(hero),
                    upperList,
                )
                if (atCap.unmet <= 0f) coverCap else (coverCap - atCap.unmet).coerceAtLeast(listCoverFloor)
            } else {
                coverCap
            }
            val squeeze = squeezeGaps(
                (listFixed + listMin + cover - height).coerceAtLeast(0f),
                lowerGaps(hero),
                upperList,
            )
            pose(LyricFrame.List, cover, hero, squeeze)
        }
        else -> {
            // 16:9: one tappable lyric line and a one-row hero; the cover
            // takes what the full ladder leaves.
            heroCompact = true
            val lower = lowerGaps(heroOneLine)
            val oneLineFixed = nominalSum(lower) + nominalSum(upperOneLine)
            val minFixed = oneLineFixed - slackOf(lower) - slackOf(upperOneLine)
            val cover = (height - minFixed).coerceAtMost(coverCap).let { if (it < CoverFloor) 0f else it }
            val squeeze = squeezeGaps((oneLineFixed + cover - height).coerceAtLeast(0f), lower, upperOneLine)
            pose(LyricFrame.OneLine, cover, heroOneLine, squeeze)
        }
    }
    val hero = if (heroCompact) heroOneLine else heroStacked
    val stageWidth = if (enlarged != null) {
        dpMin(stageMaxWidth, rest.cover + RatingGap + ratingColumn + ColumnPadding * 2)
    } else {
        width
    }

    // ── Focus ───────────────────────────────────────────────────────────
    // The cover grows, but the current lyric line stays (a focused cover
    // with the lyrics gone read as a broken player).
    val focusWidthCap = if (enlarged != null) {
        stageWidth - ColumnPadding * 2
    } else {
        dpMin(stageWidth - ColumnPadding * 2, PhoneFocusCoverMax)
    }
    val focusTarget = dpMin(focusWidthCap, rest.cover * CoverFocusMaxScale)
    val focusLower = lowerGaps(hero)
    val focusUpper = listOf(topBar, coverGap)
    val focusFixedMin = nominalSum(focusLower) + nominalSum(focusUpper) - slackOf(focusLower) - slackOf(focusUpper)
    val previewSlot = metrics.oneLineContent + OneLineAirMin
    val focusCover = dpMin(focusTarget, (height - focusFixedMin - previewSlot).coerceAtLeast(0f))
    val focus = if (rest.cover > 0f && coverFocusOffered(rest.cover, focusCover)) {
        // Room left after the cover and the line goes back to the gaps
        // (upper first), then to the lyric slot.
        val spare = height - focusFixedMin - previewSlot - focusCover
        val restored = dpMin(spare, slackOf(focusLower) + slackOf(focusUpper))
        val squeeze = squeezeGaps(
            slackOf(focusLower) + slackOf(focusUpper) - restored,
            focusLower,
            focusUpper,
        )
        val p = pose(LyricFrame.Preview, focusCover, hero, squeeze).copy(tabs = 0f, tabGap = 0f)
        val slot = height - (p.total - p.lyricSlot)
        val frame = if (slot >= metrics.lyric.cleanLines(EnlargedCleanLines)) LyricFrame.List else LyricFrame.Preview
        p.copy(lyricSlot = slot.coerceAtLeast(0f), lyricFrame = frame)
    } else {
        null
    }
    return StageColumnBudget(stageWidth = stageWidth, rest = rest, focus = focus, heroOneLine = heroCompact)
}

// ── DualPane ────────────────────────────────────────────────────────────

/** How the DualPane identity block is set. Decided per window, never per song. */
enum class DualPaneTitleForm { TwoLines, OneLine, TopBar }

/** Content minimums of the DualPane left stack (measured at DUAL_PANE_STACK_WIDTH). */
data class DualPaneMetrics(
    val transport: Float,
    /** Artist line + 2 + one / two title lines. */
    val titleOneLine: Float,
    val titleTwoLines: Float,
    val pills: Float,
    /** Bottom system inset the pills clear. */
    val navBottom: Float,
)

data class DualPanePose(
    val slot: Float,
    val cover: Float,
    val coverToRating: Float,
    val ratingToTransport: Float,
    val transportToTitle: Float,
    /** 0 when the rating / title retreat (focus) or the title folded into the top bar. */
    val rating: Float,
    val title: Float,
)

data class DualPaneBudget(
    /** Outer frame: top padding, the gap under the top bar, bottom padding. */
    val frameTop: Float,
    val barGap: Float,
    val frameBottom: Float,
    val titleForm: DualPaneTitleForm,
    val rest: DualPanePose,
    val focus: DualPanePose?,
) {
    val focusOffered: Boolean get() = focus != null
}

/**
 * The two-column player's left stack: it is ALWAYS measured at
 * DUAL_PANE_STACK_WIDTH — the cover never decides the controls' width (the
 * 170dp stack that dropped Next and Write). [contentHeight] is the window
 * minus the top system inset; [barHeight] the top bar; [rowWidth] the width
 * both columns share.
 */
fun resolveDualPaneBudget(
    rowWidth: Float,
    contentHeight: Float,
    barHeight: Float,
    metrics: DualPaneMetrics,
): DualPaneBudget {
    val frameTop = NpGap(WideFrameNominal, WideFrameMin)
    val barGap = NpGap(WideBarGapNominal, WideBarGapMin)
    val frameBottom = NpGap(WideFrameNominal, WideFrameMin)
    val coverToRating = NpGap(WideCoverToRatingNominal, WideCoverToRatingMin)
    val ratingToTransport = NpGap(WideRatingToTransportNominal, WideRatingToTransportMin)
    val transportToTitle = NpGap(WideTransportToTitleNominal, WideTransportToTitleMin)
    val lower = listOf(transportToTitle, frameBottom)
    val upper = listOf(frameTop, barGap, coverToRating, ratingToTransport)
    val fixedContent = barHeight + WideRatingRow + metrics.transport + metrics.pills + metrics.navBottom
    val gapsNominal = (lower + upper).fold(0f) { acc, g -> acc + g.nominal }
    val gapsSlack = slackOf(lower) + slackOf(upper)

    fun coverFor(title: Float, squeezeAll: Boolean): Float {
        val gaps = if (squeezeAll) gapsNominal - gapsSlack else gapsNominal
        return contentHeight - fixedContent - title - gaps
    }

    // Ladder: gaps → two-line title → one-line → cover shrinks → title to the top bar.
    val form: DualPaneTitleForm
    val title: Float
    when {
        coverFor(metrics.titleTwoLines, squeezeAll = true) >= DualPaneStackWidth -> {
            form = DualPaneTitleForm.TwoLines
            title = metrics.titleTwoLines
        }
        coverFor(metrics.titleOneLine, squeezeAll = true) >= DualPaneCoverMin -> {
            form = DualPaneTitleForm.OneLine
            title = metrics.titleOneLine
        }
        else -> {
            form = DualPaneTitleForm.TopBar
            title = 0f
        }
    }
    val restCover = coverFor(title, squeezeAll = true)
        .coerceAtMost(DualPaneStackWidth)
        .coerceAtLeast(DualPaneCoverFloor)
    val restSqueeze = squeezeGaps(
        (fixedContent + title + gapsNominal + restCover - contentHeight).coerceAtLeast(0f),
        lower,
        upper,
    )
    val restPose = DualPanePose(
        slot = DualPaneStackWidth,
        cover = restCover,
        coverToRating = coverToRating.at(restSqueeze.upper),
        ratingToTransport = ratingToTransport.at(restSqueeze.upper),
        transportToTitle = transportToTitle.at(restSqueeze.lower),
        rating = WideRatingRow,
        title = title,
    )

    // Focus: the rating and title retreat, the inner gaps close, the cover
    // grows (≤ 1.5×, the lyrics keep 320) and the slot follows the cover.
    val focusHeight = contentHeight - barHeight - metrics.transport - metrics.pills - metrics.navBottom -
        frameTop.at(restSqueeze.upper) - barGap.at(restSqueeze.upper) - frameBottom.at(restSqueeze.lower) -
        WideCoverToRatingMin - WideRatingToTransportMin
    val focusCover = dpMin(
        restCover * CoverFocusMaxScale,
        dpMin(rowWidth - WideColumnGap - WideRightColumnMin, focusHeight),
    )
    val focusPose = if (coverFocusOffered(restCover, focusCover)) {
        DualPanePose(
            slot = dpMax(DualPaneStackWidth, focusCover),
            cover = focusCover,
            coverToRating = WideCoverToRatingMin,
            ratingToTransport = WideRatingToTransportMin,
            transportToTitle = 0f,
            rating = 0f,
            title = 0f,
        )
    } else {
        null
    }
    return DualPaneBudget(
        frameTop = frameTop.at(restSqueeze.upper),
        barGap = barGap.at(restSqueeze.upper),
        frameBottom = frameBottom.at(restSqueeze.lower),
        titleForm = form,
        rest = restPose,
        focus = focusPose,
    )
}

// ── Landscape ───────────────────────────────────────────────────────────

/** The landscape handset's cover side at rest and in focus (null = no focus). */
data class LandscapeCoverBudget(val rest: Float, val focus: Float?)

/**
 * LandscapeNP: at rest the cover takes the height the rating row leaves,
 * capped so the right column keeps a phone's width; in focus the rating row
 * retreats and the cover takes the whole height while the right column
 * keeps LANDSCAPE_FOCUS_RIGHT_MIN.
 */
fun resolveLandscapeCover(width: Float, height: Float): LandscapeCoverBudget {
    val rest = (height - LandscapeRatingRow - LandscapeRatingGap)
        .coerceAtMost(width * LandscapeCoverWidthShare)
        .coerceAtLeast(LandscapeCoverFloor)
    val focus = dpMin(
        height,
        dpMin(width - LandscapeColumnGap - LandscapeFocusRightMin, rest * CoverFocusMaxScale),
    )
    return LandscapeCoverBudget(rest = rest, focus = focus.takeIf { coverFocusOffered(rest, it) })
}

// ── Shared ──────────────────────────────────────────────────────────────

/**
 * The column's bottom edge: the system inset, but never less than
 * COLUMN_BOTTOM_FLOOR. Windows that report no navigation bar — a bubble, a
 * desktop window, a cover screen with the gesture hint hidden — otherwise
 * set the pills flush on the rounded window edge, which cuts them.
 */
fun stageColumnBottomInset(systemBottom: Float): Float = dpMax(systemBottom, ColumnBottomFloor)

/** Focus is worth a tap only when the cover grows by max(40dp, 12%). */
fun coverFocusOffered(rest: Float, focus: Float): Boolean =
    focus - rest >= dpMax(CoverFocusMinGrowth, rest * CoverFocusMinGrowthFraction)

/** How far each tier compresses (0..1) and what is still missing after both. */
data class Squeeze(val lower: Float, val upper: Float, val unmet: Float)

/** Spend [deficit] from the LOWER gaps first, then the UPPER ones, each tier on one shared fraction. */
fun squeezeGaps(deficit: Float, lower: List<NpGap>, upper: List<NpGap>): Squeeze {
    if (deficit <= 0f) return Squeeze(0f, 0f, 0f)
    val lowerSlack = slackOf(lower)
    val upperSlack = slackOf(upper)
    if (deficit <= lowerSlack) {
        return Squeeze(lower = if (lowerSlack > 0f) deficit / lowerSlack else 0f, upper = 0f, unmet = 0f)
    }
    val rest = deficit - lowerSlack
    return if (rest <= upperSlack) {
        Squeeze(lower = 1f, upper = if (upperSlack > 0f) rest / upperSlack else 0f, unmet = 0f)
    } else {
        Squeeze(lower = 1f, upper = 1f, unmet = rest - upperSlack)
    }
}

private fun slackOf(gaps: List<NpGap>): Float = gaps.fold(0f) { acc, g -> acc + g.slack }

/** A content slot: the designed height, or content + [minAir] when the content is taller. */
private fun slot(designed: Float, content: Float, minAir: Float): NpGap =
    NpGap(dpMax(designed, content + minAir), content + minAir)

/** Compose's `lerp(Float, Float, Float)` (what `lerp(Dp, Dp, Float)` runs). */
private fun lerp(start: Float, stop: Float, fraction: Float): Float = (1 - fraction) * start + fraction * stop

/** `maxOf(Dp, Dp)` / `minOf(Dp, Dp)`: Comparable order (first wins a tie). */
private fun dpMax(a: Float, b: Float): Float = if (a >= b) a else b
private fun dpMin(a: Float, b: Float): Float = if (a <= b) a else b

// ── Tokens (YoinTokens.NowPlaying, as the Float dp the math runs in) ─────

internal val LyricsListPadding = T.LYRICS_LIST_PADDING.toFloat()
internal val LyricsListLineGap = T.LYRICS_LIST_LINE_GAP.toFloat()
internal val LyricsFadeBottom = T.LYRICS_FADE_BOTTOM.toFloat()
internal val LyricsAnchorFraction = T.LYRICS_ANCHOR_FRACTION.toFloat()

private const val PhoneCleanLines = T.PHONE_CLEAN_LINES
private const val EnlargedCleanLines = T.ENLARGED_CLEAN_LINES

private val ColumnPadding = T.COLUMN_PADDING.toFloat()
private val ColumnBottomFloor = T.COLUMN_BOTTOM_FLOOR.toFloat()
private val RatingGap = T.RATING_GAP.toFloat()
private val PhoneRatingColumn = T.PHONE_RATING_COLUMN.toFloat()
private val PhoneCoverMax = T.PHONE_COVER_MAX.toFloat()
private val PhoneFocusCoverMax = T.PHONE_FOCUS_COVER_MAX.toFloat()
private val CoverFloor = T.COVER_FLOOR.toFloat()
private val EnlargedCoverComfort = T.ENLARGED_COVER_COMFORT.toFloat()
private val ControlSize = T.CONTROL_SIZE.toFloat()

private val TopBarNominal = T.TOP_BAR_NOMINAL.toFloat()
private val TopBarMin = T.TOP_BAR_MIN.toFloat()
private val CoverGapNominal = T.COVER_GAP_NOMINAL.toFloat()
private val CoverGapMin = T.COVER_GAP_MIN.toFloat()
private val TabRowNominal = T.TAB_ROW_NOMINAL.toFloat()
private val TabRowAir = T.TAB_ROW_AIR.toFloat()
private val EnlargedToolTabRow = T.ENLARGED_TOOL_TAB_ROW.toFloat()
private val TabGapNominal = T.TAB_GAP_NOMINAL.toFloat()
private val ControlsSlotNominal = T.CONTROLS_SLOT_NOMINAL.toFloat()
private val ControlsAirMin = T.CONTROLS_AIR_MIN.toFloat()
private val HeroSlotNominal = T.HERO_SLOT_NOMINAL.toFloat()
private val HeroAirMin = T.HERO_AIR_MIN.toFloat()
private val HeroOneLineAir = T.HERO_ONE_LINE_AIR.toFloat()
private val AccessorySlotNominal = T.ACCESSORY_SLOT_NOMINAL.toFloat()
private val AccessoryAirMin = T.ACCESSORY_AIR_MIN.toFloat()
private val OneLineRowNominal = T.ONE_LINE_ROW_NOMINAL.toFloat()
private val OneLineAirMin = T.ONE_LINE_AIR_MIN.toFloat()
private val LyricFrameHysteresis = T.LYRIC_FRAME_HYSTERESIS.toFloat()

private val CoverFocusMaxScale = T.COVER_FOCUS_MAX_SCALE.toFloat()
private val CoverFocusMinGrowth = T.COVER_FOCUS_MIN_GROWTH.toFloat()
private val CoverFocusMinGrowthFraction = T.COVER_FOCUS_MIN_GROWTH_FRACTION.toFloat()

private val DualPaneStackWidth = T.DUAL_PANE_STACK_WIDTH.toFloat()
private val DualPaneCoverMin = T.DUAL_PANE_COVER_MIN.toFloat()
private val DualPaneCoverFloor = T.DUAL_PANE_COVER_FLOOR.toFloat()
private val WideRatingRow = T.WIDE_RATING_ROW.toFloat()
private val WideFrameNominal = T.WIDE_FRAME_NOMINAL.toFloat()
private val WideFrameMin = T.WIDE_FRAME_MIN.toFloat()
private val WideBarGapNominal = T.WIDE_BAR_GAP_NOMINAL.toFloat()
private val WideBarGapMin = T.WIDE_BAR_GAP_MIN.toFloat()
private val WideCoverToRatingNominal = T.WIDE_COVER_TO_RATING_NOMINAL.toFloat()
private val WideCoverToRatingMin = T.WIDE_COVER_TO_RATING_MIN.toFloat()
private val WideRatingToTransportNominal = T.WIDE_RATING_TO_TRANSPORT_NOMINAL.toFloat()
private val WideRatingToTransportMin = T.WIDE_RATING_TO_TRANSPORT_MIN.toFloat()
private val WideTransportToTitleNominal = T.WIDE_TRANSPORT_TO_TITLE_NOMINAL.toFloat()
private val WideTransportToTitleMin = T.WIDE_TRANSPORT_TO_TITLE_MIN.toFloat()
private val WideColumnGap = T.WIDE_COLUMN_GAP.toFloat()
private val WideRightColumnMin = T.WIDE_RIGHT_COLUMN_MIN.toFloat()

private val LandscapeRatingRow = T.LANDSCAPE_RATING_ROW.toFloat()
private val LandscapeRatingGap = T.LANDSCAPE_RATING_GAP.toFloat()
private val LandscapeCoverWidthShare = T.LANDSCAPE_COVER_WIDTH_SHARE.toFloat()
private val LandscapeCoverFloor = T.LANDSCAPE_COVER_FLOOR.toFloat()
private val LandscapeColumnGap = T.LANDSCAPE_COLUMN_GAP.toFloat()
private val LandscapeFocusRightMin = T.LANDSCAPE_FOCUS_RIGHT_MIN.toFloat()
