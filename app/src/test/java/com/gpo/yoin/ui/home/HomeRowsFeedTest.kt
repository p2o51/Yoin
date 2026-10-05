package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.home.edit.homeRowsHandleTag
import com.gpo.yoin.ui.home.edit.rememberStandaloneHomeEditController
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real feed at a row preset (D1): normal mode renders the stored preset; edit mode shows the ⌟ handles. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1800dp")
class HomeRowsFeedTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var controller: HomeEditController

    private fun setHome(layout: HomeLayout) {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    controller = rememberStandaloneHomeEditController(layout)
                    HomeEditorialContent(
                        activities = activities(),
                        widgetGrid = grid(),
                        sections = layout.sections,
                        editController = controller,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
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

    @Test
    fun should_seatTodaysShelf_when_rowsAtDefault() {
        setHome(HomeLayout.Default)

        rule.onNodeWithText("Cover 8", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Cover 9", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Entry 4", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Entry 5", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_seatTheXlShelf_when_jumpBackInStoredAtXl() {
        setHome(HomeLayout.Default.withRows(HomeSection.JumpBackIn, HomeRowPreset.XL))

        // The phone's XL: two signal cards and fourteen covers over six rows.
        rule.onNodeWithText("Cover 14", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Cover 15", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_showOnlyTheHero_when_activitiesStoredAtS() {
        setHome(HomeLayout.Default.withRows(HomeSection.Activities, HomeRowPreset.S))

        rule.onNodeWithText("Entry 1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Entry 2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_showResizeHandles_when_editing() {
        setHome(HomeLayout.Default)
        rule.onNodeWithTag(homeRowsHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { controller.enter(null, lifted = false) }
        rule.waitForIdle()

        rule.onNodeWithTag(homeRowsHandleTag(HomeSection.Activities), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(homeRowsHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertExists()
    }

    private fun activities(): List<ActivityEvent> = (1..10).map { i ->
        ActivityEvent(
            id = i.toLong(),
            entityType = if (i % 2 == 1) ActivityEntityType.ALBUM.name else ActivityEntityType.ARTIST.name,
            actionType = ActivityActionType.PLAYED.name,
            entityId = "e$i",
            title = "Entry $i",
            subtitle = "Artist $i",
            albumId = "e$i",
            timestamp = 1_000_000L - i * 60_000L,
        )
    }

    private fun grid(): List<HomeWidgetCard> = (1..2).map { i ->
        HomeWidgetCard(
            stableId = "s$i",
            entityType = MemoryEntityType.ALBUM,
            title = "Signal $i",
            subtitle = "Artist",
            coverArtUrl = null,
            ratingText = "8.$i",
            comment = "Note $i",
            expanded = true,
            target = HomeWidgetTarget.MemoryFocus(i.toLong()),
        )
    } + (1..28).map { i ->
        HomeWidgetCard(
            stableId = "c$i",
            entityType = MemoryEntityType.ALBUM,
            title = "Cover $i",
            subtitle = "Artist",
            coverArtUrl = null,
            target = HomeWidgetTarget.AlbumDetail("c$i"),
        )
    }
}
