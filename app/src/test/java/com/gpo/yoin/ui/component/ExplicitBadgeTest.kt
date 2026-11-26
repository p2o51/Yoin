package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.nowplaying.NowPlayingMarqueeTitle
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "E" badge: drawn only for an explicit song or release, read as "Explicit", right after the
 * title, and still on screen when a long title is cut or scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ExplicitBadgeTest {
    @get:Rule
    val rule = createComposeRule()

    private val explicitLabel: String
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.cmp_explicit_badge_cd)

    private val longTitle = "Sing About Me, I'm Dying of Thirst (Extended Version, Remastered 2025)"

    private fun row(title: String, explicit: Boolean) {
        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(ROW_WIDTH).testTag("row")) {
                    SongListItem(
                        title = title,
                        artist = "Kendrick Lamar",
                        album = "good kid, m.A.A.d city",
                        durationSeconds = 723,
                        coverArtUrl = null,
                        onClick = {},
                        extractBackdropColors = false,
                        isExplicit = explicit,
                    )
                }
            }
        }
    }

    @Test
    fun should_showTheBadge_when_theSongIsExplicit() {
        row("HUMBLE.", explicit = true)

        rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun should_showNoBadge_when_theSongIsCleanOrUnknown() {
        row("HUMBLE.", explicit = false)

        rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_keepTheBadgeInTheRow_when_aLongTitleIsCut() {
        row(longTitle, explicit = true)

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        val row = rule.onNodeWithTag("row").getBoundsInRoot()
        val bounds = badge.getBoundsInRoot()
        assertInside(bounds, row)
        assertTrue("badge has a size", bounds.right > bounds.left)
    }

    @Test
    fun should_followTheEllipsis_when_anExplicitTitleIsCut() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleMedium
                Box(modifier = Modifier.width(220.dp).testTag("box")) {
                    ExplicitTitle(text = longTitle, explicit = true, style = style) { m ->
                        Text(longTitle, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                    }
                }
            }
        }

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        assertInside(badge.getBoundsInRoot(), rule.onNodeWithTag("box").getBoundsInRoot())
    }

    @Test
    fun should_keepTheBadgeAfterAMarqueeTitle_when_itScrolls() {
        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(300.dp).testTag("box")) {
                    NowPlayingMarqueeTitle(
                        text = longTitle,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        stretchScale = 1f,
                        explicit = true,
                    )
                }
            }
        }

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        assertInside(badge.getBoundsInRoot(), rule.onNodeWithTag("box").getBoundsInRoot())
    }

    @Test
    fun should_setNowPlayingsBadgeAtTheTitlesXHeight_when_theTitleIsBig() {
        rule.setContent {
            YoinTheme {
                Column {
                    Box(modifier = Modifier.width(380.dp).testTag("hero")) {
                        NowPlayingMarqueeTitle(
                            text = "HUMBLE.",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            stretchScale = 1f,
                            explicit = true,
                        )
                    }
                    val style = MaterialTheme.typography.headlineMedium
                    Box(modifier = Modifier.width(380.dp).testTag("twoLines")) {
                        ExplicitTitle(text = "HUMBLE.", explicit = true, style = style, maxLines = 2, hero = true) { m ->
                            Text("HUMBLE.", style = style, maxLines = 2, modifier = m)
                        }
                    }
                }
            }
        }

        val badges = rule.onAllNodesWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        val hero = badges[0].getBoundsInRoot()
        val twoLines = badges[1].getBoundsInRoot()
        // displaySmall 36sp: x-height 18.4dp, held to 18dp (the cap height's 25.8dp was too big).
        assertEquals(18f, (hero.bottom - hero.top).value, 1f)
        // headlineMedium 28sp: its x-height, 14.3dp (cap height 20dp).
        assertEquals(14.3f, (twoLines.bottom - twoLines.top).value, 1f)
    }

    @Test
    fun should_keepTheCapHeightBadge_when_notAHero() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.headlineMedium
                ExplicitTitle(text = "HUMBLE.", explicit = true, style = style) { m ->
                    Text("HUMBLE.", style = style, modifier = m)
                }
            }
        }

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(20f, (badge.bottom - badge.top).value, 1f)
    }

    @Test
    fun should_keepTheBadgeBesideAVersionTag_when_bothFollowALongName() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleSmall
                Box(modifier = Modifier.width(160.dp).testTag("box")) {
                    AlbumVersionTitle(name = longTitle, version = "Deluxe", titleStyle = style, explicit = true) { text, m ->
                        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                    }
                }
            }
        }

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        assertInside(badge.getBoundsInRoot(), rule.onNodeWithTag("box").getBoundsInRoot())
    }

    @Test
    fun should_layOutTheTitleAsBefore_when_notExplicit() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleMedium
                Column {
                    Text("HUMBLE.", style = style, maxLines = 1, modifier = Modifier.testTag("bare"))
                    ExplicitTitle(text = "HUMBLE.", explicit = false, style = style) { m ->
                        Text("HUMBLE.", style = style, maxLines = 1, modifier = m.testTag("wrapped"))
                    }
                }
            }
        }

        val bare = rule.onNodeWithTag("bare").getBoundsInRoot()
        val wrapped = rule.onNodeWithTag("wrapped").getBoundsInRoot()
        assertEquals(bare.right - bare.left, wrapped.right - wrapped.left)
        assertEquals(bare.bottom - bare.top, wrapped.bottom - wrapped.top)
        rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_giveTheTitleTheRoom_when_aTileIsTooNarrowForTheBadge() {
        // The rows preview's squeezed third tile: a version tag and the badge left the name ".d c…".
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleSmall
                Column {
                    Box(modifier = Modifier.width(91.dp).testTag("tagged")) {
                        AlbumVersionTitle(name = "good kid, m.A.A.d city", version = "Deluxe", titleStyle = style, explicit = true) { text, m ->
                            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m.testTag("taggedName"))
                        }
                    }
                    Box(modifier = Modifier.width(24.dp).testTag("tiny")) {
                        ExplicitTitle(text = longTitle, explicit = true, style = style) { m ->
                            Text(longTitle, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m.testTag("tinyName"))
                        }
                    }
                }
            }
        }

        rule.onAllNodesWithTag(ExplicitBadgeTestTag, useUnmergedTree = true).assertAll(isNotDisplayedMatcher())
        // Without the badge the name keeps what the tag leaves (the tag rule as before), and the tiny tile is all title.
        val tagged = rule.onNodeWithTag("taggedName", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("name keeps ${tagged.right - tagged.left}", tagged.right - tagged.left >= 30.dp)
        val tiny = rule.onNodeWithTag("tinyName", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(24.dp, tiny.right - tiny.left)
    }

    @Test
    fun should_keepTheBadge_when_theTileLeavesTheTitleHalfItsWidth() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleSmall
                Box(modifier = Modifier.width(91.dp).testTag("box")) {
                    ExplicitTitle(text = longTitle, explicit = true, style = style) { m ->
                        Text(longTitle, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m.testTag("name"))
                    }
                }
            }
        }

        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        assertInside(badge.getBoundsInRoot(), rule.onNodeWithTag("box").getBoundsInRoot())
        val name = rule.onNodeWithTag("name", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("name keeps half the tile", name.right - name.left >= 91.dp / 2)
    }

    @Test
    fun should_stillSayExplicit_when_aNarrowTileLeavesTheBadgeOut() {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.titleSmall
                Column {
                    // Tiles read as one node, as Library and Home's cards do.
                    Box(modifier = Modifier.width(91.dp).semantics(mergeDescendants = true) {}.testTag("tagged")) {
                        AlbumVersionTitle(name = "good kid, m.A.A.d city", version = "Deluxe", titleStyle = style, explicit = true) { text, m ->
                            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                        }
                    }
                    Box(modifier = Modifier.width(24.dp).semantics(mergeDescendants = true) {}.testTag("tiny")) {
                        ExplicitTitle(text = longTitle, explicit = true, style = style) { m ->
                            Text(longTitle, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                        }
                    }
                    Box(modifier = Modifier.width(300.dp).semantics(mergeDescendants = true) {}.testTag("marquee")) {
                        com.gpo.yoin.ui.nowplaying.NowPlayingMarqueeTitle(
                            text = longTitle,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            stretchScale = 1f,
                            explicit = true,
                        )
                    }
                }
            }
        }

        // No badge drawn on the two narrow tiles, yet each still says "Explicit" once, beside its title.
        val drawn = rule.onAllNodesWithTag(ExplicitBadgeTestTag, useUnmergedTree = true).fetchSemanticsNodes()
            .count { it.layoutInfo.isPlaced && it.boundsInRoot.width > 0f }
        assertEquals("only the marquee draws its badge", 1, drawn)
        listOf("tagged", "tiny", "marquee").forEach { tag ->
            val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
            assertEquals(tag, listOf(explicitLabel), config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue(tag, config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty())
        }
    }

    @Test
    fun should_sayExplicitOnce_when_theBadgeIsDrawn() {
        row("HUMBLE.", explicit = true)

        val config = rule.onNodeWithTag("row").onChildren().onFirst().fetchSemanticsNode().config
        assertEquals(listOf(explicitLabel), config.getOrNull(SemanticsProperties.ContentDescription)?.filter { it == explicitLabel })
    }

    @Test
    fun should_fitTheBadge_onlyWhenTheTitleKeepsHalfTheBoxOrAllOfItself() {
        assertTrue(explicitBadgeFits(maxWidth = 200, titleRoom = 100, naturalTitle = 500))
        assertTrue(!explicitBadgeFits(maxWidth = 200, titleRoom = 99, naturalTitle = 500))
        // A short title that fits whole beside the badge keeps it however narrow the box.
        assertTrue(explicitBadgeFits(maxWidth = 60, titleRoom = 40, naturalTitle = 40))
        assertTrue(!explicitBadgeFits(maxWidth = 60, titleRoom = 20, naturalTitle = 40))
    }

    private fun isNotDisplayedMatcher() = androidx.compose.ui.test.SemanticsMatcher("is not displayed") { node ->
        !node.layoutInfo.isPlaced || node.boundsInRoot.width <= 0f
    }

    private fun assertInside(inner: DpRect, outer: DpRect) {
        assertTrue("badge ${inner.left}..${inner.right} starts inside ${outer.left}", inner.left >= outer.left)
        assertTrue("badge ${inner.left}..${inner.right} ends inside ${outer.right}", inner.right <= outer.right + 0.5.dp)
    }

    private companion object {
        val ROW_WIDTH = 360.dp
    }
}
