package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBudgetTest {

    // Font scale 1.0 metrics (M3 line heights: labelLarge 20, titleMedium 24,
    // displaySmall 44, bodyLarge 24, bodyMedium 20).
    private val lyric = LyricWindowMetrics(activeLine = 32.dp, inactiveLine = 28.dp)
    private val focusLyric = LyricWindowMetrics(
        activeLine = 32.dp * FocusLyricScale,
        inactiveLine = 28.dp * FocusLyricScale,
        anchorFraction = FocusLyricsAnchorFraction,
        linesAbove = FocusLinesAbove,
    )
    private fun column(transport: Dp = 116.dp, pills: Dp = 44.dp) = StageColumnMetrics(
        tabText = 20.dp,
        transport = transport,
        hero = 70.dp,
        heroOneLine = 44.dp,
        pills = pills,
        oneLineContent = 32.dp,
        lyric = lyric,
        // shortFocusControlsHeight(56): play row + 4 + the 36dp progress row.
        compactTransport = 96.dp,
    )

    private fun <T> nn(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun assertDp(expected: Dp, actual: Dp, tolerance: Float = 0.5f) {
        assertEquals(expected.value, actual.value, tolerance)
    }

    @Test
    fun should_measureCleanLinesFromLyricsDisplayGeometry() {
        assertDp(125.6.dp, lyric.cleanLines(2), 0.1f)
        assertDp(164.1.dp, lyric.cleanLines(3), 0.1f)
    }

    @Test
    fun should_keepTodaysPhoneLook_when_412x843() {
        val budget = resolveStageColumnBudget(412.dp, 843.dp, column())
        val rest = budget.rest
        assertEquals(LyricFrame.List, rest.lyricFrame)
        // W − 116: the row is exactly the drawn cover (no 4dp bands).
        assertDp(296.dp, rest.cover)
        assertEquals(0f, rest.lower)
        assertEquals(0f, rest.upper)
        assertDp(56.dp, rest.topBar)
        assertDp(16.dp, rest.coverGap)
        assertDp(30.dp, rest.tabs)
        assertDp(148.dp, rest.controls)
        assertDp(86.dp, rest.hero)
        assertDp(68.dp, rest.accessory)
        assertDp(843.dp, rest.total)
        // Clean focus: top bar and pills retreat, the cover grows to the
        // column and the lyric list stays; controls and title rest as they are.
        val focus = nn(budget.focus)
        assertDp(364.dp, focus.cover)
        assertEquals(LyricFrame.List, focus.lyricFrame)
        assertDp(0.dp, focus.tabs)
        // The larger focus lyrics take a little of the transport's air.
        assertTrue(focus.controls >= 116.dp + 8.dp - 0.5.dp)
        assertTrue(focus.lyricSlot >= focusLyric.cleanLines(3) - 0.5.dp)
        assertTrue(focus.topBar < rest.topBar)
        assertTrue(focus.accessory < rest.accessory)
        assertDp(843.dp, focus.total)
    }

    @Test
    fun should_fitWholeOneLineRow_when_ownerPanel420x684() {
        val budget = resolveStageColumnBudget(420.dp, 684.dp, column())
        val rest = budget.rest
        assertEquals(LyricFrame.OneLine, rest.lyricFrame)
        assertDp(304.dp, rest.cover)
        assertTrue("one-line row keeps its 32dp text", rest.lyricSlot >= 36.dp)
        assertDp(684.dp, rest.total)
        assertTrue(rest.lower > 0f)
        // 16:9: title and artist share one row.
        assertTrue(budget.heroOneLine)
        // Focus: the resting cover (centred by the screen), two clean
        // (larger) lyric lines, the transport folded to one row; the title
        // and artist keep their row while it shows and stack at ease.
        val focus = nn(budget.focus)
        assertDp(rest.cover, focus.cover)
        assertTrue(budget.compactFocus)
        assertTrue(budget.focusHeroStacked)
        assertDp(96.dp, focus.controls)
        assertEquals(LyricFrame.List, focus.lyricFrame)
        assertTrue(focus.lyricSlot >= focusLyric.cleanLines(2) - 0.5.dp)
        assertDp(420.dp, budget.focusStageWidth)
        assertDp(684.dp, focus.total)
    }

    @Test
    fun should_keepCoverBySqueezingGapsAndHero_when_412x628() {
        val budget = resolveStageColumnBudget(412.dp, 628.dp, column())
        val rest = budget.rest
        assertEquals(LyricFrame.OneLine, rest.lyricFrame)
        assertTrue(budget.heroOneLine)
        // Lower gaps all spent, upper only partly: the cover keeps its cap.
        assertEquals(1f, rest.lower)
        assertTrue(rest.upper in 0.01f..0.99f)
        assertDp(296.dp, rest.cover)
        assertDp(628.dp, rest.total)
    }

    @Test
    fun should_shrinkCoverOnlyAfterEveryGap_when_412x560() {
        val rest = resolveStageColumnBudget(412.dp, 560.dp, column()).rest
        assertEquals(1f, rest.lower)
        assertEquals(1f, rest.upper)
        assertTrue(rest.cover < 296.dp)
        assertDp(560.dp, rest.total)
        assertDp(36.dp, rest.lyricSlot)
    }

    @Test
    fun should_foldHeroBeforeLyrics_when_twoLinesAreOnlyAHeroAway() {
        // Find a height where the stacked hero can't keep two clean lines but
        // a one-row hero can: the list stays, the hero folds.
        val folded = (600..900).map { it.dp }.firstOrNull { h ->
            val b = resolveStageColumnBudget(412.dp, h, column())
            b.rest.lyricFrame == LyricFrame.List && b.heroOneLine
        }
        assertNotNull(folded)
    }

    @Test
    fun should_spendGapsToKeepTwoCleanLines_when_360x732() {
        val rest = resolveStageColumnBudget(360.dp, 732.dp, column(transport = 136.dp)).rest
        assertEquals(LyricFrame.List, rest.lyricFrame)
        assertDp(244.dp, rest.cover)
        assertTrue(rest.lyricSlot >= lyric.cleanLines(2) - 0.5.dp)
        assertTrue(rest.lower > 0f)
    }

    @Test
    fun should_holdFourVisibleLines_when_enlargedFold690x790() {
        val spec = nowPlayingEnlargedSpec(690.dp)
        val budget = resolveStageColumnBudget(690.dp, 790.dp, column(), enlarged = spec)
        val rest = budget.rest
        assertEquals(LyricFrame.List, rest.lyricFrame)
        assertTrue(rest.lyricSlot >= lyric.cleanLines(3) - 0.5.dp)
        assertTrue(rest.cover >= 168.dp)
        assertDp(790.dp, rest.total)
    }

    @Test
    fun should_keepEnlargedTabletUnchanged_when_800x1212() {
        val spec = nowPlayingEnlargedSpec(800.dp)
        val rest = resolveStageColumnBudget(800.dp, 1212.dp, column(transport = 124.dp), enlarged = spec).rest
        // The 640dp column less its margins, the 12dp gap and the 56dp rating column (owner 2026-11-27:
        // no longer the 104dp one, so the cover takes the 48dp back).
        assertDp(572.dp, rest.cover)
        assertEquals(0f, rest.lower)
        assertEquals(0f, rest.upper)
    }

    @Test
    fun should_notFlapLyricFrame_when_heightHoversAtTheGate() {
        // Find the smallest height that still gets the list, then step 4dp
        // below/above it: once folded it needs the hysteresis to unfold.
        var gate = 600.dp
        while (resolveStageColumnBudget(412.dp, gate, column()).rest.lyricFrame != LyricFrame.List) gate += 1.dp
        val folded = resolveStageColumnBudget(412.dp, gate + 4.dp, column(), previous = LyricFrame.OneLine)
        assertEquals(LyricFrame.OneLine, folded.rest.lyricFrame)
        val unfolded = resolveStageColumnBudget(412.dp, gate + 9.dp, column(), previous = LyricFrame.OneLine)
        assertEquals(LyricFrame.List, unfolded.rest.lyricFrame)
    }

    @Test
    fun should_neverOverflow_forWindowSweep() {
        for (w in 320..1000 step 24) {
            for (h in 420..1400 step 16) {
                for (enlarged in listOf(false, true)) {
                    val spec = if (enlarged && w >= 600) nowPlayingEnlargedSpec(w.dp) else null
                    val budget = resolveStageColumnBudget(w.dp, h.dp, column(), enlarged = spec)
                    val rest = budget.rest
                    assertTrue("rest overflow at ${w}x$h", rest.total <= h.dp + 0.5.dp)
                    assertTrue("controls clipped at ${w}x$h", rest.controls >= 116.dp)
                    val heroMin = if (budget.heroOneLine) 48.dp else 74.dp
                    assertTrue("hero clipped at ${w}x$h", rest.hero >= heroMin - 0.5.dp)
                    budget.focus?.let { focus ->
                        assertTrue(
                            "focus lost its lines at ${w}x$h",
                            focus.lyricSlot >= focusLyric.cleanLines(
                                if (rest.lyricFrame == LyricFrame.OneLine) 1 else 2,
                            ) - 0.5.dp,
                        )
                    }
                    assertTrue("pills clipped at ${w}x$h", rest.accessory >= 44.dp)
                    if (rest.lyricFrame == LyricFrame.OneLine) {
                        assertTrue("one-line row clipped at ${w}x$h", rest.lyricSlot >= 36.dp - 0.5.dp)
                    }
                    budget.focus?.let { focus ->
                        assertTrue("focus overflow at ${w}x$h", focus.total <= h.dp + 0.5.dp)
                        assertTrue("focus too small at ${w}x$h", focus.cover >= rest.cover * 0.7f - 0.5.dp)
                        if (budget.compactFocus) {
                            assertDp(budget.stageWidth, budget.focusStageWidth)
                            assertDp(budget.stageWidth, focus.cover + 48.dp + budget.focusLyricInset * 2f)
                        } else {
                            assertDp(focus.cover + 48.dp, budget.focusStageWidth)
                        }
                        assertTrue("focus column too wide at ${w}x$h", budget.focusStageWidth <= w.dp + 0.5.dp)
                    }
                }
            }
        }
    }

    @Test
    fun should_offerFocusOnlyWhenTheCoverVisiblyGrows() {
        assertTrue(coverFocusOffered(296.dp, 364.dp))
        // +36 on a 286 cover is under max(40, 12%).
        assertTrue(!coverFocusOffered(286.dp, 322.dp))
    }

    @Test
    fun should_keepLyricListInFocus_when_tabletPortraitPanel() {
        val focus = nn(resolveStageColumnBudget(400.dp, 1212.dp, column(transport = 136.dp)).focus)
        assertEquals(LyricFrame.List, focus.lyricFrame)
        val phoneFocus = nn(resolveStageColumnBudget(412.dp, 843.dp, column()).focus)
        assertEquals(LyricFrame.List, phoneFocus.lyricFrame)
    }

    @Test
    fun should_keepTheTransportSlot_when_focusNarrowsTheColumn() {
        // Owner 2026-11-27: the single column's time labels sit under the wave at
        // every width (their row is part of the transport metric), so focus
        // narrowing the column below 360dp no longer grows the transport slot.
        val budget = resolveStageColumnBudget(475.dp, 800.dp, column())
        val focus = nn(budget.focus)
        assertTrue(focus.cover < 360.dp)
        // No label row joins the slot: at most the resting one (focus may spend its air).
        assertTrue("focus ${focus.controls} over rest ${budget.rest.controls}", focus.controls <= budget.rest.controls + 0.5.dp)
        assertTrue(focus.controls >= 116.dp)
        assertDp(800.dp, focus.total)
    }

    @Test
    fun should_keepTheRestingOrderAndCover_when_shortWindowFocuses() {
        // Owner 2026-11-05 (prototype v3): the Fold 8 outer screen and 16:9
        // keep the cover's resting size, centred; the transport folds to one
        // row and keeps the full width with the title; the lyrics line up
        // with the cover and run on 2–3 dotted lines under the transport.
        for ((w, h) in listOf(475 to 690, 420 to 684, 360 to 592)) {
            val budget = resolveStageColumnBudget(w.dp, h.dp, column())
            assertEquals(LyricFrame.OneLine, budget.rest.lyricFrame)
            val focus = nn(budget.focus)
            assertTrue(budget.compactFocus)
            assertDp(budget.rest.cover, focus.cover)
            assertDp(16.dp, focus.topBar)
            assertDp(96.dp, focus.controls)
            // The sung line above, the current line, and the next where it
            // fits beside the resting cover; the hero keeps its row while the
            // transport shows (owner 2026-11-05) and stacks at ease.
            assertTrue(budget.focusHeroStacked)
            val lines = if (h >= 684) 2 else 1
            assertTrue(focus.lyricSlot >= focusLyric.cleanLines(lines) - 0.5.dp)
            assertDp(w.dp, budget.focusStageWidth)
            assertDp((w.dp - 48.dp - focus.cover) / 2f, budget.focusLyricInset)
            assertTrue(budget.focusLyricOverflow >= focusLyric.inactiveLine * 2f)
            // Faded out by the progress row's bottom: never under the title.
            assertDp(focus.controls, budget.focusLyricOverflow)
            assertDp(h.dp, focus.total)
        }
        // Shorter still: the folded transport frees enough that the cover
        // still keeps its size.
        for (h in listOf(480, 520)) {
            val tight = resolveStageColumnBudget(360.dp, h.dp, column())
            val tightFocus = nn(tight.focus)
            assertDp(tight.rest.cover, tightFocus.cover)
            assertDp(h.dp, tightFocus.total)
        }
        // A 16:9 phone with tall bars (360x564 on device, real metrics a little
        // taller): the current line alone rather than no focus.
        val tallBars = column(transport = 140.dp)
        for (h in listOf(540, 564)) {
            val b = resolveStageColumnBudget(360.dp, h.dp, tallBars)
            val f = nn(b.focus)
            assertDp(b.rest.cover, f.cover)
            assertTrue(f.lyricSlot >= focusLyric.cleanLines(1) - 0.5.dp)
            assertDp(h.dp, f.total)
        }
        // Tall phones keep the regular focus.
        assertTrue(!resolveStageColumnBudget(412.dp, 843.dp, column()).compactFocus)
    }

    @Test
    fun should_keepOneSungLineAboveTheCurrent_when_focused() {
        // Owner 2026-11-05: the line just sung shows whole above the current
        // one; the current line itself is anchored right under it.
        val noneAbove = focusLyric.copy(linesAbove = 0)
        assertDp(
            noneAbove.cleanLines(2) + focusLyric.inactiveLine + 2.dp,
            focusLyric.cleanLines(2),
        )
    }

    @Test
    fun should_keepThreeLinesAndBothTransportRows_when_tallPhoneFocuses() {
        val budget = resolveStageColumnBudget(412.dp, 843.dp, column())
        val focus = nn(budget.focus)
        assertTrue(focus.controls >= 116.dp)
        assertTrue(focus.lyricSlot >= focusLyric.cleanLines(3) - 0.5.dp)
        // Equal margins: the column is the cover plus its 24dp insets.
        assertDp((412.dp - focus.cover) / 2f, focus.topBar)
    }

    // ── DualPane ────────────────────────────────────────────────────────

    private fun dual(navBottom: Dp = 24.dp) = DualPaneMetrics(
        transport = 136.dp,
        titleOneLine = 24.dp + 2.dp + 36.dp,
        titleTwoLines = 24.dp + 2.dp + 72.dp,
        navBottom = navBottom,
    )

    @Test
    fun should_measureStackAt312AndKeepCoverLarge_when_owner1130x753() {
        // 753 − 45dp status bar; 1130 − 48 frame.
        val budget = resolveDualPaneBudget(
            rowWidth = 1082.dp,
            contentHeight = 708.dp,
            barHeight = 48.dp,
            metrics = dual(),
        )
        // Two lines would leave the cover no room to focus: one line it is.
        assertEquals(DualPaneTitleForm.OneLine, budget.titleForm)
        assertTrue("cover ${budget.rest.cover}", budget.rest.cover >= 290.dp)
        // The rating column stands at the cover's right; the stack spans both.
        assertDp(DualPaneRatingSide, budget.rest.ratingSide)
        assertDp(budget.rest.cover + DualPaneRatingSide, budget.rest.slot)
        val focus = nn(budget.focus)
        assertTrue(focus.cover >= budget.rest.cover + 40.dp)
        assertEquals(maxOf(DualPaneStackWidth, focus.cover), focus.slot)
        // The title stays in focus, exactly as it rests; only the rating retreats.
        assertDp(budget.rest.title, focus.title)
        assertDp(0.dp, focus.ratingSide)
    }

    @Test
    fun should_reach312WithTwoLineTitle_when_tallWindow() {
        val budget = resolveDualPaneBudget(
            rowWidth = 1392.dp,
            contentHeight = 876.dp,
            barHeight = 48.dp,
            metrics = dual(),
        )
        assertEquals(DualPaneTitleForm.TwoLines, budget.titleForm)
        assertDp(312.dp, budget.rest.cover)
        assertDp(16.dp, budget.frameTop)
    }

    @Test
    fun should_foldTitleIntoTopBar_when_veryShort() {
        val budget = resolveDualPaneBudget(
            rowWidth = 852.dp,
            contentHeight = 420.dp,
            barHeight = 48.dp,
            metrics = dual(),
        )
        assertEquals(DualPaneTitleForm.TopBar, budget.titleForm)
        // A small cover still keeps the controls at the measuring width.
        assertEquals(DualPaneStackWidth, budget.rest.slot)
    }

    // ── Dense two columns (phone landscape, PR 3) ────────────────────────

    // A 28dp title row + 4 + the 36dp progress row + 4 + 48dp keys; at ease the keys fold away.
    private val denseControls = 28.dp + 4.dp + 36.dp + 4.dp + 48.dp
    private val denseAtEase = 28.dp + 4.dp + 36.dp

    @Test
    fun should_shareOneRule_when_landscapeAtEveryWidth() {
        // Owner 2026-11-27: no 792dp cliff. The four phones on their side (frame less the 16dp
        // sides, 8dp top / bottom and the bars) all lay out the same way: cover + rating column
        // over the controls on the left, the page on the right.
        val windows = listOf(883.dp to 356.dp, 819.dp to 337.dp, 708.dp to 304.dp, 536.dp to 264.dp)
        for ((width, height) in windows) {
            val dense = resolveDenseTwoColumn(width, height, denseControls, denseAtEase)
            // The controls always fit their column…
            assertTrue("$width: left ${dense.leftWidth}", dense.leftWidth >= DenseTransportMin - 0.5.dp)
            // …the cover and its rating column fit beside each other in it…
            assertTrue(dense.restCover + DualPaneRatingSide <= dense.leftWidth + 0.5.dp)
            // …the cover sits above the controls inside the height…
            assertTrue(dense.restCover + 8.dp + denseControls <= height + 0.5.dp)
            // …and the page keeps its room.
            assertTrue("$width: page ${width - 20.dp - dense.leftWidth}", width - 20.dp - dense.leftWidth >= 200.dp - 0.5.dp)
        }
    }

    @Test
    fun should_growTheCover_when_focusLeftAtEase() {
        val dense = resolveDenseTwoColumn(883.dp, 356.dp, denseControls, denseAtEase)
        // Height-led: the cover takes what the controls leave, the rating column at its side.
        assertDp(356.dp - 8.dp - denseControls, dense.restCover)
        assertDp(dense.restCover + DualPaneRatingSide, dense.leftWidth)
        // Focus: the rating steps away, the cover keeps the height; at ease the keys fold away
        // and the cover grows into their row (never past the column).
        assertTrue(dense.focusCover >= dense.restCover)
        assertTrue("ease ${dense.easeCover}", dense.easeCover > dense.focusCover)
        assertTrue(dense.easeCover <= dense.leftWidth + 0.5.dp)
    }

    @Test
    fun should_keepTheControlsWhole_when_theCoverIsSmall() {
        // 568 with side navigation: the cover alone would leave the controls a ~200dp column.
        val dense = resolveDenseTwoColumn(488.dp, 264.dp, denseControls, denseAtEase)
        assertDp(DenseTransportMin, dense.leftWidth)
        assertTrue(dense.restCover + DualPaneRatingSide <= dense.leftWidth)
        assertTrue(488.dp - 20.dp - dense.leftWidth >= 196.dp)
    }

    @Test
    fun should_squeezeLowerTierFirst() {
        val lower = listOf(NpGap(32.dp, 8.dp))
        val upper = listOf(NpGap(16.dp, 8.dp))
        assertEquals(Squeeze(0.5f, 0f, 0.dp), squeezeGaps(12.dp, lower, upper))
        assertEquals(Squeeze(1f, 0.5f, 0.dp), squeezeGaps(28.dp, lower, upper))
        val over = squeezeGaps(40.dp, lower, upper)
        assertEquals(1f, over.upper)
        assertDp(8.dp, over.unmet)
    }

    @Test
    fun should_keepPillsOffTheEdge_when_windowReportsNoNavigationBar() {
        // Bubble / desktop window / cover screen with the gesture hint hidden.
        assertDp(16.dp, stageColumnBottomInset(0.dp))
        assertDp(16.dp, stageColumnBottomInset(10.dp))
    }

    @Test
    fun should_keepSystemInset_when_navigationBarIsTallerThanTheFloor() {
        // Gesture handle (24dp) and three-button bar (48dp) are unchanged.
        assertDp(24.dp, stageColumnBottomInset(24.dp))
        assertDp(48.dp, stageColumnBottomInset(48.dp))
    }

    @Test
    fun should_evenTheGapsOverTheCoverAndUnderTheTitle_when_focusOnA32Tablet() {
        // Owner 2026-11-14: on a 3:2 tablet the equal side/top margin hung the
        // focus stage low. The gaps over the cover and under the title match now.
        val spec = nowPlayingEnlargedSpec(800.dp)
        val focus = nn(resolveStageColumnBudget(800.dp, 1120.dp, column(transport = 124.dp), enlarged = spec).focus)
        assertDp(focus.topBar, focus.accessory)
        assertDp(1120.dp, focus.total)
    }

    @Test
    fun should_keepTheCoverAndLyrics_when_focusGapsAreEvened() {
        val spec = nowPlayingEnlargedSpec(800.dp)
        val focus = nn(resolveStageColumnBudget(800.dp, 1120.dp, column(transport = 124.dp), enlarged = spec).focus)
        // The equal side margin still picks the cover; only the vertical gaps move.
        assertTrue(focus.cover > 0.dp)
        assertTrue(focus.lyricSlot >= 0.dp)
        assertTrue(focus.topBar < (800.dp - focus.cover) / 2f)
    }
}
