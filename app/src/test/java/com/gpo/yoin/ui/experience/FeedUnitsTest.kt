package com.gpo.yoin.ui.experience

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedUnitsTest {

    /** A tall window (portrait / landscape tablet height) — no column is ever a landscape phone. */
    private val tallWideWindow = previewYoinWindowInfo(widthDp = 1280, heightDp = 800)

    /** N of a [width]-wide container in a tall window, fractional widths allowed. */
    private fun unitsAt(width: Float): Int = tallWideWindow.forPaneWidth(width.dp).feedUnits

    private fun assertUnits(expected: Int, vararg widths: Int) {
        widths.forEach { w ->
            assertEquals("window $w", expected, previewYoinWindowInfo(widthDp = w, heightDp = 1000).feedUnits)
            assertEquals("column $w", expected, unitsAt(w.toFloat()))
        }
    }

    @Test
    fun should_returnTwo_when_phonePortraitWidths() {
        assertUnits(2, 360, 393, 411, 430, 479)
    }

    @Test
    fun should_returnOne_when_containerBelow342() {
        assertUnits(1, 330, 341)
        assertUnits(2, 342)
    }

    @Test
    fun should_returnThreeOrFour_when_compactAtLeast480() {
        assertUnits(3, 480, 560, 581)
        assertUnits(4, 582, 599)
        listOf(480, 581, 582, 599).forEach { w ->
            assertEquals(LayoutMode.Compact, previewYoinWindowInfo(widthDp = w, heightDp = 1000).layoutMode)
        }
    }

    @Test
    fun should_returnFour_when_mediumOrTabletop() {
        assertUnits(4, 600, 690, 709, 753, 800, 839)
        // C = 688 is the Capped frame's widest content; the clamp holds N at 4
        // even where ⌊(C + 10) / 140⌋ would give 5.
        assertEquals(4, feedUnitsFor(688.dp, LayoutMode.Medium))
        assertEquals(4, feedUnitsFor(688.dp, LayoutMode.Tabletop))
        assertEquals(4, feedUnitsFor(1000.dp, LayoutMode.Medium))
        assertEquals(4, feedUnitsFor(1000.dp, LayoutMode.Tabletop))
        val tabletop = YoinWindowInfo(
            layoutMode = LayoutMode.Tabletop,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
        )
        listOf(600f, 800f, 1280f).forEach { w ->
            assertEquals("tabletop $w", 4, tabletop.forPaneWidth(w.dp).feedUnits)
        }
    }

    @Test
    fun should_scaleFiveToEight_when_wide() {
        assertUnits(5, 840, 860, 893)
        assertUnits(6, 894, 1000, 1033)
        assertUnits(7, 1034, 1129, 1173)
        assertUnits(8, 1174, 1280, 1440, 2000)
    }

    @Test
    fun should_neverDecrease_when_widthSweeps() {
        var previous = unitsAt(320f)
        var w = 320f
        while (w <= 2000f) {
            val units = unitsAt(w)
            assertTrue("width $w: $units < $previous", units >= previous)
            assertTrue("width $w: $units out of 1..8", units in 1..8)
            previous = units
            w += 0.5f
        }
        assertEquals(8, previous)
    }

    @Test
    fun should_followContainer_when_forPaneWidth() {
        assertEquals(8, tallWideWindow.feedUnits)
        assertEquals(4, unitsAt(600f))
        assertEquals(5, unitsAt(860f))
        assertEquals(2, unitsAt(376f))
        assertEquals(1, unitsAt(330f))
        // A float-sum column an ulp short of a breakpoint keeps its tier.
        val almost600 = tallWideWindow.forPaneWidth(599.995f.dp)
        assertEquals(LayoutMode.Medium, almost600.layoutMode)
        assertEquals(4, almost600.feedUnits)
        assertEquals(4, unitsAt(581.995f))
        assertEquals(6, unitsAt(893.995f))
        assertEquals(8, unitsAt(1173.995f))
        // A Tabletop window keeps its posture whatever the column.
        val tabletop = YoinWindowInfo(
            layoutMode = LayoutMode.Tabletop,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
        )
        val column = tabletop.forPaneWidth(690.dp)
        assertEquals(LayoutMode.Tabletop, column.layoutMode)
        assertEquals(4, column.feedUnits)
    }

    @Test
    fun should_defaultFromLayoutMode_when_constructedDirectly() {
        val expected = mapOf(
            LayoutMode.Compact to 2,
            LayoutMode.Medium to 4,
            LayoutMode.Tabletop to 4,
            LayoutMode.Wide to 8,
        )
        expected.forEach { (mode, units) ->
            val info = YoinWindowInfo(
                layoutMode = mode,
                isWidthAtLeastMedium = mode != LayoutMode.Compact,
                isHeightAtLeastMedium = true,
                hingeBounds = null,
            )
            assertEquals(mode.name, units, info.feedUnits)
            assertEquals(mode.name, units, representativeFeedUnits(mode))
        }
    }

    @Test
    fun should_matchTodayMargins_when_atRest() {
        val expectedStart = mapOf(
            411 to 16f,
            600 to 16f,
            690 to 16f,
            753 to 32.5f,
            800 to 56f,
            839 to 75.5f,
            860 to 32f,
            1129 to 32f,
            1280 to 32f,
        )
        expectedStart.forEach { (w, start) ->
            val frame = feedFrameClass(previewYoinWindowInfo(widthDp = w, heightDp = 1000))
            val insets = feedFrameInsets(w.dp, frame)
            assertEquals("start $w", start, insets.start.value, 0.01f)
            // Symmetric at rest: today's centred column.
            assertEquals("end $w", start, insets.end.value, 0.01f)
            // The margins leave exactly the content width N is computed from.
            assertEquals(
                "content $w",
                feedContentWidth(w.dp, frame).value,
                (w.dp - insets.start - insets.end).value,
                0.01f,
            )
        }
        val landscape = previewYoinWindowInfo(widthDp = 914, heightDp = 411)
        assertEquals(FeedFrameClass.Landscape, feedFrameClass(landscape))
        val landscapeInsets = feedFrameInsets(914.dp, FeedFrameClass.Landscape)
        assertEquals(24f, landscapeInsets.start.value, 0.01f)
        assertEquals(24f, landscapeInsets.end.value, 0.01f)
        assertEquals(866f, feedContentWidth(914.dp, FeedFrameClass.Landscape).value, 0.01f)
    }

    @Test
    fun should_startAlignCappedFrame_when_widerThanRest() {
        val insets = feedFrameInsets(1280.dp, FeedFrameClass.Capped)
        assertEquals(76f, insets.start.value, 0.01f)
        assertEquals(688f, (1280.dp - insets.start - insets.end).value, 0.01f)
        assertEquals(516f, insets.end.value, 0.01f)
        assertEquals(688f, feedContentWidth(1280.dp, FeedFrameClass.Capped).value, 0.01f)
    }

    @Test
    fun should_keepContentWidthMonotonic_when_crossing840() {
        fun contentAt(width: Float): Float {
            val info = tallWideWindow.forPaneWidth(width.dp)
            val frame = feedFrameClass(info)
            val insets = feedFrameInsets(width.dp, frame)
            val content = width - insets.start.value - insets.end.value
            assertEquals("width $width", feedContentWidth(width.dp, frame).value, content, 0.01f)
            return content
        }
        assertEquals(688f, contentAt(839f), 0.01f)
        assertEquals(776f, contentAt(840f), 0.01f)
        var previous = contentAt(320f)
        var w = 320f
        while (w <= 2000f) {
            val content = contentAt(w)
            assertTrue("width $w: $content < $previous", content >= previous - 0.01f)
            previous = content
            w += 0.5f
        }
    }

    @Test
    fun should_centreTabletopFrame_when_restingWiderThan840() {
        val tabletop = YoinWindowInfo(
            layoutMode = LayoutMode.Tabletop,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
        )
        assertEquals(FeedFrameClass.CappedCentred, feedFrameClass(tabletop))
        val insets = feedFrameInsets(1000.dp, FeedFrameClass.CappedCentred)
        assertEquals(insets.start.value, insets.end.value, 0.01f)
        assertEquals(156f, insets.start.value, 0.01f)
        // At the Capped resting widths it is the same frame as Capped.
        for (w in listOf(411, 690, 753, 800)) {
            assertEquals(feedFrameInsets(w.dp, FeedFrameClass.Capped), feedFrameInsets(w.dp, FeedFrameClass.CappedCentred))
        }
    }

    // ---- Cover columns (Jump Back In) ----

    private fun coverColumnsAt(width: Int): Int = previewYoinWindowInfo(widthDp = width, heightDp = 1000).feedCoverColumns

    @Test
    fun should_seatPhoneSizedColumns_when_deviceWidths() {
        assertEquals(3, coverColumnsAt(480))
        assertEquals(4, coverColumnsAt(600))
        assertEquals(5, coverColumnsAt(690)) // foldable inner display
        assertEquals(5, coverColumnsAt(800)) // tablet portrait
        assertEquals(6, coverColumnsAt(860)) // landscape tablet beside the NP panel
        assertEquals(8, coverColumnsAt(1129))
        assertEquals(9, coverColumnsAt(1280)) // landscape tablet
        assertEquals(10, coverColumnsAt(1440))
        assertEquals(10, coverColumnsAt(2000))
    }

    @Test
    fun should_keepCoversPhoneSized_when_anyTemplatedWidth() {
        // Every container a template can see: the column stays within the
        // phone's ~112dp up to ~165dp at the narrowest step, and the cover
        // never leaves 100–138dp (the owner's "too big" was 130–160 on 4 / 6 columns).
        for (w in 480..1600) {
            val info = previewYoinWindowInfo(widthDp = w, heightDp = 1000)
            if (info.feedUnits < 3) continue
            val k = info.feedCoverColumns
            val content = feedContentWidth(w.dp, feedFrameClass(info)).value
            val column = (content - 12f * (k - 1)) / k
            assertTrue("$w: column $column", column >= 112f - 0.01f)
            if (k > 3 && k < 10) assertTrue("$w: column $column", column < 112f + 124f / k + 0.01f)
        }
    }

    @Test
    fun should_matchWindowAndColumn_when_coverColumns() {
        listOf(600, 690, 800, 860, 1129, 1280).forEach { w ->
            assertEquals("$w", coverColumnsAt(w), tallWideWindow.forPaneWidth(w.dp).feedCoverColumns)
        }
        assertEquals(9, tallWideWindow.feedCoverColumns)
    }

    @Test
    fun should_crossCoverColumnBreakpoint_when_floatSumUlpBelow() {
        // C = 484 is the 4-column line (Compact container 516).
        assertEquals(4, feedCoverColumnsFor((484f - 0.001f).dp))
        assertEquals(3, feedCoverColumnsFor(483.9f.dp))
    }
}
