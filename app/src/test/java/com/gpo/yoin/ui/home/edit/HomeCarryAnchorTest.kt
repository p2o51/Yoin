package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.FeedFrameClass
import com.gpo.yoin.ui.home.HomeFeedFrame
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import com.gpo.yoin.ui.home.HomeSection.Rediscover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The drop's anchor (port sheet §4.9): one scroll that lands the dropped
 * plate on its slot, the header kept out, the index taken from the keys Home
 * emits. Pure where it can be; one real LazyColumn for the scroll itself.
 */
@RunWith(RobolectricTestRunner::class)
class HomeCarryAnchorTest {

    @get:Rule
    val rule = createComposeRule()

    private val feedKeys: List<Any> =
        listOf(HeaderKey) + listOf(Activities, JumpBackIn, RecentlyAdded).map(::carryItemKey)

    @Test
    fun should_targetSlotTopPlusOutset() {
        val anchor = carryAnchor(
            emittedKeys = feedKeys,
            order = listOf(Activities, JumpBackIn, RecentlyAdded),
            dropped = JumpBackIn,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = mapOf<Any, Int>(carryItemKey(Activities) to 1000)::get,
            spacingPx = 36,
            headerVisible = false,
        )
        assertEquals(CarryAnchor(index = 2, offset = -304), anchor)
        // The item lands at −offset in the list, plus the top padding in the Box: its plate top is the slot.
        val itemTopInBox = -anchor!!.offset - (-8)
        assertEquals(300f, itemTopInBox - 12f, 0f)
    }

    @Test
    fun should_keepFirstVisibleAtLeastOne() {
        // Only 100px of block above: the scroll stops with that block at the top, the header out.
        val clamped = carryAnchor(
            emittedKeys = feedKeys,
            order = listOf(Activities, JumpBackIn, RecentlyAdded),
            dropped = JumpBackIn,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = mapOf<Any, Int>(carryItemKey(Activities) to 100)::get,
            spacingPx = 36,
            headerVisible = false,
        )
        assertEquals(CarryAnchor(index = 2, offset = -136), clamped)

        val first = carryAnchor(
            emittedKeys = feedKeys,
            order = listOf(Activities, JumpBackIn, RecentlyAdded),
            dropped = Activities,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = { null },
            spacingPx = 36,
            headerVisible = false,
        )
        assertEquals(CarryAnchor(index = 1, offset = 0), first)
    }

    @Test
    fun should_skip_when_headerVisible() {
        val anchor = carryAnchor(
            emittedKeys = feedKeys,
            order = listOf(Activities, JumpBackIn, RecentlyAdded),
            dropped = JumpBackIn,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = { 1000 },
            spacingPx = 36,
            headerVisible = true,
        )
        assertNull(anchor)
    }

    @Test
    fun should_countHeaderGone_when_fullyUnderStatusBand() {
        // Critique 34: the header's own status-bar padding still lays out under the band.
        assertTrue(carryHeaderVisible(headerBottomInBox = 120f, statusBandBottom = 100f))
        assertFalse(carryHeaderVisible(headerBottomInBox = 100f, statusBandBottom = 100f))
        assertFalse(carryHeaderVisible(headerBottomInBox = null, statusBandBottom = 100f))
    }

    @Test
    fun should_skip_when_aHeightAboveIsUnknown() {
        val anchor = carryAnchor(
            emittedKeys = feedKeys,
            order = listOf(Activities, JumpBackIn, RecentlyAdded),
            dropped = RecentlyAdded,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = mapOf<Any, Int>(carryItemKey(JumpBackIn) to 100)::get,
            spacingPx = 36,
            headerVisible = false,
        )
        assertNull(anchor)
    }

    @Test
    fun should_indexDroppedInCommittedOrder_when_keysAreStillOld() {
        // The commit has not recomposed yet: the keys Home emits are the old order.
        val keys = feedKeys + TrayKey
        val order = listOf(RecentlyAdded, Activities, JumpBackIn)
        assertEquals(
            listOf(HeaderKey, carryItemKey(RecentlyAdded), carryItemKey(Activities), carryItemKey(JumpBackIn), TrayKey),
            carryReorderedKeys(keys, order),
        )
        val anchor = carryAnchor(keys, order, RecentlyAdded, 300f, 12f, -8, { null }, 36, headerVisible = false)
        assertEquals(1, anchor?.index)
        // Already in that order: unchanged.
        assertEquals(carryReorderedKeys(keys, order), carryReorderedKeys(carryReorderedKeys(keys, order), order))
    }

