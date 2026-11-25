package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.forPaneWidth
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Now Playing side panel's close re-tiers Home in the frame it is
 * committed (tablet portrait: Compact 400dp -> Medium 800dp). Activities and
 * Jump Back In change layout then (phone rows -> unit grid, packed shelf ->
 * column template); their cards must MOVE into the new layout, not be
 * disposed and built again (cover, palette wash, press shape) in that frame.
 * A card's layout node surviving the re-tier is what moving looks like.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp")
class HomeReTierKeepsCardsTest {

    @get:Rule
    val rule = createComposeRule()

    private val window = YoinWindowInfo(LayoutMode.Medium, true, true, null, ShellChromeForm.CenteredBar)
    private var column by mutableStateOf(400.dp)

    @Test
    fun should_keepTheActivitiesCards_when_thePanelClosesAndHomeReTiers() {
        setHome()
        val before = nodeIds("Entry 1")

        rule.runOnIdle { column = 800.dp }
        rule.waitForIdle()

        assertEquals(before, nodeIds("Entry 1"))
    }

    @Test
    fun should_keepTheJumpBackInCards_when_thePanelClosesAndHomeReTiers() {
        setHome()
        val before = nodeIds("Cover 1")

        rule.runOnIdle { column = 800.dp }
        rule.waitForIdle()

        assertEquals(before, nodeIds("Cover 1"))
    }

    @Test
    fun should_seeANewLayout_when_reTiered() {
        // Guards the two above: the re-tier really changes the Activities
        // layout (the unit grid seats entries the phone rows leave out).
        setHome()
        val shown = rule.onAllNodesWithText("Entry 5", useUnmergedTree = true).fetchSemanticsNodes().size

        rule.runOnIdle { column = 800.dp }
        rule.waitForIdle()

        assertNotEquals(shown, rule.onAllNodesWithText("Entry 5", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun nodeIds(text: String): List<Int> =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }.also {
            check(it.isNotEmpty()) { "$text not shown" }
        }

    private fun setHome() {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    CompositionLocalProvider(LocalYoinWindowInfo provides window.forPaneWidth(column)) {
                        HomeEditorialContent(
                            activities = activities(),
                            widgetGrid = grid(),
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
        }
        rule.waitForIdle()
    }

    private fun activities(): List<ActivityEvent> = (1..16).map { i ->
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
