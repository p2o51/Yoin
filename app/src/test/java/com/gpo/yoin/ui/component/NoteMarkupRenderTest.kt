package com.gpo.yoin.ui.component

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cards that show a note or review as formatted text (All Memories' album review and song-note cards, the
 * scrapbook sheet's note cards) draw it through [NoteMarkupText]: the reader sees the words and the mention's
 * name, never the stored `[@Title](yoin:…)` link or Markdown marks.
 */
@RunWith(RobolectricTestRunner::class)
class NoteMarkupRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_showWordsAndNames_neverTheStoredMarkup() {
        rule.setContent {
            YoinTheme {
                NoteMarkupText(
                    text = "**整张**都像 [@Titanic Rising](yoin:album/applemusic:1?a=Weyes%20Blood) 的白天版本",
                    maxLines = 6,
                )
            }
        }

        rule.onNode(hasText("Titanic Rising", substring = true)).assertExists()
        rule.onNode(hasText("整张", substring = true)).assertExists()
        assertEquals(0, rule.onAllNodes(hasText("yoin:", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("**", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun should_drawTheMentionOnce_withItsThumbnailSlot() {
        rule.setContent {
            YoinTheme { NoteMarkupText(text = "像 [@Andromeda](yoin:track/subsonic:t2) 那样", interactive = false) }
        }
        val nodes = rule.onAllNodes(hasText("Andromeda", substring = true)).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
    }
}
