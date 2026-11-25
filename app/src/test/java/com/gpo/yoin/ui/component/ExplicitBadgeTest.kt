package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

        val badge = rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true)
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

        val badge = rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true)
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

        val badge = rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true)
        badge.assertIsDisplayed()
        assertInside(badge.getBoundsInRoot(), rule.onNodeWithTag("box").getBoundsInRoot())
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

        val badge = rule.onNodeWithContentDescription(explicitLabel, useUnmergedTree = true)
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

    private fun assertInside(inner: DpRect, outer: DpRect) {
        assertTrue("badge ${inner.left}..${inner.right} starts inside ${outer.left}", inner.left >= outer.left)
        assertTrue("badge ${inner.left}..${inner.right} ends inside ${outer.right}", inner.right <= outer.right + 0.5.dp)
    }

    private companion object {
        val ROW_WIDTH = 360.dp
    }
}
