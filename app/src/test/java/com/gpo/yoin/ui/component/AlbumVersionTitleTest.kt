package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The version tag on a narrow shelf card (owner 2026-11-24, Recently Played on a small tablet: "你们… 全…"
 * with the tag adrift from the name): a short version stays whole, a long one is still cut so the name
 * keeps half the row, and the tag follows the name's ellipsis instead of the end of the box it was given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class AlbumVersionTitleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepAShortTagWhole_when_aThirdOfTheRowStaysForTheName() {
        // 82dp card: the tag is 45 whole, a third is 27, the gap 6.
        assertEquals(45, versionTagMaxWidth(maxWidth = 82, nameWidth = 200, tagWidth = 45, gap = 6))
        // A name that fits whole leaves the rest to the tag.
        assertEquals(45, versionTagMaxWidth(maxWidth = 82, nameWidth = 20, tagWidth = 45, gap = 6))
    }

    @Test
    fun should_cutALongTag_when_itWouldTakeMoreThanTwoThirds() {
        // "Deluxe Edition" ≈ 80 on the same card: cut to what half the name leaves, as before.
        assertEquals(82 - 41 - 6, versionTagMaxWidth(maxWidth = 82, nameWidth = 200, tagWidth = 80, gap = 6))
        // A short name still lends the tag the rest.
        assertEquals(82 - 10 - 6, versionTagMaxWidth(maxWidth = 82, nameWidth = 10, tagWidth = 80, gap = 6))
    }

    @Test
    fun should_neverGoNegative_when_theRowIsTiny() {
        assertEquals(0, versionTagMaxWidth(maxWidth = 4, nameWidth = 200, tagWidth = 80, gap = 6))
    }

    @Test
    fun should_showTheWholeTagRightAfterTheNamesEllipsis_when_onAnEightyTwoDpCard() {
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(82.dp)) {
                    val style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                    AlbumVersionTitle(name = Name, version = Version, titleStyle = style) { text, modifier ->
                        Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val tag = rule.onNodeWithText(Version, useUnmergedTree = true).fetchSemanticsNode()
        val name = rule.onNodeWithText(Name, useUnmergedTree = true).fetchSemanticsNode()
        val tagLayout = tag.layout()
        val nameLayout = name.layout()
        // The tag says all of 全景声.
        assertFalse(tagLayout.hasVisualOverflow)
        assertFalse(tagLayout.isLineEllipsized(0))
        // The name is cut but keeps at least one glyph before its ellipsis.
        assertTrue(nameLayout.isLineEllipsized(0))
        assertTrue(nameLayout.getLineEnd(0, visibleEnd = true) >= 1)
        // The tag's surface starts 6dp past where the name's ellipsis ends, not past the name's box.
        val nameEnd = name.positionInRoot.x + nameLayout.getLineRight(0)
        val tagSurfaceStart = tag.positionInRoot.x - TagTextInset * density
        assertEquals(6f, (tagSurfaceStart - nameEnd) / density, 1.5f)
        // And the whole row stays inside the card.
        assertTrue((tag.positionInRoot.x + tag.size.width) / density <= 82f - TagTextInset + 0.5f)
    }

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val Name = "你们的快乐都是我的快乐"
        const val Version = "全景声"

        /** The tag's text sits 6dp inside its surface. */
        const val TagTextInset = 6f
    }
}
