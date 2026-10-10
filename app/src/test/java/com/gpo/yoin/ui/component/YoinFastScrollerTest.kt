package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Holding and dragging [YoinFastScroller]'s handle. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class YoinFastScrollerTest {

    @get:Rule
    val rule = createComposeRule()

    private val sections = ('A'..'Z').mapIndexed { i, c -> FastScrollSection(c.toString(), startIndex = i * 23) }
    private val itemCount = sections.size * 23

    @Test
    fun should_jumpAndUnfoldTheTicks_when_theHandleIsDragged() {
        val jumps = mutableListOf<Int>()
        setScroller(itemCount = itemCount, onJump = { jumps += it })
        rule.onNodeWithTag(FastScrollerTags.TICKS).assertDoesNotExist()

        val track = rule.onNodeWithTag(TRACK).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        val extentPx = with(rule.density) { (track.height - thumb.height).toPx() }
        val dragPx = extentPx * 0.5f

        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, dragPx))
        }
        rule.waitForIdle()

        val expected = FastScrollMath.jumpIndex(0.5f, itemCount, 1, ITEMS_PER_SCREEN)
        assertTrue("no jump", jumps.isNotEmpty())
        assertEquals(expected.toFloat(), jumps.last().toFloat(), 1f)
        rule.onNodeWithTag(FastScrollerTags.TICKS).assertExists()
        rule.onNodeWithTag(FastScrollerTags.BUBBLE).assertExists()
        // The handle sits under the finger.
        val moved = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        val movedPx = with(rule.density) { (moved.top - thumb.top).toPx() }
        assertEquals(dragPx, movedPx, 2f)

        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        rule.onNodeWithTag(FastScrollerTags.TICKS).assertDoesNotExist()
        rule.onNodeWithTag(FastScrollerTags.BUBBLE).assertDoesNotExist()
    }

    @Test
    fun should_ignoreTheHandle_when_contentIsUnderThreeScreens() {
        val jumps = mutableListOf<Int>()
        setScroller(itemCount = (ITEMS_PER_SCREEN * 3).toInt(), onJump = { jumps += it })

        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f))
            up()
        }
        rule.waitForIdle()

        assertTrue(jumps.isEmpty())
        rule.onNodeWithTag(FastScrollerTags.TICKS).assertDoesNotExist()
    }

    @Test
    fun should_stopTakingTouches_when_theListHasBeenStillForAWhile() {
        val jumps = mutableListOf<Int>()
        var scrolling by mutableStateOf(true)
        setScroller(itemCount = itemCount, onJump = { jumps += it }, isScrollInProgress = { scrolling })

        rule.runOnIdle { scrolling = false }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(HIDE_MS)
        rule.waitForIdle()
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f))
            up()
        }
        rule.waitForIdle()

        assertTrue(jumps.isEmpty())
    }

    @Test
    fun should_scrollTheList_when_draggedThroughTheListAdapter() {
        lateinit var list: LazyListState
        rule.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(count = 600) { Text("Row $it", Modifier.height(40.dp)) }
                    }
                    YoinFastScroller(
                        state = list,
                        sections = sections,
                        modifier = Modifier.matchParentSize().testTag(TRACK)
                    )
                }
            }
        }
        // Wake it: the handle only takes touches once the list has scrolled.
        rule.runOnIdle { list.requestScrollToItem(1) }
        rule.onNodeWithTag(TRACK).performTouchInput { swipeUp(startY = centerY, endY = centerY - 100f) }
        rule.waitForIdle()

        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, 250f))
        }
        rule.waitForIdle()

        assertTrue("list at ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex > 100)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    @Test
    fun should_stopTheFling_when_theHandleIsGrabbed() {
        lateinit var list: LazyListState
        rule.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(count = 600) { Text("Row $it", Modifier.height(40.dp)) }
                    }
                    YoinFastScroller(
                        state = list,
                        sections = sections,
                        modifier = Modifier.matchParentSize().testTag(TRACK)
                    )
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TRACK).performTouchInput {
            swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 80)
        }
        rule.mainClock.advanceTimeBy(FRAME_MS * 6)
        assertTrue("the fling should still be running", list.isScrollInProgress)

        // The handle sits over the list: the list never sees this touch.
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 3)
        val grabbedAt = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        rule.mainClock.advanceTimeBy(SETTLE_MS)

        assertFalse(list.isScrollInProgress)
        assertEquals(grabbedAt, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    @Test
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickInAGridUnderASortRow() {
        lateinit var grid: LazyGridState
        setGrid { grid = it }
        wakeGrid(grid)

        val section = gridSections[5] // starts mid-row: 115 = row 38, column 1
        val f = FastScrollMath.tickFraction(section.startIndex, GRID_ITEMS, GRID_COLUMNS)
        val track = rule.onNodeWithTag(TRACK).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        assertEquals("the handle rests at the top", track.top.value, thumb.top.value, 0.5f)
        val extentPx = with(rule.density) { (track.height - thumb.height).toPx() }
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, f * extentPx))
        }
        rule.waitForIdle()

        // The sort row (index 0) has scrolled away; the row holding the
        // section's first item is at the top.
        val row = section.startIndex / GRID_COLUMNS
        assertEquals(1 + row * GRID_COLUMNS, grid.firstVisibleItemIndex)
        assertEquals(0, grid.firstVisibleItemScrollOffset)

        // Back to the top end: the sort row shows again.
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { moveBy(Offset(0f, -f * extentPx)) }
        rule.waitForIdle()
        assertEquals(0, grid.firstVisibleItemIndex)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    @Test
    fun should_restAtTheBottom_when_theGridUnderASortRowIsScrolledToTheEnd() {
        setGrid(initialIndex = GRID_ITEMS)

        val track = rule.onNodeWithTag(TRACK).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        assertEquals(track.bottom.value, thumb.bottom.value, 0.5f)
    }

    @Test
    fun should_fadeOutWhereTheFingerLeftIt_when_hidingAfterARelease() {
        var scrolling by mutableStateOf(true)
        setScroller(itemCount = itemCount, onJump = {}, isScrollInProgress = { scrolling })
        rule.runOnIdle { scrolling = false } // still showing for a moment
        val (startTop, dragPx) = holdAndDragHalfway()
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }

        // The list never moved: the handle stays pinned as it starts to fade…
        rule.mainClock.advanceTimeBy(HIDE_DELAY_MS + FRAME_MS * 3)
        assertEquals(startTop + dragPx, thumbTopPx(), 1f)
        // …and only once it is out of sight takes the list's place again.
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(startTop, thumbTopPx(), 1f)
    }

    @Test
    fun should_glideToTheList_when_itChangesLengthUnderAPinnedHandle() {
        var scrolling by mutableStateOf(true)
        var count by mutableStateOf(itemCount)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    YoinFastScroller(
                        itemCount = count,
                        firstVisibleIndex = { 0f },
                        itemsPerScreen = { ITEMS_PER_SCREEN },
                        sections = sections,
                        onJump = {},
                        modifier = Modifier.matchParentSize().testTag(TRACK),
                        isScrollInProgress = { scrolling }
                    )
                }
            }
        }
        rule.runOnIdle { scrolling = false }
        val (startTop, dragPx) = holdAndDragHalfway()
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        val pinnedTop = startTop + dragPx
        assertEquals(pinnedTop, thumbTopPx(), 1f)

        count = itemCount * 2
        Snapshot.sendApplyNotifications()
        // Frame by frame: it travels the whole way, never more than half of it at once.
        val trace = listOf(pinnedTop) + (1..12).map {
            rule.mainClock.advanceTimeByFrame()
            thumbTopPx()
        }
        assertTrue("never left: $trace", trace.last() < pinnedTop / 2f)
        trace.zipWithNext().forEach { (a, b) -> assertTrue("cut in $trace", a - b < pinnedTop / 2f) }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(startTop, thumbTopPx(), 1f)
    }

    @Test
    fun should_keepTheTarget_when_theListGrowsWhileHeld() {
        val jumps = mutableListOf<Int>()
        var count by mutableStateOf(itemCount)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    YoinFastScroller(
                        itemCount = count,
                        firstVisibleIndex = { 0f },
                        itemsPerScreen = { ITEMS_PER_SCREEN },
                        sections = sections,
                        onJump = { jumps += it },
                        modifier = Modifier.matchParentSize().testTag(TRACK),
                        isScrollInProgress = { true }
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, 5_000f)) // the bottom
        }
        rule.waitForIdle()
        val atBottom = jumps.toList()
        assertEquals(FastScrollMath.jumpIndex(1f, itemCount, 1, ITEMS_PER_SCREEN), atBottom.last())

        // A page arrives while the finger holds the bottom: no new target,
        // so nothing keeps asking for the next page.
        rule.runOnIdle { count = itemCount * 2 }
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { moveBy(Offset(0f, 5f)) }
        rule.waitForIdle()
        assertEquals(atBottom, jumps)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    /** Presses the handle and drags it half the track; returns its top before, and the drag, in px. */
    private fun holdAndDragHalfway(): Pair<Float, Float> {
        rule.mainClock.autoAdvance = false
        val track = rule.onNodeWithTag(TRACK).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        val extentPx = with(rule.density) { (track.height - thumb.height).toPx() }
        val startTop = thumbTopPx()
        val dragPx = extentPx * 0.5f
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput {
            down(center)
            moveBy(Offset(0f, dragPx))
        }
        rule.mainClock.advanceTimeBy(FRAME_MS * 2)
        assertEquals(startTop + dragPx, thumbTopPx(), 1f)
        return startTop to dragPx
    }

    private fun thumbTopPx(): Float {
        val track = rule.onNodeWithTag(TRACK).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        return with(rule.density) { (thumb.top - track.top).toPx() }
    }

    /** A 3-column grid under a full-span sort row, the scroller told to skip it. */
    private fun setGrid(initialIndex: Int = 0, onState: (LazyGridState) -> Unit = {}) {
        rule.setContent {
            MaterialTheme {
                val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
                onState(grid)
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        state = grid,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("Sort", Modifier.height(48.dp)) }
                        items(count = GRID_ITEMS) { Text("Cell $it", Modifier.height(40.dp)) }
                    }
                    YoinFastScroller(
                        state = grid,
                        sections = gridSections,
                        leadingItems = 1,
                        modifier = Modifier.matchParentSize().testTag(TRACK)
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The handle only takes touches once the list has scrolled; then back to the very top. */
    private fun wakeGrid(grid: LazyGridState) {
        rule.onNodeWithTag(TRACK).performTouchInput { swipeUp(startY = centerY, endY = centerY - 100f) }
        rule.waitForIdle()
        rule.runOnIdle { grid.requestScrollToItem(0) }
        rule.waitForIdle()
    }

    private fun setScroller(itemCount: Int, onJump: (Int) -> Unit, isScrollInProgress: () -> Boolean = { true }) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    YoinFastScroller(
                        itemCount = itemCount,
                        firstVisibleIndex = { 0f },
                        itemsPerScreen = { ITEMS_PER_SCREEN },
                        sections = sections,
                        onJump = onJump,
                        modifier = Modifier.matchParentSize().testTag(TRACK),
                        isScrollInProgress = isScrollInProgress
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private val gridSections = ('A'..'M').mapIndexed { i, c -> FastScrollSection(c.toString(), startIndex = i * 23) }

    private companion object {
        const val TRACK = "track"
        const val ITEMS_PER_SCREEN = 16f
        const val SETTLE_MS = 1_000L
        const val HIDE_MS = 2_500L
        const val HIDE_DELAY_MS = 1_600L
        const val FRAME_MS = 16L
        const val GRID_COLUMNS = 3
        const val GRID_ITEMS = 300
    }
}
