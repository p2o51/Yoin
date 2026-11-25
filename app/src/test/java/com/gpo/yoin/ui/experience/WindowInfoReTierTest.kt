package com.gpo.yoin.ui.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The side panel's close re-tiers the shell column (Compact beside a
 * tablet-portrait panel → Medium at full width). With a static local that
 * recomposed everything under the column — every Home card, cover and line
 * of text — in one ~90ms frame. Only what reads the info may recompose.
 */
@RunWith(RobolectricTestRunner::class)
class WindowInfoReTierTest {

    @get:Rule
    val rule = createComposeRule()

    private val window = YoinWindowInfo(
        layoutMode = LayoutMode.Medium,
        isWidthAtLeastMedium = true,
        isHeightAtLeastMedium = true,
        hingeBounds = null,
        chromeForm = ShellChromeForm.CenteredBar,
    )

    @Test
    fun should_recomposeOnlyItsReaders_when_theColumnReTiersAsThePanelCloses() {
        var columnWidth by mutableStateOf(400.dp)
        val readerCompositions = IntArray(1)
        val bystanderCompositions = IntArray(1)
        val seenUnits = mutableListOf<Int>()
        rule.setContent {
            CompositionLocalProvider(LocalYoinWindowInfo provides window.forPaneWidth(columnWidth)) {
                Reader(readerCompositions, seenUnits)
                Bystander(bystanderCompositions)
            }
        }
        rule.runOnIdle {
            assertEquals(1, readerCompositions[0])
            assertEquals(1, bystanderCompositions[0])
        }

        columnWidth = 800.dp

        rule.runOnIdle {
            assertEquals("the reader takes the new tier", 2, readerCompositions[0])
            assertEquals(listOf(2, 4), seenUnits)
            assertEquals("what doesn't read the tier must skip", 1, bystanderCompositions[0])
        }
    }

    @Test
    fun should_recomposeNothing_when_anEqualInfoIsProvidedAgain() {
        var columnWidth by mutableStateOf(800.dp)
        val readerCompositions = IntArray(1)
        val seenUnits = mutableListOf<Int>()
        rule.setContent {
            CompositionLocalProvider(LocalYoinWindowInfo provides window.forPaneWidth(columnWidth)) {
                Reader(readerCompositions, seenUnits)
            }
        }

        // Same tier, a fresh copy: nothing changes for the content.
        columnWidth = 801.dp

        rule.runOnIdle { assertEquals(1, readerCompositions[0]) }
    }
}

@Composable
private fun Reader(compositions: IntArray, seenUnits: MutableList<Int>) {
    val units = LocalYoinWindowInfo.current.feedUnits
    SideEffect {
        compositions[0]++
        seenUnits += units
    }
}

@Composable
private fun Bystander(compositions: IntArray) {
    SideEffect { compositions[0]++ }
}
