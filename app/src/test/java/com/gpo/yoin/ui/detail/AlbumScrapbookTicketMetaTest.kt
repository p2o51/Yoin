package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
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
 * A ticket's duration and play count stay separate groups on one line.
 * A narrow row drops the plays and leaves the duration.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumScrapbookTicketMetaTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepDurationAndPlaysSeparate_when_aTrackHasBoth() {
        assertEquals(
            ScrapTicketMeta(duration = "1:10", plays = 1),
            scrapTicketMeta(durationSec = 70, plays = 1),
        )
    }

    @Test
    fun should_haveNothingToDrop_when_aTrackHasOnlyOneOfThem() {
        assertEquals(
            ScrapTicketMeta(duration = "1:10", plays = 0),
            scrapTicketMeta(durationSec = 70, plays = 0),
        )
        assertEquals(
            ScrapTicketMeta(duration = null, plays = 3),
            scrapTicketMeta(durationSec = null, plays = 3),
        )
        assertNull(scrapTicketMeta(durationSec = null, plays = 0))
    }

    @Test
    fun should_showDurationAndPlaysOnOneLine_when_theyFit() {
        show(width = 240.dp)

        assertTrue(placed("1:10"))
        assertTrue(placed("1"))
        assertTrue(placed("play"))
        assertOneLine()
    }

    @Test
    fun should_dropThePlays_when_theRowIsNarrow() {
        show(width = 60.dp)

        assertTrue(placed("1:10"))
        assertFalse(placed("play"))
        assertOneLine()
    }

    private fun show(width: Dp) {
        rule.setContent {
            YoinTheme {
                ScrapTicketMetaLine(
                    meta = scrapTicketMeta(durationSec = 70, plays = 1)!!,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
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
    }
}
