package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A small ticket's "duration · N plays" line (device QA 2026-10-05, the Wide right column): one line,
 * never a wrapped "1:10 ·" — the dot and the count are bound by no-break spaces, and the duration
 * goes whole when the line can't hold both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumScrapbookTicketMetaTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_bindTheDotToBothSides_when_aTrackHasDurationAndPlays() {
        val meta = scrapTicketMeta(durationSec = 70, plays = 1)

        assertEquals(ScrapTicketMeta(full = FULL, short = SHORT), meta)
    }

    @Test
    fun should_haveNothingToDrop_when_aTrackHasOnlyOneOfThem() {
        assertEquals(ScrapTicketMeta(full = "1:10", short = null), scrapTicketMeta(durationSec = 70, plays = 0))
        assertEquals(ScrapTicketMeta(full = "3\u00A0plays", short = null), scrapTicketMeta(durationSec = null, plays = 3))
        assertNull(scrapTicketMeta(durationSec = null, plays = 0))
    }

    @Test
    fun should_showDurationAndPlaysOnOneLine_when_theyFit() {
        show(width = 240.dp)

        assertTrue(placed(FULL))
        assertFalse(placed(SHORT))
        assertOneLine()
    }

    @Test
    fun should_dropTheDurationWhole_when_bothDoNotFitTheLine() {
        // "1:10 · 1 play" is thirteen 12sp monospace glyphs (~94dp); "1 play" is six (~43dp).
        show(width = 60.dp)

        assertFalse(placed(FULL))
        assertTrue(placed(SHORT))
        assertOneLine()
    }

    private fun show(width: Dp) {
        rule.setContent {
            YoinTheme {
                ScrapTicketMetaLine(
                    meta = scrapTicketMeta(durationSec = 70, plays = 1)!!,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                    color = Color.Black,
                    modifier = Modifier.width(width).testTag(TAG),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun placed(text: String): Boolean =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    /** Under two lines of the 16sp line height: the line never wraps. */
    private fun assertOneLine() {
        val bounds = rule.onNodeWithTag(TAG).getBoundsInRoot()
        val height = (bounds.bottom - bounds.top).value
        assertTrue("one line: $height", height in 1f..20f)
    }

    private companion object {
        const val TAG = "ticket-meta"
        const val FULL = "1:10\u00A0·\u00A01\u00A0play"
        const val SHORT = "1\u00A0play"
    }
}
