package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBudgetTest {

    // Font scale 1.0 metrics (M3 line heights: labelLarge 20, titleMedium 24,
    // displaySmall 44, bodyLarge 24, bodyMedium 20).
    private val lyric = LyricWindowMetrics(activeLine = 32.dp, inactiveLine = 28.dp)
    private fun column(transport: Dp = 116.dp, pills: Dp = 44.dp) = StageColumnMetrics(
        tabText = 20.dp,
        transport = transport,
        hero = 70.dp,
        heroOneLine = 44.dp,
        pills = pills,
        oneLineContent = 32.dp,
        lyric = lyric,
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
        assertDp(364.dp, nn(budget.focus).cover)
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
        // Focus visibly grows (the old Immersive stayed at 288) and keeps the
        // current lyric line.
        val focus = nn(budget.focus)
        assertDp(368.dp, focus.cover)
        assertEquals(LyricFrame.Preview, focus.lyricFrame)
        assertTrue(focus.lyricSlot >= 36.dp - 0.5.dp)
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
        assertDp(524.dp, rest.cover)
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
                        assertTrue("focus dropped the lyric line at ${w}x$h", focus.lyricSlot >= 36.dp - 0.5.dp)
                    }
                    assertTrue("pills clipped at ${w}x$h", rest.accessory >= 44.dp)
                    if (rest.lyricFrame == LyricFrame.OneLine) {
                        assertTrue("one-line row clipped at ${w}x$h", rest.lyricSlot >= 36.dp - 0.5.dp)
                    }
                    budget.focus?.let { focus ->
                        assertTrue("focus overflow at ${w}x$h", focus.total <= h.dp + 0.5.dp)
                        assertTrue(
                            "focus too small at ${w}x$h",
                            coverFocusOffered(rest.cover, focus.cover),
                        )
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
        assertEquals(LyricFrame.Preview, phoneFocus.lyricFrame)
    }

    // ── DualPane ────────────────────────────────────────────────────────

    private fun dual(navBottom: Dp = 24.dp) = DualPaneMetrics(
        transport = 136.dp,
        titleOneLine = 24.dp + 2.dp + 36.dp,
        titleTwoLines = 24.dp + 2.dp + 72.dp,
        pills = 44.dp,
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
        assertEquals(DualPaneTitleForm.OneLine, budget.titleForm)
        assertEquals(DualPaneStackWidth, budget.rest.slot)
        assertTrue("cover ${budget.rest.cover}", budget.rest.cover >= 290.dp)
        val focus = nn(budget.focus)
        assertTrue(focus.cover >= budget.rest.cover + 40.dp)
        assertEquals(maxOf(DualPaneStackWidth, focus.cover), focus.slot)
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
            contentHeight = 560.dp,
            barHeight = 48.dp,
            metrics = dual(),
        )
        assertEquals(DualPaneTitleForm.TopBar, budget.titleForm)
        assertEquals(DualPaneStackWidth, budget.rest.slot)
    }

    // ── Landscape ───────────────────────────────────────────────────────

    @Test
    fun should_offerLandscapeFocus_when_phoneLandscape() {
        val cover = resolveLandscapeCover(width = 819.dp, height = 340.dp)
        assertDp(280.dp, cover.rest)
        assertDp(340.dp, nn(cover.focus))
    }

    @Test
    fun should_keepCoverPlain_when_narrowLandscape() {
        // 640×360: the right column can't spare the width.
        val cover = resolveLandscapeCover(width = 544.dp, height = 288.dp)
        assertNull(cover.focus)
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
}
