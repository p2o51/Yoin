package com.gpo.yoin.ui.home

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A cell springing between half and full width lays its content out once,
 * at the width it springs to, while its block follows the spring: laid out
 * at every width in between, sections re-packed their grids each frame and
 * drew their cards over each other (HomeCellMorph).
 */
@RunWith(RobolectricTestRunner::class)
class HomeCellMorphTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_layContentOutOnceAtTarget_when_halfTurnsFull() {
        var row: HomeFeedRow by mutableStateOf(HomeFeedRow.Halves(HomeSection.Activities, HomeSection.JumpBackIn))
        val contentWidths = mutableListOf<Int>()
        val composedWidths = mutableListOf<Int>()
        val blockWidths = mutableListOf<Int>()
        rule.setContent {
            val scope = rememberCoroutineScope()
            val flips = remember {
                HomeCellFlips(scope, { spring(stiffness = 200f) }, { spring() }).apply { enabled = { true } }
            }
            val cells = remember {
                HomeFeedCells<Unit> { section, _ ->
                    val morph = LocalHomeCellMorph.current
                    Box(
                        Modifier
                            .homeCellMorphBlock(morph)
                            .fillMaxWidth()
                            .onPlaced { if (section == HomeSection.Activities) blockWidths += it.size.width },
                    ) {
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .homeCellMorphContent(morph)
                                .layout { measurable, constraints ->
                                    if (section == HomeSection.Activities) contentWidths += constraints.maxWidth
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                },
                        ) {
                            if (section == HomeSection.Activities) composedWidths += constraints.maxWidth
                            Box(Modifier.fillMaxWidth().height(100.dp))
                        }
                    }
                }
            }
            Box(Modifier.width(820.dp)) {
                HomeFeedRowLayout(
                    row = row,
                    span = null,
                    gap = 20.dp,
                    flips = flips,
                    cells = cells,
                    data = { Unit },
                )
            }
        }
        rule.waitForIdle()
        val half = contentWidths.last()

        rule.mainClock.autoAdvance = false
        contentWidths.clear()
        composedWidths.clear()
        blockWidths.clear()
        row = HomeFeedRow.Full(HomeSection.Activities)
        repeat(40) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val full = blockWidths.last()
        assertTrue(full > half)
        // The block springs through the widths in between…
        assertTrue(blockWidths.any { it in (half + 1) until full })
        // …the content is measured at the full width alone, and never composes for a width in between.
        assertEquals(setOf(full), contentWidths.toSet())
        assertTrue(composedWidths.all { it == half || it == full })
        assertEquals(full, composedWidths.last())
    }

    @Test
    fun should_addWhatSpringHasLeft_when_contentWidthAsked() {
        assertEquals(800, homeCellMorphContentWidth(boxWidth = 500, extra = 300))
        assertEquals(390, homeCellMorphContentWidth(boxWidth = 790, extra = -400))
        assertEquals(1, homeCellMorphContentWidth(boxWidth = 0, extra = -10))
    }

    @Test
    fun should_keepContentAtTheEndEdge_when_aFullRowBecomesTheRightHalf() {
        // Jump Back In (0‥1200) becomes the right half (620‥1200) as Activities is dropped on its left:
        // its right edge stays, so its content sits in the right half, never across the incoming slot.
        assertTrue(homeCellAnchorsEnd(fromLeft = 0f, fromRight = 1200f, toLeft = 620f, toRight = 1200f))
        // The right half turning full widens leftward: revealed from its end edge too.
        assertTrue(homeCellAnchorsEnd(fromLeft = 620f, fromRight = 1200f, toLeft = 0f, toRight = 1200f))
    }

    @Test
    fun should_keepContentAtTheStartEdge_when_theLeftEdgeStaysOrTheCellOnlyMoves() {
        assertFalse(homeCellAnchorsEnd(fromLeft = 0f, fromRight = 1200f, toLeft = 0f, toRight = 580f))
        assertFalse(homeCellAnchorsEnd(fromLeft = 0f, fromRight = 580f, toLeft = 0f, toRight = 1200f))
        // A swap of sides (same width) moves the whole cell: start edge, as before.
        assertFalse(homeCellAnchorsEnd(fromLeft = 0f, fromRight = 580f, toLeft = 620f, toRight = 1200f))
    }
}
