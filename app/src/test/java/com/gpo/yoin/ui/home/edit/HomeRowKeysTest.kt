package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.lazy.LazyListItemInfo
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.MemoryGrid
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The feed rows' item keys: a pair swapping sides keeps its item ([HomeRowKeys]). */
class HomeRowKeysTest {

    private val pair = HomeFeedRow.Halves(Activities, JumpBackIn)
    private val swapped = HomeFeedRow.Halves(JumpBackIn, Activities)
    private val rows = listOf(pair, HomeFeedRow.Full(MemoryGrid))

    @Test
    fun should_keyEachRowByItsFirst_when_new() {
        val keys = HomeRowKeys.Initial.next(rows)

        assertEquals(homeSectionItemKey(Activities), keys.itemKey(Activities))
        assertEquals(homeSectionItemKey(MemoryGrid), keys.itemKey(MemoryGrid))
        assertEquals(Activities, keys.firstOf(homeSectionItemKey(Activities)))
    }

    @Test
    fun should_keepTheRowsKey_when_pairSwapsSides() {
        val keys = HomeRowKeys.Initial.next(rows).next(listOf(swapped, HomeFeedRow.Full(MemoryGrid)))

        assertEquals(homeSectionItemKey(Activities), keys.itemKey(JumpBackIn))
        // The item reads back as the row's first section, its left half now.
        assertEquals(JumpBackIn, keys.firstOf(homeSectionItemKey(Activities)))
        assertEquals(MemoryGrid, keys.firstOf(homeSectionItemKey(MemoryGrid)))
    }

    @Test
    fun should_keepTheKey_when_swappedBackAndForth() {
        val once = HomeRowKeys.Initial.next(rows).next(listOf(swapped, HomeFeedRow.Full(MemoryGrid)))
        val back = once.next(rows)

        assertEquals(homeSectionItemKey(Activities), back.itemKey(Activities))
        assertEquals(Activities, back.firstOf(homeSectionItemKey(Activities)))
    }

    @Test
    fun should_keyByFirst_when_pairSplitsOrForms() {
        val swappedKeys = HomeRowKeys.Initial.next(rows).next(listOf(swapped, HomeFeedRow.Full(MemoryGrid)))
        // Split: each half its own row, keyed by itself (the same sections no longer share a row).
        val split = swappedKeys.next(listOf(HomeFeedRow.Full(JumpBackIn), HomeFeedRow.Full(Activities)))
        assertEquals(homeSectionItemKey(JumpBackIn), split.itemKey(JumpBackIn))
        assertEquals(homeSectionItemKey(Activities), split.itemKey(Activities))
        assertEquals(Activities, split.firstOf(homeSectionItemKey(Activities)))

        // A new pair takes its left half's key, as before.
        val formed = split.next(listOf(HomeFeedRow.Halves(MemoryGrid, Activities), HomeFeedRow.Full(JumpBackIn)))
        assertEquals(homeSectionItemKey(MemoryGrid), formed.itemKey(MemoryGrid))
    }

    @Test
    fun should_giveUniqueKeys_when_swapped() {
        val next = listOf(swapped, HomeFeedRow.Full(MemoryGrid), HomeFeedRow.Full(RecentlyAdded))
        val keys = HomeRowKeys.Initial.next(rows).next(next)

        val itemKeys = next.map { keys.itemKey(it.first) }
        assertEquals(itemKeys.size, itemKeys.toSet().size)
        assertEquals(next.map { it.first }, itemKeys.map { keys.firstOf(it) })
    }

    @Test
    fun should_returnNull_when_notASectionItem() {
        assertNull(HomeRowKeys.Initial.firstOf("tray-title"))
        assertNull(HomeRowKeys.Initial.next(rows).firstOf(42))
    }

    @Test
    fun should_bandTheRowByItsFirst_when_pairSwapped() {
        val keys = HomeRowKeys.Initial.next(rows).next(listOf(swapped, HomeFeedRow.Full(MemoryGrid)))
        val items = listOf(
            Item(1, homeSectionItemKey(Activities), offset = 100, size = 300),
            Item(2, homeSectionItemKey(MemoryGrid), offset = 418, size = 200),
        )

        assertEquals(
            listOf(
                SectionBand(JumpBackIn, top = 100f, bottom = 400f, bleeds = false),
                SectionBand(MemoryGrid, top = 418f, bottom = 618f, bleeds = false),
            ),
            homeEditSectionBands(items, viewportStartOffset = 0, rowKeys = keys),
        )
    }

    private data class Item(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo
}
