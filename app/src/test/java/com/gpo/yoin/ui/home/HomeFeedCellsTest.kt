package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A feed row's cells as plain values (HomeFeedCells): what each one shows, and which side is which. */
class HomeFeedCellsTest {

    private val order = listOf(HomeSection.Activities, HomeSection.JumpBackIn, HomeSection.MemoryGrid)
    private val blocks = mapOf(
        HomeSection.Activities to HomeFeedBlock(HomeSection.Activities, placeholder = false),
        HomeSection.JumpBackIn to HomeFeedBlock(HomeSection.JumpBackIn, placeholder = false),
        HomeSection.MemoryGrid to HomeFeedBlock(HomeSection.MemoryGrid, placeholder = true),
    )

    @Test
    fun should_placeACellByTheOrder_when_itHasABlock() {
        assertEquals(
            HomeFeedCellInfo(
                section = HomeSection.JumpBackIn,
                displayIndex = 1,
                displayCount = 3,
                placeholder = false,
                wholeBlockWiggle = false,
            ),
            homeFeedCellInfo(HomeSection.JumpBackIn, blocks, order, activitiesEmpty = false),
        )
    }

    @Test
    fun should_wiggleTheWholeBlock_when_placeholderOrEmptyActivities() {
        assertEquals(true, homeFeedCellInfo(HomeSection.MemoryGrid, blocks, order, activitiesEmpty = false)?.wholeBlockWiggle)
        assertEquals(true, homeFeedCellInfo(HomeSection.Activities, blocks, order, activitiesEmpty = true)?.wholeBlockWiggle)
        assertEquals(false, homeFeedCellInfo(HomeSection.JumpBackIn, blocks, order, activitiesEmpty = true)?.wholeBlockWiggle)
    }

    @Test
    fun should_haveNoCell_when_noBlock() {
        assertNull(homeFeedCellInfo(HomeSection.RecentlyAdded, blocks, order, activitiesEmpty = false))
    }

    @Test
    fun should_indexAsFirst_when_notInTheOrder() {
        val hidingBlock = blocks + (HomeSection.RecentlyAdded to HomeFeedBlock(HomeSection.RecentlyAdded, placeholder = false))

        assertEquals(0, homeFeedCellInfo(HomeSection.RecentlyAdded, hidingBlock, order, activitiesEmpty = false)?.displayIndex)
    }

    @Test
    fun should_beEqual_when_aStepLeavesTheCellAlone() {
        // A step that moves other rows hands an unmoved cell an equal value: its host skips.
        val before = homeFeedCellInfo(HomeSection.MemoryGrid, blocks, order, activitiesEmpty = false)
        val after = homeFeedCellInfo(
            HomeSection.MemoryGrid,
            blocks,
            listOf(HomeSection.JumpBackIn, HomeSection.Activities, HomeSection.MemoryGrid),
            activitiesEmpty = false,
        )
        assertEquals(before, after)
    }

    @Test
    fun should_findEitherSide_when_inTheRow() {
        val left = homeFeedCellInfo(HomeSection.Activities, blocks, order, activitiesEmpty = false)
        val right = homeFeedCellInfo(HomeSection.JumpBackIn, blocks, order, activitiesEmpty = false)

        assertEquals(left, homeFeedRowCell(HomeSection.Activities, left, right))
        assertEquals(right, homeFeedRowCell(HomeSection.JumpBackIn, left, right))
        assertEquals(left, homeFeedRowCell(HomeSection.Activities, left, null))
        assertNull(homeFeedRowCell(HomeSection.MemoryGrid, left, right))
        assertNull(homeFeedRowCell(HomeSection.JumpBackIn, left, null))
    }
}
