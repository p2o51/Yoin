package com.gpo.yoin.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Segment chips are single-choice tabs to accessibility services: the
 * search row puts a scope pair and the result types side by side, and
 * TalkBack has to tell which of each is selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class ExpressiveSegmentChipTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_readAsSelectedTab_when_chipIsSelected() {
        rule.setContent {
            ExpressiveSegmentChip(label = "Songs", selected = true, onClick = {})
        }

        rule.onNodeWithText("Songs")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
    }

    @Test
    fun should_moveSelection_when_segmentedTabsChipIsTapped() {
        var selected by mutableStateOf("Artists")
        rule.setContent {
            ExpressiveSegmentedTabs(
                items = listOf("Artists", "Albums"),
                selectedItem = selected,
                label = { it },
                onSelectedChange = { selected = it }
            )
        }

        rule.onNodeWithText("Albums").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Albums")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
        rule.onNodeWithText("Artists").assertIsNotSelected()
    }
}
