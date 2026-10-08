package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.home.edit.rememberStandaloneHomeEditController
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home edit mode in the real feed (plan §2.5): a standalone controller and
 * AdaptiveReduced, so nothing sways and the page goes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1600dp")
class HomeEditIntegrationTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var controller: HomeEditController
    private var albumClicks = 0

    private fun setHome(
        layout: HomeLayout = HomeLayout.Default,
        activities: List<ActivityEvent> = listOf(albumActivity()),
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    controller = rememberStandaloneHomeEditController(layout)
                    HomeEditorialContent(
                        activities = activities,
                        sections = layout.sections,
                        editController = controller,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> albumClicks++ },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        buildCoverArtUrl = { "" },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun enterEdit() {
        rule.runOnIdle { controller.enter(null, lifted = false) }
        rule.waitForIdle()
    }

    private fun footerEntry(): SemanticsNodeInteractionCollection =
        rule.onAllNodes(hasText(EditHome) and hasClickAction())

    @Test
    fun should_disableCardClicks_when_editing() {
        setHome()
        rule.onNodeWithText(AlbumArtist, useUnmergedTree = true).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, albumClicks)

        enterEdit()
        assertTrue(controller.isEditing)
        rule.onNodeWithText(AlbumArtist, useUnmergedTree = true).performTouchInput { click() }
        rule.waitForIdle()

        // An edit-mode tap kicks the card; it never opens it, and never leaves edit mode.
        assertEquals(1, albumClicks)
        assertTrue(controller.isEditing)
        // The card's own click is off too (TalkBack can't reach it either).
        val cards = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText(AlbumArtist)), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { SemanticsProperties.Disabled in it.config })
    }

    @Test
    fun should_renderTrayRow_when_sectionHidden() {
        setHome()
        enterEdit()
        rule.onNodeWithContentDescription("Show Jump Back In", useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { assertTrue(controller.hide(HomeSection.JumpBackIn)) }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Show Jump Back In", useUnmergedTree = true).assertExists()
        // The hidden section's placeholder has faded out of the feed.
        rule.onNodeWithText(JbiPlaceholder, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_renderPlaceholder_when_jbiEmptyAndEditing() {
        setHome()
        rule.onNodeWithText(JbiPlaceholder, useUnmergedTree = true).assertDoesNotExist()

        enterEdit()

        rule.onNodeWithText(JbiPlaceholder, useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_renderFooterEntry_when_notEditing() {
        setHome()
        assertEquals(1, footerEntry().fetchSemanticsNodes().size)
        rule.onNodeWithText(ResetHome, useUnmergedTree = true).assertDoesNotExist()

        enterEdit()

        // The tray takes the footer's place.
        assertEquals(0, footerEntry().fetchSemanticsNodes().size)
        rule.onNodeWithText(ResetHome, useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_renderAllHiddenCard_when_everySectionDisabled() {
        val allHidden = HomeLayout(HomeLayout.Default.sections.map { it.copy(enabled = false) })
        setHome(layout = allHidden)

        rule.onNodeWithText("Home is empty", useUnmergedTree = true).assertExists()
        // The empty card's button and the footer entry both say Edit Home.
        assertEquals(
            2,
            rule.onAllNodesWithText("Edit Home", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        // The empty card's button and the footer entry.
        assertEquals(2, footerEntry().fetchSemanticsNodes().size)
        // No section renders, not even Activities' empty card.
        rule.onNodeWithText(AlbumArtist, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun albumActivity() = ActivityEvent(
        id = 1,
        entityType = ActivityEntityType.ALBUM.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = "a1",
        title = "Black Holes and Revelations",
        subtitle = AlbumArtist,
        coverArtId = null,
        albumId = "a1",
        timestamp = System.currentTimeMillis() - 3_600_000L,
    )

    private companion object {
        const val AlbumArtist = "Muse"
        const val EditHome = "Edit Home"
        const val ResetHome = "Reset Home"
        const val JbiPlaceholder = "Nothing to jump back into"
    }
}
