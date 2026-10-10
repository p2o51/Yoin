package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private companion object {
        const val TRACK = "track"
        const val ITEMS_PER_SCREEN = 16f
        const val SETTLE_MS = 1_000L
        const val HIDE_MS = 2_500L
    }
}
