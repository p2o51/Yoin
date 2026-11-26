package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.home.WidgetBackdropArtwork
import com.gpo.yoin.ui.home.WidgetShapeKind
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
 * PROTOTYPE (PR #343): the version tag and the "E" on the backdrop shape's band above the cover —
 * never on the art, never growing the card, the tag dropped rather than cut to an ellipsis.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class CoverBandBadgesTest {
    @get:Rule
    val rule = createComposeRule()

    private fun art(size: Int, version: String?, explicit: Boolean) {
        rule.setContent {
            YoinTheme {
                Box(Modifier.testTag("art").size(size.dp)) {
                    WidgetBackdropArtwork(
                        model = null,
                        kind = WidgetShapeKind.Album,
                        contentDescription = "DAMN.",
                        extractBackdropColors = false,
                        modifier = Modifier.size(size.dp),
                        version = version,
                        explicit = explicit,
                    )
                }
            }
        }
    }

    @Test
    fun should_setTagAndBadgeOnTheBandAboveTheCover() {
        art(118, "全景声", explicit = true)

        val art = rule.onNodeWithTag("art").getBoundsInRoot()
        val bandBottom = art.top + (art.bottom - art.top) * 0.28f
        val badge = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        badge.assertIsDisplayed()
        val tag = rule.onNodeWithText("全景声", useUnmergedTree = true)
        tag.assertIsDisplayed()
        listOf(badge.getBoundsInRoot(), tag.getBoundsInRoot()).forEach { b ->
            assertTrue("above the cover: ${b.bottom} <= $bandBottom", b.bottom <= bandBottom + 0.5.dp)
            assertTrue("inside the art box", b.left >= art.left && b.right <= art.right)
        }
    }

    @Test
    fun should_dropTheTagNotCutIt_when_theBandIsTooNarrow() {
        art(48, "Dolby Atmos Deluxe Edition", explicit = true)

        rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true).assertIsDisplayed()
        val tag = rule.onNodeWithText("Dolby Atmos Deluxe Edition", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("tag not drawn", !tag.layoutInfo.isPlaced || tag.boundsInRoot.width <= 0f)
    }

    @Test
    fun should_keepTheCardHeight_when_badgesMoveOntoTheArt() {
        rule.setContent {
            YoinTheme {
                Row {
                    Box(Modifier.width(118.dp).testTag("bare")) {
                        AlbumCard(coverArtUrl = null, title = "DAMN.", subtitle = "Kendrick Lamar", onClick = {}, fixedWidth = null, extractBackdropColors = false)
                    }
                    Box(Modifier.width(118.dp).testTag("badged")) {
                        AlbumCard(
                            coverArtUrl = null, title = "good kid, m.A.A.d city", subtitle = "Kendrick Lamar", version = "Deluxe Edition",
                            explicit = true, onClick = {}, fixedWidth = null, extractBackdropColors = false,
                        )
                    }
                }
            }
        }

        val bare = rule.onNodeWithTag("bare").getBoundsInRoot()
        val badged = rule.onNodeWithTag("badged").getBoundsInRoot()
        assertEquals(bare.bottom - bare.top, badged.bottom - badged.top)
    }

    @Test
    fun should_keepTheEAndTheKind_andDropTheVersion_when_threeTagsMeetANarrowBand() {
        rule.setContent {
            YoinTheme {
                Box(Modifier.testTag("art").size(96.dp)) {
                    WidgetBackdropArtwork(
                        model = null,
                        kind = WidgetShapeKind.Album,
                        contentDescription = "Section.80",
                        extractBackdropColors = false,
                        modifier = Modifier.size(96.dp),
                        version = "Dolby Atmos Deluxe Edition",
                        explicit = true,
                        kindTag = "EP",
                    )
                }
            }
        }

        val art = rule.onNodeWithTag("art").getBoundsInRoot()
        val e = rule.onNodeWithTag(ExplicitBadgeTestTag, useUnmergedTree = true)
        val kind = rule.onNodeWithText("EP", useUnmergedTree = true)
        e.assertIsDisplayed()
        kind.assertIsDisplayed()
        assertTrue("kind before E", kind.getBoundsInRoot().right <= e.getBoundsInRoot().left)
        listOf(e.getBoundsInRoot(), kind.getBoundsInRoot()).forEach { b ->
            assertTrue(b.left >= art.left && b.right <= art.right && b.bottom <= art.top + (art.bottom - art.top) * 0.28f + 0.5.dp)
        }
    }

    @Test
    fun should_offerTheKindFilter_onlyForTwoOrMoreKinds() {
        assertEquals(emptyList<ReleaseKindFilter>(), ReleaseKindFilter.offeredFor(listOf(com.gpo.yoin.data.model.ReleaseType.Album, null)))
        assertEquals(
            listOf(ReleaseKindFilter.All, ReleaseKindFilter.Albums, ReleaseKindFilter.SinglesAndEps),
            ReleaseKindFilter.offeredFor(listOf(com.gpo.yoin.data.model.ReleaseType.Album, com.gpo.yoin.data.model.ReleaseType.EP)),
        )
        assertEquals("Section.80", nameWithoutKindSuffix("Section.80 - EP", com.gpo.yoin.data.model.ReleaseType.EP))
        assertEquals("Section.80 - EP", nameWithoutKindSuffix("Section.80 - EP", com.gpo.yoin.data.model.ReleaseType.Album))
    }
}