    @Test
    fun should_keepFadingBlockInPlace_when_reordering() {
        // Jump Back In was just hidden and still fades out where it was (critique 17).
        val order = listOf(RecentlyAdded, Activities)
        val keys = carryReorderedKeys(feedKeys, order)
        assertEquals(
            listOf(HeaderKey, carryItemKey(RecentlyAdded), carryItemKey(JumpBackIn), carryItemKey(Activities)),
            keys,
        )
        val anchor = carryAnchor(
            emittedKeys = feedKeys,
            order = order,
            dropped = Activities,
            slotTop = 300f,
            plateOutsetV = 12f,
            viewportStartOffset = -8,
            heightOf = { 1000 },
            spacingPx = 36,
            headerVisible = false,
        )
        assertEquals(3, anchor?.index)
    }

    @Test
    fun should_comeFromAbove_when_heldBackBetweenVisibleBlocks() {
        val order = listOf(Activities, JumpBackIn, RecentlyAdded, Rediscover)
        // A held-back placeholder between laid-out blocks.
        assertTrue(carryIsAbove(JumpBackIn, order, listOf(Activities, RecentlyAdded), pastHeader = false))
        // Scrolled past.
        assertTrue(carryIsAbove(Activities, order, listOf(RecentlyAdded, Rediscover), pastHeader = true))
        // Below everything on screen.
        assertFalse(carryIsAbove(Rediscover, order, listOf(Activities, JumpBackIn), pastHeader = false))
        // Nothing visible: the header decides.
        assertFalse(carryIsAbove(Activities, order, emptyList(), pastHeader = false))
        assertTrue(carryIsAbove(Activities, order, emptyList(), pastHeader = true))
    }

    @Test
    fun should_followOrder_ignoringOtherSections() {
        val order = listOf(RecentlyAdded, Activities, JumpBackIn)
        assertTrue(carryOrderFollows(listOf(RecentlyAdded, Rediscover, JumpBackIn), order))
        assertTrue(carryOrderFollows(emptyList(), order))
        assertFalse(carryOrderFollows(listOf(Activities, RecentlyAdded), order))
    }

    @Test
    fun should_readSectionFromKey_when_itIsABlock() {
        assertEquals(JumpBackIn, carryKeySection(carryItemKey(JumpBackIn)))
        assertEquals("section-jump_back_in", carryItemKey(JumpBackIn))
        assertNull(carryKeySection(HeaderKey))
        assertNull(carryKeySection("section-nope"))
        assertNull(carryKeySection(7))
    }

    @Test
    fun should_landDroppedPlateOnItsSlot_when_anchoredInAList() {
        val listState = LazyListState()
        var order by mutableStateOf(listOf(Activities, JumpBackIn, RecentlyAdded, Rediscover))
        lateinit var host: LazyListCarryHost
        lateinit var scope: CoroutineScope
        val outset = Size(16f, 12f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                scope = rememberCoroutineScope()
                val keys: List<Any> = listOf<Any>(HeaderKey) + order.map(::carryItemKey)
                val emitted = remember { mutableStateOf(keys) }
                SideEffect { emitted.value = keys }
                val frame = remember { HomeFeedFrame(FeedFrameClass.Capped) }
                host = rememberLazyListCarryHost(
                    listState = listState,
                    feedFrame = frame,
                    order = { order },
                    emittedKeys = { emitted.value },
                    safeArea = { HomeEditSafeArea(top = 60f, bottom = 700f) },
                    plateOutsetPx = { outset },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.size(400.dp, 800.dp),
                    contentPadding = PaddingValues(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(key = HeaderKey) { Box(Modifier.fillMaxWidth().height(120.dp)) }
                    items(order, key = ::carryItemKey) { Box(Modifier.fillMaxWidth().height(300.dp)) }
                }
            }
        }
        // Every block lays out once (the height cache), then the header scrolls away.
        rule.runOnIdle { scope.launch { listState.scrollToItem(4) } }
        rule.runOnIdle { scope.launch { listState.scrollToItem(2, 50) } }
        rule.runOnIdle { assertEquals(2, listState.firstVisibleItemIndex) }

        // Jump Back In dropped one slot down, its slot's top at 300px.
        val committed = listOf(Activities, RecentlyAdded, JumpBackIn, Rediscover)
        var done = false
        rule.runOnIdle {
            order = committed
            scope.launch {
                host.anchorAndAwaitLayout(JumpBackIn, committed, slotTop = 300f)
                done = true
            }
        }
        rule.waitUntil { done }
        rule.runOnIdle {
            val info = listState.layoutInfo
            val dropped = info.visibleItemsInfo.first { it.key == carryItemKey(JumpBackIn) }
            assertEquals(3, dropped.index)
            assertEquals(312f, (dropped.offset - info.viewportStartOffset).toFloat(), .5f)
            assertEquals(300f, host.plateRect(JumpBackIn)!!.top, .5f)
            assertTrue(listState.firstVisibleItemIndex >= 1)
        }
    }

    private companion object {
        const val HeaderKey = "home-header"
        const val TrayKey = "tray-title"
    }
}
