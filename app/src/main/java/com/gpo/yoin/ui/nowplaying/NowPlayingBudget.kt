package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

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
 * Tapping the cover is FOCUS (the shared Immersive stage): the top bar, the
 * rating, the tabs and the pills give way; the title stays as it rests and
 * the lyrics always keep three clean lines — the cover grows only into what
 * is left (owner 2026-11-04). A short window (one resting lyric line)
 * focuses into the compact transport and is offered for the lyrics alone;
 * elsewhere focus is offered only when the cover grows by at least
 * max(40dp, 12%). Otherwise the cover is a plain image.
 *
 * Pure Dp math, JVM-tested (NowPlayingBudgetTest).
 */

/** A gap with a resting [nominal] and the [min] it compresses to. */
@Immutable
data class NpGap(val nominal: Dp, val min: Dp) {
    val slack: Dp get() = (nominal - min).coerceAtLeast(0.dp)

    /** 0 = nominal, 1 = fully compressed. */
    fun at(squeeze: Float): Dp = lerp(nominal, min, squeeze.coerceIn(0f, 1f))
}

/**
 * The lyric window's own geometry (LyricsDisplay): the active line sits at
 * [LyricsAnchorFraction] of the viewport, below a [LyricsListPadding] inset,
 * and the bottom [LyricsFadeBottom] dissolves. "N clean lines" = the active
 * line plus N − 1 following lines, none of them inside the bottom fade.
 */
