package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Cards that show a note or review as formatted text (All Memories' album review and song-note cards, the
 * scrapbook sheet's note cards) draw it through [NoteMarkupText]: the reader sees the words and the mention's
 * name, never the stored `[@Title](yoin:…)` link or Markdown marks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun should_layEachMentionOutAsOneSlot_evenForLongAndChineseNamesOnANarrowLine() {
        var layout: androidx.compose.ui.text.TextLayoutResult? = null
        rule.setContent {
            YoinTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(160.dp)) {
                    NoteMarkupBody(
                        text = "from [@Low Tide Static](yoin:track/subsonic:t1) and [@林间](yoin:album/subsonic:a1) and " +
                            "[@An Extraordinarily Long Song Title That Cannot Fit](yoin:track/subsonic:t2)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        color = { androidx.compose.ui.graphics.Color.Black },
                        mentionColor = androidx.compose.ui.graphics.Color.Blue,
                        isMentionResolved = { true },
                        onMentionClick = {},
                        interactive = false,
                        onTextLayout = { layout = it },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasText("Low Tide Static", substring = true)).assertExists()
        rule.onNode(hasText("林间", substring = true)).assertExists()

        val laid = layout!!
        val widthPx = with(rule.density) { 160.dp.toPx() }
        val pills = laid.placeholderRects
        // one slot per mention: cover and name together, never split across lines
        assertEquals(3, pills.size)
        pills.forEach { pill ->
            requireNotNull(pill)
            assertTrue(pill.width <= widthPx + 0.5f)
            val line = laid.getLineForVerticalPosition(pill.center.y)
            assertTrue(pill.top >= laid.getLineTop(line) - 0.5f && pill.bottom <= laid.getLineBottom(line) + 0.5f)
        }
    }
}
