package com.gpo.yoin.ui.experience

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellChromeFormTest {

    @Test
    fun should_readEdgeSplit_when_heightIsCompact_whateverTheWidth() {
        // 844 × 390 and 780 × 390 handsets both read EdgeSplit (§14.6).
        assertEquals(
            ShellChromeForm.EdgeSplit,
            resolveShellChromeForm(isTabletop = false, widthAtLeastMedium = true, heightAtLeastMedium = false),
        )
        assertEquals(
            ShellChromeForm.EdgeSplit,
            resolveShellChromeForm(isTabletop = false, widthAtLeastMedium = false, heightAtLeastMedium = false),
        )
    }

    @Test
    fun should_keepPortraitBar_when_compactWidthAndTallEnough() {
        assertEquals(
            ShellChromeForm.PortraitBar,
            resolveShellChromeForm(isTabletop = false, widthAtLeastMedium = false, heightAtLeastMedium = true),
        )
    }

    @Test
    fun should_centreTheBar_when_mediumWidthAndTallEnough() {
        assertEquals(
            ShellChromeForm.CenteredBar,
            resolveShellChromeForm(isTabletop = false, widthAtLeastMedium = true, heightAtLeastMedium = true),
        )
    }

    @Test
    fun should_keepPortraitBar_when_tabletop() {
        assertEquals(
            ShellChromeForm.PortraitBar,
            resolveShellChromeForm(isTabletop = true, widthAtLeastMedium = true, heightAtLeastMedium = false),
        )
    }

    @Test
    fun should_renderDualPaneNowPlaying_only_when_wideAndTall() {
        fun info(mode: LayoutMode, tall: Boolean) = YoinWindowInfo(
            layoutMode = mode,
            isWidthAtLeastMedium = mode != LayoutMode.Compact,
            isHeightAtLeastMedium = tall,
            hingeBounds = null,
        )
        assertTrue(info(LayoutMode.Wide, tall = true).isDualPaneNowPlaying)
        assertFalse(info(LayoutMode.Wide, tall = false).isDualPaneNowPlaying)
        // Medium became the panel → enlarged-phone pair (§3.4).
        assertFalse(info(LayoutMode.Medium, tall = true).isDualPaneNowPlaying)
        assertFalse(info(LayoutMode.Tabletop, tall = true).isDualPaneNowPlaying)
    }

    @Test
    fun should_splitAroundACentredLeftCutout_when_matchingTheBoard() {
        // LandscapeHome: 390 tall, cutout 176..212 → two 154dp capsules.
        val segments = computeEdgeSplitSegments(
            windowHeight = 390.dp,
            topInset = 0.dp,
            bottomInset = 0.dp,
            leftCutoutTop = 176.dp,
            leftCutoutBottom = 212.dp,
        )
        assertEquals(14.dp, segments.upperTop)
        assertEquals(168.dp, segments.upperBottom)
        assertEquals(220.dp, segments.lowerTop)
        assertEquals(374.dp, segments.lowerBottom)
        assertEquals(154.dp, segments.upperHeight)
        assertEquals(154.dp, segments.lowerHeight)
        assertFalse(segments.roomy)
    }

    @Test
    fun should_closeTheGapAndKeepTheLowerCapsule_when_noLeftCutout() {
        // AlbumLandscapeRoomy: lower capsule unmoved, upper grows to 198 (+ Shuffle).
        val segments = computeEdgeSplitSegments(
            windowHeight = 390.dp,
            topInset = 0.dp,
            bottomInset = 0.dp,
            leftCutoutTop = null,
            leftCutoutBottom = null,
        )
        assertEquals(220.dp, segments.lowerTop)
        assertEquals(212.dp, segments.upperBottom)
        assertEquals(198.dp, segments.upperHeight)
        assertTrue(segments.roomy)
    }

    @Test
    fun should_keepClearOfACornerCutout_when_itLeavesNoRoomBelowIt() {
        val segments = computeEdgeSplitSegments(
            windowHeight = 411.dp,
            topInset = 24.dp,
            bottomInset = 0.dp,
            leftCutoutTop = 360.dp,
            leftCutoutBottom = 411.dp,
        )
        assertTrue(segments.lowerBottom <= 352.dp)
        assertTrue(segments.lowerHeight > 0.dp)
        assertTrue(segments.upperHeight > 0.dp)
        assertTrue(segments.roomy)
    }

    @Test
    fun should_startBelowTheStatusBar_when_itIsTallerThanTheMargin() {
        val segments = computeEdgeSplitSegments(
            windowHeight = 411.dp,
            topInset = 28.dp,
            bottomInset = 0.dp,
            leftCutoutTop = 187.dp,
            leftCutoutBottom = 223.dp,
        )
        assertEquals(32.dp, segments.upperTop)
    }
}
