package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard shortcuts tip's demo: Library with a song in the bar and three
 * chords as keycaps; pressing Ctrl + → moves the bar on a song, Ctrl + K
 * opens the phone's search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class TipKeyboardShortcutsSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show() {
        rule.setContent { YoinTheme { KeyboardShortcutsDemo(playing = false) } }
        rule.waitForIdle()
    }

    @Test
    fun should_showTheThreeChordsAsKeycaps_when_shown() {
        show()

        rule.onNodeWithText("Space").assertExists()
        rule.onNodeWithText("→").assertExists()
        rule.onNodeWithText("K").assertExists()
        assertEquals(2, rule.onAllNodesWithText("Ctrl").fetchSemanticsNodes().size)
    }

    @Test
    fun should_moveTheBarToTheNextSong_when_ctrlRightIsPressed() {
        show()
        rule.onAllNodesWithText(DemoTrackTitles[0], substring = true).onFirst().assertExists()

        rule.onNodeWithText("→").performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText(DemoTrackTitles[1], substring = true).onFirst().assertExists()
    }

    @Test
    fun should_openSearch_when_ctrlKIsPressed() {
        show()
        // Library's own search pill reads the same placeholder.
        val before = rule.onAllNodesWithText("Search library").fetchSemanticsNodes().size

        rule.onNodeWithText("K").performClick()
        rule.waitForIdle()

        assertEquals(before + 1, rule.onAllNodesWithText("Search library").fetchSemanticsNodes().size)
    }

    @Config(qualifiers = "zh-rCN-w412dp-h915dp")
    @Test
    fun should_labelTheSpaceKeyInChinese_when_theAppUsesSimplifiedChinese() {
        show()

        rule.onNodeWithText("空格").assertExists()
    }
}
