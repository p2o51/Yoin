package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search surface's grids come back where they were left after the
 * surface morphs to its pill and back (device QA LIB-26: a result opened in
 * the Wide column, the pill tapped again). The morph lays the results out at
 * every width between the window and the pill, and the surface's content
 * leaves composition while folded; the grid's state and anchor are kept
 * outside it, as LibrarySearchScrollMemory keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class LibraryGridAnchorTest {

    @get:Rule
    val rule = createComposeRule()

    private var width by mutableStateOf(FullWidth)
    private var composed by mutableStateOf(true)
    private val state = LazyGridState()
    private val anchor = LibraryGridAnchor(0)

    @Test
    fun should_comeBackPartWayIntoTheFirstRow_when_theSearchMorphsAwayAndBack() {
        setGrid()
        scrollBy(SMALL_SCROLL_PX)
        val left = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        assertEquals("still in the first row", 0, left.first)
        assertTrue("scrolled into it", left.second > 0)

        morphAwayAndBack()

        assertEquals(left, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test
    fun should_comeBackToTheRowItWasOn_when_theSearchMorphsAwayAndBack() {
        setGrid()
        scrollBy(LARGE_SCROLL_PX)
        val left = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        assertTrue("a row down", left.first > 0)

        morphAwayAndBack()

        assertEquals(left, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test
    fun should_keepTheItemInTheTopRow_when_theGridSettlesAtAnotherWidth() {
        setGrid()
        scrollBy(LARGE_SCROLL_PX)
        val item = state.firstVisibleItemIndex

        width = 600.dp
        rule.waitForIdle()

        // The item the user left on top is in the top row again, not a row off.
        val top = state.firstVisibleItemIndex
        assertTrue("item $item in the top row from $top", item - top in 0 until perLine())
    }

    @Test
    fun should_keepTheShareOfTheRowScrolledPast_when_theMorphPassesThroughOtherWidths() {
        setGrid()
        scrollBy(SMALL_SCROLL_PX)
        val share = state.firstVisibleItemScrollOffset.toFloat() / rowPx()

        // Halfway through the morph: other columns, other rows, the same share.
        width = Sweep[2]
        rule.waitForIdle()

        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(share, state.firstVisibleItemScrollOffset.toFloat() / rowPx(), 1f / rowPx())
    }

    private fun rowPx(): Int {
        val info = state.layoutInfo
        return info.visibleItemsInfo.first().size.height + info.mainAxisItemSpacing
    }

    /** The full-window search folding to its pill, gone, and unfolding again. */
    private fun morphAwayAndBack() {
        for (step in Sweep.reversed()) {
            width = step
            rule.waitForIdle()
        }
        composed = false
        rule.waitForIdle()
        composed = true
        rule.waitForIdle()
        for (step in Sweep + FullWidth) {
            width = step
            rule.waitForIdle()
        }
    }

    private fun perLine(): Int = state.layoutInfo.maxSpan

    /** A slow drag: a user scroll, all but no fling. */
    private fun scrollBy(px: Float) {
        rule.onNodeWithTag(GRID).performTouchInput {
            swipeUp(startY = centerY + px / 2f, endY = centerY - px / 2f, durationMillis = 1_500)
        }
        rule.waitForIdle()
    }

    private fun setGrid() {
        rule.setContent {
            if (composed) {
                Box(Modifier.width(width).height(760.dp).testTag(GRID)) {
                    KeepGridAnchorAcrossWidthChanges(state, anchor, exact = true)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        state = state,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(count = 120) { Text("Cell $it", Modifier.aspectRatio(1f)) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        const val GRID = "grid"
        const val SMALL_SCROLL_PX = 60f
        const val LARGE_SCROLL_PX = 260f
        val FullWidth: Dp = 1_248.dp

        /** The widths the morph lays the results out at, pill to window. */
        val Sweep: List<Dp> = listOf(360.dp, 520.dp, 700.dp, 880.dp, 1_060.dp)
    }
}
