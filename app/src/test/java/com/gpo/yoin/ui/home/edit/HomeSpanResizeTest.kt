package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.homeRowCellWidths
import com.gpo.yoin.ui.home.homeSpanInfoOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resize handle's horizontal axis in two columns (large-screen Home, stage 1). */
class HomeSpanResizeTest {

    private val a = HomeSection.Activities
    private val j = HomeSection.JumpBackIn
    private val m = HomeSection.MemoryGrid

    @Test
    fun should_lockToFirstDirection_when_pastSlop() {
        assertNull(homeHandleAxis(3f, 2f, slopPx = 8f, horizontal = true))
        assertEquals(HomeHandleAxis.Horizontal, homeHandleAxis(-12f, 4f, 8f, true))
        assertEquals(HomeHandleAxis.Vertical, homeHandleAxis(4f, 12f, 8f, true))
        // Phones (one column): vertical only.
        assertEquals(HomeHandleAxis.Vertical, homeHandleAxis(-30f, 1f, 8f, horizontal = false))
    }

    @Test
    fun should_followFinger_when_dragged() {
        assertEquals(900f, homeSpanDragWidth(HomeSpan.Full, 1200f, -300f, 590f, 1200f), .01f)
        // A right half grows leftward.
        assertEquals(890f, homeSpanDragWidth(HomeSpan.RightHalf, 590f, -300f, 590f, 1200f), .01f)
        // Held between a little under half and full.
        assertEquals(1200f, homeSpanDragWidth(HomeSpan.LeftHalf, 590f, 2000f, 590f, 1200f), .01f)
        assertEquals(590f * HomeSpanTokens.UnderHalf, homeSpanDragWidth(HomeSpan.Full, 1200f, -2000f, 590f, 1200f), .01f)
    }

    @Test
    fun should_snapAtMidpoint_when_released() {
        assertEquals(HomeSpan.LeftHalf, homeSpanTarget(890f, 590f, 1200f))
        assertEquals(HomeSpan.Full, homeSpanTarget(900f, 590f, 1200f))
    }

    @Test
    fun should_commitOnlyARealChange_when_released() {
        assertEquals(HomeSpanCommit.ToHalf, homeSpanCommit(HomeSpan.Full, HomeSpan.LeftHalf, hasNext = true))
        assertEquals(HomeSpanCommit.None, homeSpanCommit(HomeSpan.Full, HomeSpan.LeftHalf, hasNext = false))
        assertEquals(HomeSpanCommit.ToFull, homeSpanCommit(HomeSpan.RightHalf, HomeSpan.Full, hasNext = false))
        assertEquals(HomeSpanCommit.None, homeSpanCommit(HomeSpan.LeftHalf, HomeSpan.LeftHalf, true))
        assertEquals(HomeSpanCommit.None, homeSpanCommit(HomeSpan.Full, HomeSpan.Full, true))
    }

    @Test
    fun should_mapArrowKeys_when_handleFocused() {
        assertEquals(HomeSpanCommit.ToHalf, homeSpanKey(HomeSpan.Full, left = true, hasNext = true))
        assertNull(homeSpanKey(HomeSpan.Full, left = true, hasNext = false))
        assertNull(homeSpanKey(HomeSpan.Full, left = false, hasNext = true))
        assertEquals(HomeSpanCommit.ToFull, homeSpanKey(HomeSpan.LeftHalf, left = false, hasNext = true))
        assertEquals(HomeSpanCommit.ToFull, homeSpanKey(HomeSpan.RightHalf, left = true, hasNext = true))
        assertNull(homeSpanKey(HomeSpan.RightHalf, left = false, hasNext = true))
    }

    @Test
    fun should_describePlace_when_askedForSpanInfo() {
        val rows = listOf(HomeFeedRow.Halves(a, j), HomeFeedRow.Full(m))
        val order = listOf(a, j, m)
        val right = homeSpanInfoOf(j, rows, order, fullPx = 1220f, gapPx = 20f)!!
        assertEquals(HomeSpan.RightHalf, right.span)
        assertEquals(600f, right.halfPx, .01f)
        val last = homeSpanInfoOf(m, rows, order, 1220f, 20f)!!
        assertEquals(HomeSpan.Full, last.span)
        assertFalse(last.hasNext)
        val first = homeSpanInfoOf(m, listOf(HomeFeedRow.Full(m), HomeFeedRow.Full(a)), listOf(m, a), 1220f, 20f)!!
        assertTrue(first.hasNext)
        assertNull(homeSpanInfoOf(HomeSection.Rediscover, rows, order, 1220f, 20f))
    }

    @Test
    fun should_sizeCells_when_rowDraggedOrAtRest() {
        val pair = HomeFeedRow.Halves(a, j)
        assertEquals(listOf(600, 600), homeRowCellWidths(pair, 1220, 20, dragged = null, dragPx = 0))
        assertEquals(listOf(800, 400), homeRowCellWidths(pair, 1220, 20, dragged = a, dragPx = 800))
        // The partner never shrinks under half a half; the dragged cell draws over it.
        assertEquals(listOf(300, 1100), homeRowCellWidths(pair, 1220, 20, dragged = j, dragPx = 1100))
        assertEquals(listOf(900), homeRowCellWidths(HomeFeedRow.Full(m), 1220, 20, dragged = m, dragPx = 900))
    }
}
