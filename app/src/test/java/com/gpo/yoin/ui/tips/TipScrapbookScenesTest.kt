package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The album notes tips: the real scrapbook page in the demo phone. A tap on its paper opens a sticky
 * that never takes the focus (no keyboard over the tip) and saves onto the page; 被提及 waits at the
 * page's foot, and a clipping opens the album where it was written.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h900dp")
class TipScrapbookScenesTest {

    @get:Rule
    val rule = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun should_openAnUnfocusedStickyThenPinIt_when_theStickiesDemoPlays() {
        rule.mainClock.autoAdvance = false
        rule.setContent { YoinTheme { AlbumStickiesDemo(playing = true) } }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText("Bought the vinyl", substring = true, useUnmergedTree = true).assertExists()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()

        // Past the tap on bare paper: the new sticky is an editor, with no focus.
        rule.mainClock.advanceTimeBy(2_800)
        rule.onNode(hasSetTextAction()).assertExists().assertIsNotFocused()

        // Past the words and the save: the sticky is on the page beside the first.
        rule.mainClock.advanceTimeBy(2_800)
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("Side B is for 2 a.m.", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Bought the vinyl", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_listTheMentionsThenOpenTheOneTapped_when_theMentionedInDemoPlays() {
        rule.mainClock.autoAdvance = false
        rule.setContent { YoinTheme { MentionedInDemo(playing = true) } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(1_000)

        val section = resources.getString(R.string.notes_backlinks_title)
        rule.onNodeWithText(section, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(resources.getQuantityString(R.plurals.notes_backlinks_count, 2, 2), useUnmergedTree = true)
            .assertExists()

        // Past the scroll and the tap on the last clipping: the album where it was written, with its sticky.
        rule.mainClock.advanceTimeBy(6_500)
        rule.onNodeWithText(section, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Play it right after", substring = true, useUnmergedTree = true).assertExists()
    }
}
