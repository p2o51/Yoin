package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.MemoryGrid
import com.gpo.yoin.ui.home.edit.rememberHomeRowKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A section's cell keeps its content as it moves between the feed's rows
 * (HomeFeedCells): a half dragged out of its pair, a full row joining one,
 * and again — never composed anew, and a row a step leaves alone never
 * recomposes.
 */
@RunWith(RobolectricTestRunner::class)
class HomeFeedCellsMoveTest {

    @get:Rule
    val rule = createComposeRule()

    private val pair = listOf(HomeFeedRow.Halves(Activities, JumpBackIn), HomeFeedRow.Full(MemoryGrid))
    private val split = listOf(HomeFeedRow.Full(Activities), HomeFeedRow.Full(JumpBackIn), HomeFeedRow.Full(MemoryGrid))

    @Test
    fun should_keepEachCellsContent_when_aPairSplitsAndJoinsAgain() {
        var rows by mutableStateOf(pair)
        val probe = CellProbe()
        rule.setContent { Feed(rows, probe) }
        rule.waitForIdle()

        listOf(split, pair, split, pair).forEach { next ->
            rule.runOnIdle { rows = next }
            rule.waitForIdle()
        }

        assertEquals(mapOf(Activities to 1, JumpBackIn to 1, MemoryGrid to 1), probe.created)
    }

    @Test
    fun should_recomposeNoCell_when_rowsReorderAroundThem() {
        var rows by mutableStateOf(split)
        val probe = CellProbe()
        rule.setContent { Feed(rows, probe) }
        rule.waitForIdle()
        val before = probe.composed.toMap()

        rule.runOnIdle { rows = listOf(split[2], split[0], split[1]) }
        rule.waitForIdle()

        assertEquals(before, probe.composed)
        assertEquals(mapOf(Activities to 1, JumpBackIn to 1, MemoryGrid to 1), probe.created)
    }

    @Composable
    private fun Feed(rows: List<HomeFeedRow>, probe: CellProbe) {
        val cells = remember { HomeFeedCells<Int> { section, place -> Cell(section, place.data, probe) } }
        val keys = rememberHomeRowKeys(rows)
        LazyColumn(Modifier.fillMaxSize()) {
            rows.forEach { row ->
                item(key = keys.itemKey(row.first), contentType = keys.contentType(row.first)) {
                    HomeFeedRowLayout(
                        row = row,
                        span = null,
                        gap = 8.dp,
                        cells = cells,
                        data = { section -> section.ordinal },
                    )
                }
            }
        }
    }

    @Composable
    private fun Cell(section: HomeSection, data: Int, probe: CellProbe) {
        remember(section) { probe.created[section] = (probe.created[section] ?: 0) + 1 }
        SideEffect { probe.composed[section] = (probe.composed[section] ?: 0) + 1 }
        Box(Modifier.fillMaxWidth().height((40 + data).dp))
    }

    private class CellProbe {
        val created = HashMap<HomeSection, Int>()
        val composed = HashMap<HomeSection, Int>()
    }
}