@Immutable
data class LyricWindowMetrics(val activeLine: Dp, val inactiveLine: Dp) {
    fun cleanLines(n: Int): Dp {
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
    /**
     * Short windows' focus transport: PREVIOUS up in the play row, the
     * progress bar on its own row. Null = the window keeps both rows.
     */
    val compactTransport: Dp? = null,
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
    /** Focus narrows the column to the cover (+ its 24dp insets), centred in the window. */
    val focusStageWidth: Dp = stageWidth,
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
    val ratingColumn = enlarged?.ratingColumn ?: PhoneRatingColumn
    val stageMaxWidth = enlarged?.let { minOf(width, it.columnWidth + ColumnPadding * 2) } ?: width
    val coverCap = if (enlarged == null) {
        (width - ColumnPadding * 2 - RatingGap - ratingColumn).coerceIn(0.dp, PhoneCoverMax)
    } else {
        (stageMaxWidth - ColumnPadding * 2 - RatingGap - ratingColumn).coerceAtLeast(0.dp)
    }
    val tabsWithTools = enlarged != null

    // Slots: nominal = the designed value (or content + min air when the
    // font is larger), min = content + min air.
    val topBar = NpGap(TopBarNominal, TopBarMin)
    val coverGap = NpGap(CoverGapNominal, CoverGapMin)
    val tabs = if (tabsWithTools) {
        NpGap(EnlargedToolTabRow, EnlargedToolTabRow)
    } else {
        NpGap(maxOf(TabRowNominal, metrics.tabText + TabRowAir), metrics.tabText)
    }
    val tabGap = if (tabsWithTools) NpGap(TabGapNominal, 0.dp) else NpGap(TabGapNominal, TabGapNominal)
    val controlsDesigned = ControlsSlotNominal + ((enlarged?.controlSize ?: 56.dp) - 56.dp) * 2
    val controls = slot(controlsDesigned, metrics.transport, ControlsAirMin)
    // The identity block: artist over title, or — when the window is short
    // (the 16:9 gate, or two lyric lines are only a hero away) — title and
    // artist on ONE row.
    val heroStacked = slot(HeroSlotNominal, metrics.hero, HeroAirMin)
    val heroOneLine = NpGap(metrics.heroOneLine + HeroOneLineAir, metrics.heroOneLine + HeroAirMin)
    val accessory = slot(AccessorySlotNominal, metrics.pills, AccessoryAirMin)
    val oneLine = NpGap(
        maxOf(OneLineRowNominal, metrics.oneLineContent + OneLineAirMin * 3),
        metrics.oneLineContent + OneLineAirMin,
    )

    val upperList = listOf(topBar, coverGap, tabs, tabGap)
    val upperOneLine = listOf(topBar, coverGap, oneLine)
    fun lowerGaps(hero: NpGap) = listOf(controls, hero, accessory)

    val listMin = metrics.lyric.cleanLines(if (enlarged != null) EnlargedCleanLines else PhoneCleanLines)

    fun pose(
        frame: LyricFrame,
        cover: Dp,
        hero: NpGap,
        squeeze: Squeeze,
    ): StageColumnPose {
        val oneLineFrame = frame == LyricFrame.OneLine
        val top = topBar.at(squeeze.upper)
        val cg = coverGap.at(squeeze.upper)
        val tb = if (oneLineFrame) 0.dp else tabs.at(squeeze.upper)
        val tg = if (oneLineFrame) 0.dp else tabGap.at(squeeze.upper)
        val ctl = controls.at(squeeze.lower)
        val hr = hero.at(squeeze.lower)
        val acc = accessory.at(squeeze.lower)
        val lyric = (height - top - cover - cg - tb - tg - ctl - hr - acc).coerceAtLeast(0.dp)
        return StageColumnPose(
            topBar = top, cover = cover, coverGap = cg, tabs = tb, tabGap = tg,
            lyricSlot = lyric, lyricFrame = frame,
            controls = ctl, hero = hr, accessory = acc,
            lower = squeeze.lower, upper = squeeze.upper,
        )
    }

    fun nominalSum(gaps: List<NpGap>) = gaps.fold(0.dp) { acc, g -> acc + g.nominal }

    // ── Rest ────────────────────────────────────────────────────────────
    val hysteresis = if (previous == LyricFrame.OneLine) LyricFrameHysteresis else 0.dp

    // The list keeps the full cover by spending every gap first ("间距永远先于
    // 内容换形"), then the stacked hero folds to one row, and only then the
    // lyrics fold to one line. The enlarged phone instead lets its cover go
    // down to the comfort size before the lyric window steps down
    // (lyrics-first, §6).
    val listCoverFloor = if (enlarged != null) minOf(coverCap, EnlargedCoverComfort) else coverCap
    fun listFits(hero: NpGap): Boolean = squeezeGaps(
        (nominalSum(lowerGaps(hero)) + nominalSum(upperList) + listMin + listCoverFloor + hysteresis - height)
            .coerceAtLeast(0.dp),
        lowerGaps(hero),
        upperList,
    ).unmet <= 0.dp
    val heroCompact: Boolean
    val rest = when {
        listFits(heroStacked) || listFits(heroOneLine) -> {
            heroCompact = !listFits(heroStacked)
            val hero = if (heroCompact) heroOneLine else heroStacked
            val listFixed = nominalSum(lowerGaps(hero)) + nominalSum(upperList)
            val cover = if (enlarged != null) {
                // Spend gaps to keep the cover at its cap, but no further than needed.
                val atCap = squeezeGaps(
                    (listFixed + listMin + coverCap - height).coerceAtLeast(0.dp),
                    lowerGaps(hero),
                    upperList,
                )
                if (atCap.unmet <= 0.dp) coverCap else (coverCap - atCap.unmet).coerceAtLeast(listCoverFloor)
            } else {
                coverCap
            }
            val squeeze = squeezeGaps(
                (listFixed + listMin + cover - height).coerceAtLeast(0.dp),
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
            val cover = (height - minFixed).coerceAtMost(coverCap).let { if (it < CoverFloor) 0.dp else it }
            val squeeze = squeezeGaps((oneLineFixed + cover - height).coerceAtLeast(0.dp), lower, upperOneLine)
            pose(LyricFrame.OneLine, cover, heroOneLine, squeeze)
        }
    }
    val hero = if (heroCompact) heroOneLine else heroStacked
    val stageWidth = if (enlarged != null) {
        minOf(stageMaxWidth, rest.cover + RatingGap + ratingColumn + ColumnPadding * 2)
    } else {
        width
    }

    // ── Focus ───────────────────────────────────────────────────────────
    // Clean focus (owner 2026-11-03): the top bar, rating, tabs and the three
    // pills retreat; the title and the two transport rows stay as they rest.
    // Owner 2026-11-04: the lyrics always keep [FocusCleanLines] clean lines
    // (「没必要那么大封面，歌词保证有三行」), and the cover sits with the SAME
    // margin on its left, top (from the safe area) and right; the column
    // narrows to the cover, so the lyrics, title and transport line up with
    // its left and right edges.
    val focusCoverCap = minOf(
        stageMaxWidth - ColumnPadding * 2,
        if (enlarged == null) PhoneFocusCoverMax else stageMaxWidth,
        rest.cover * CoverFocusMaxScale,
    )
    val focusBottom = NpGap(FocusBottomNominal, FocusBottomMin)
    // A short window (its resting lyrics fold to one line) focuses into the
    // compact transport: PREVIOUS moves up and the progress takes its own row.
    val compactFocus = rest.lyricFrame == LyricFrame.OneLine && metrics.compactTransport != null
    val focusControls = if (compactFocus) {
        val compact = metrics.compactTransport!!
        NpGap(compact, compact)
    } else {
        controls
    }
    val focusLower = listOf(focusControls, hero, focusBottom)
    val focusUpper = listOf(coverGap)
    val focusNominal = nominalSum(focusLower) + nominalSum(focusUpper)
    val focusFixedMin = focusNominal - slackOf(focusLower) - slackOf(focusUpper)
    val focusListMin = metrics.lyric.cleanLines(FocusCleanLines)
    fun focusMargin(cover: Dp): Dp = (width - cover) / 2f

    // Equal margins: the top margin grows as the cover shrinks, m + c = (W + c) / 2.
    val equalCover = minOf(focusCoverCap, (height - focusFixedMin - focusListMin) * 2f - width)
        .coerceAtLeast(0.dp)
    // Where equal margins would leave focus nothing to show for it (a wide,
    // short window: the fold's inner screen), the top margin gives way first.
    val freeCover = minOf(focusCoverCap, (height - focusFixedMin - focusListMin - FocusTopMin).coerceAtLeast(0.dp))
    fun worthIt(cover: Dp): Boolean =
        coverFocusOffered(rest.cover, cover) || (compactFocus && cover >= rest.cover * CompactFocusCoverKeep)

    fun focusPose(cover: Dp): StageColumnPose {
        val top = minOf(focusMargin(cover), height - cover - focusFixedMin - focusListMin)
            .coerceAtLeast(FocusTopMin)
        val squeeze = squeezeGaps(
            (top + cover + focusNominal + focusListMin - height).coerceAtLeast(0.dp),
            focusLower,
            focusUpper,
        )
        val cg = coverGap.at(squeeze.upper)
        val ctl = focusControls.at(squeeze.lower)
        val hr = hero.at(squeeze.lower)
        val bottom = focusBottom.at(squeeze.lower)
        val lyric = (height - top - cover - cg - ctl - hr - bottom).coerceAtLeast(0.dp)
        return StageColumnPose(
            topBar = top, cover = cover, coverGap = cg, tabs = 0.dp, tabGap = 0.dp,
            lyricSlot = lyric, lyricFrame = LyricFrame.List,
            controls = ctl, hero = hr, accessory = bottom,
            lower = squeeze.lower, upper = squeeze.upper,
        )
    }

    // A short window's focus is worth the tap for the lyrics alone (one line
    // opens to three), so there the cover only has to keep (about) its
    // resting size.
    val focus = when {
        rest.cover <= 0.dp -> null
        // Equal margins win over cover size (owner 2026-11-04), down to
        // [EqualMarginCoverKeep] of the resting cover.
        // The column narrows to the cover, so the cover also has to leave the
        // transport its narrowest designed width.
        equalCover >= maxOf(rest.cover * EqualMarginCoverKeep, FocusMinColumn) -> focusPose(equalCover)
        worthIt(freeCover) -> focusPose(freeCover)
        else -> null
    }
    return StageColumnBudget(
        stageWidth = stageWidth,
        rest = rest,
        focus = focus,
        heroOneLine = heroCompact,
        focusStageWidth = focus?.let { maxOf(it.cover, FocusMinColumn) + ColumnPadding * 2 } ?: stageWidth,
    )
}

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
    /** Rating: 0 when it retreats (focus). Title: 0 when it folded into the top bar. */
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
    val frameTop = NpGap(WideFrameNominal, WideFrameMin)
    val barGap = NpGap(WideBarGapNominal, WideBarGapMin)
    val frameBottom = NpGap(WideFrameNominal, WideFrameMin)
    val coverToRating = NpGap(WideCoverToRatingNominal, WideCoverToRatingMin)
    val ratingToTransport = NpGap(WideRatingToTransportNominal, WideRatingToTransportMin)
    val transportToTitle = NpGap(WideTransportToTitleNominal, WideTransportToTitleMin)
    val lower = listOf(transportToTitle, frameBottom)
    val upper = listOf(frameTop, barGap, coverToRating, ratingToTransport)
    val fixedContent = barHeight + WideRatingRow + metrics.transport + metrics.pills + metrics.navBottom
    val gapsNominal = (lower + upper).fold(0.dp) { acc, g -> acc + g.nominal }
    val gapsSlack = slackOf(lower) + slackOf(upper)

    fun coverFor(title: Dp, squeezeAll: Boolean): Dp {
        val gaps = if (squeezeAll) gapsNominal - gapsSlack else gapsNominal
        return contentHeight - fixedContent - title - gaps
    }

    // Ladder: gaps → two-line title → one-line → cover shrinks → title to the top bar.
    val form: DualPaneTitleForm
    val title: Dp
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
            title = 0.dp
        }
    }
    val restCover = coverFor(title, squeezeAll = true)
        .coerceAtMost(DualPaneStackWidth)
        .coerceAtLeast(DualPaneCoverFloor)
    val restSqueeze = squeezeGaps(
        (fixedContent + title + gapsNominal + restCover - contentHeight).coerceAtLeast(0.dp),
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

    // Focus: the rating retreats, the inner gaps close, the cover grows
    // (≤ 1.5×, the lyrics keep 320) and the slot follows the cover. The title
    // stays as it rests (owner 2026-11-03: the title never changes).
    val focusHeight = contentHeight - barHeight - metrics.transport - metrics.pills - metrics.navBottom -
        frameTop.at(restSqueeze.upper) - barGap.at(restSqueeze.upper) - frameBottom.at(restSqueeze.lower) -
        WideCoverToRatingMin - WideRatingToTransportMin - restPose.transportToTitle - restPose.title
    val focusCover = minOf(
        restCover * CoverFocusMaxScale,
        rowWidth - WideColumnGapToken - WideRightColumnMinToken,
        focusHeight,
    )
    val focusPose = if (coverFocusOffered(restCover, focusCover)) {
        DualPanePose(
            slot = maxOf(DualPaneStackWidth, focusCover),
            cover = focusCover,
            coverToRating = WideCoverToRatingMin,
            ratingToTransport = WideRatingToTransportMin,
            transportToTitle = restPose.transportToTitle,
            rating = 0.dp,
            title = restPose.title,
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
@Immutable
data class LandscapeCoverBudget(val rest: Dp, val focus: Dp?)

/**
 * LandscapeNP: at rest the cover takes the height the rating row leaves,
 * capped so the right column keeps a phone's width; in focus the rating row
 * retreats and the cover takes the whole height while the right column
 * keeps [LandscapeFocusRightMin].
 */
fun resolveLandscapeCover(width: Dp, height: Dp): LandscapeCoverBudget {
    val rest = (height - LandscapeRatingRow - LandscapeRatingGap)
        .coerceAtMost(width * LandscapeCoverWidthShare)
        .coerceAtLeast(LandscapeCoverFloor)
    val focus = minOf(
        height,
        width - LandscapeColumnGap - LandscapeFocusRightMin,
        rest * CoverFocusMaxScale,
    )
    return LandscapeCoverBudget(rest = rest, focus = focus.takeIf { coverFocusOffered(rest, it) })
}

// ── Shared ──────────────────────────────────────────────────────────────

/**
 * The column's bottom edge: the system inset, but never less than
 * [ColumnBottomFloor]. Windows that report no navigation bar — a bubble, a
 * desktop window, a cover screen with the gesture hint hidden — otherwise
 * set the pills flush on the rounded window edge, which cuts them.
 */
fun stageColumnBottomInset(systemBottom: Dp): Dp = maxOf(systemBottom, ColumnBottomFloor)

/** Focus is worth a tap only when the cover grows by max(40dp, 12%). */
fun coverFocusOffered(rest: Dp, focus: Dp): Boolean =
    focus - rest >= maxOf(CoverFocusMinGrowth, rest * CoverFocusMinGrowthFraction)

/** How far each tier compresses (0..1) and what is still missing after both. */
@Immutable
data class Squeeze(val lower: Float, val upper: Float, val unmet: Dp)

/** Spend [deficit] from the LOWER gaps first, then the UPPER ones, each tier on one shared fraction. */
fun squeezeGaps(deficit: Dp, lower: List<NpGap>, upper: List<NpGap>): Squeeze {
    if (deficit <= 0.dp) return Squeeze(0f, 0f, 0.dp)
    val lowerSlack = slackOf(lower)
    val upperSlack = slackOf(upper)
    if (deficit <= lowerSlack) {
        return Squeeze(lower = if (lowerSlack > 0.dp) deficit / lowerSlack else 0f, upper = 0f, unmet = 0.dp)
    }
    val rest = deficit - lowerSlack
    return if (rest <= upperSlack) {
        Squeeze(lower = 1f, upper = if (upperSlack > 0.dp) rest / upperSlack else 0f, unmet = 0.dp)
    } else {
        Squeeze(lower = 1f, upper = 1f, unmet = rest - upperSlack)
    }
}

private fun slackOf(gaps: List<NpGap>): Dp = gaps.fold(0.dp) { acc, g -> acc + g.slack }

/** A content slot: the designed height, or content + [minAir] when the content is taller. */
private fun slot(designed: Dp, content: Dp, minAir: Dp): NpGap =
    NpGap(maxOf(designed, content + minAir), content + minAir)

// ── Tokens ──────────────────────────────────────────────────────────────

/** LyricsDisplay geometry (kept in step with LyricsDisplay.kt). */
internal val LyricsListPadding = 12.dp
internal val LyricsListLineGap = 2.dp
internal val LyricsFadeBottom = 24.dp
internal const val LyricsAnchorFraction = 0.22f

/** Phone / panel list: two clean lines; enlarged: three (= four visible). */
private const val PhoneCleanLines = 2
internal const val EnlargedCleanLines = 3

private val ColumnPadding = 24.dp
private val ColumnBottomFloor = 16.dp
private val RatingGap = 12.dp
private val PhoneRatingColumn = 56.dp
private val PhoneCoverMax = 312.dp
private val PhoneFocusCoverMax = 420.dp
private val CoverFloor = 96.dp
private val EnlargedCoverComfort = 168.dp

private val TopBarNominal = 56.dp
private val TopBarMin = 48.dp
private val CoverGapNominal = 16.dp
private val CoverGapMin = 8.dp
private val TabRowNominal = 30.dp
private val TabRowAir = 10.dp
private val EnlargedToolTabRow = 44.dp
private val TabGapNominal = 4.dp
private val ControlsSlotNominal = 148.dp
private val ControlsAirMin = 8.dp
private val HeroSlotNominal = 86.dp
private val HeroAirMin = 4.dp
private val HeroOneLineAir = 12.dp
private val AccessorySlotNominal = 68.dp
private val AccessoryAirMin = 8.dp
private val OneLineRowNominal = 44.dp
private val OneLineAirMin = 4.dp
private val LyricFrameHysteresis = 8.dp
private val FocusTopMin = 12.dp
private val FocusBottomNominal = 20.dp
private val FocusBottomMin = 8.dp

/** Focus keeps the current line and two more, clean of the bottom fade. */
internal const val FocusCleanLines = 3

/** A short window's focus may give up this little of the resting cover for its three lines. */
private const val CompactFocusCoverKeep = 0.9f

/** Focus keeps equal margins around the cover as long as it stays this much of its resting size. */
private const val EqualMarginCoverKeep = 0.7f

/** The narrowest content width the transport is designed for (a 320dp phone less its 24dp insets). */
private val FocusMinColumn = 272.dp

const val CoverFocusMaxScale = 1.5f
private val CoverFocusMinGrowth = 40.dp
private const val CoverFocusMinGrowthFraction = 0.12f

/** The DualPane left stack's measuring width (TabletNP: the 312dp cover side). */
val DualPaneStackWidth = 312.dp
private val DualPaneCoverMin = 168.dp
private val DualPaneCoverFloor = 140.dp
private val WideRatingRow = 48.dp
private val WideFrameNominal = 16.dp
private val WideFrameMin = 8.dp
private val WideBarGapNominal = 8.dp
private val WideBarGapMin = 4.dp
private val WideCoverToRatingNominal = 12.dp
private val WideCoverToRatingMin = 8.dp
private val WideRatingToTransportNominal = 24.dp
private val WideRatingToTransportMin = 12.dp
private val WideTransportToTitleNominal = 16.dp
private val WideTransportToTitleMin = 8.dp
private val WideColumnGapToken = 24.dp
private val WideRightColumnMinToken = 320.dp

private val LandscapeRatingRow = 48.dp
private val LandscapeRatingGap = 12.dp
private const val LandscapeCoverWidthShare = 0.42f
private val LandscapeCoverFloor = 120.dp
private val LandscapeColumnGap = 28.dp
private val LandscapeFocusRightMin = 360.dp
