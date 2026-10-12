package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.forPaneWidth
import com.gpo.yoin.ui.navigation.pane.defaultShellFraction
import com.gpo.yoin.ui.navigation.pane.resolvePaneBudget
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
 * By You unfolds after Playlists and comes to rest whole on screen, clear of
 * the row's edge fades, in every layout the chip row has. Native graphics, so
 * the chip labels measure their real widths and each row is as long as on a
 * device.
 *
 * Playlists is the first chip, so most rows have room for By You as they are:
 * a phone in either orientation, a tablet in either orientation, and the
 * Medium column the detail pane leaves by default. A Wide column the pane
 * narrows to 840dp does not: its head row is short, and from All the ✕
 * unfolds in front of it on the same frames as By You. There, and in a short
 * row of its own, the tests also check the row scrolled, so it is the reveal
 * that brought By You on screen. Device QA still owns the look and the motion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryByYouChipTest {

    private companion object {
        /** Room for By You clear of both fades beside the ✕, not for Playlists before it too. */
        val ShortRowWidth = 240.dp

        /** The row's scroll-aware edge fade (horizontalEdgeFadeOnScroll). */
        val EdgeFadeWidth = 24.dp

        /** Pixel Tablet, whose landscape window the shell and the detail pane share. */
        const val TABLET_LONG_SIDE = 1280
        const val TABLET_SHORT_SIDE = 800

        /** The narrowest column that is still Wide: the head row with the chips in it. */
        val NarrowestWideColumn = 840.dp

        /** A Wide column whose head row has room for By You, until it narrows to [NarrowestWideColumn]. */
        val RoomyWideColumn = 880.dp
    }

    @get:Rule
    val rule = createComposeRule()

    private val byYouPicks = mutableListOf<Boolean>()

    /** The shell column's width beside an open detail pane; `null` is no pane. */
    private var columnWidth by mutableStateOf<Dp?>(null)

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnPhone() {
        setLibrary(windowWidthDp = 400, windowHeightDp = 860)

        assertByYouUnfolds(rowMustScroll = false)
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-land")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnPhoneLandscape() {
        setLibrary(windowWidthDp = 740, windowHeightDp = 360)

        assertByYouUnfolds(rowMustScroll = false)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnTabletPortrait() {
        setLibrary(windowWidthDp = TABLET_SHORT_SIDE, windowHeightDp = TABLET_LONG_SIDE)

        assertByYouUnfolds(rowMustScroll = false)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnTabletLandscape() {
        setLibrary(windowWidthDp = TABLET_LONG_SIDE, windowHeightDp = TABLET_SHORT_SIDE)

        assertByYouUnfolds(rowMustScroll = false)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_unfoldByYouOnScreen_when_detailPaneLeavesDefaultColumn() {
        val window = TABLET_LONG_SIDE.dp
        setLibrary(
            windowWidthDp = TABLET_LONG_SIDE,
            windowHeightDp = TABLET_SHORT_SIDE,
            column = resolvePaneBudget(window, defaultShellFraction(window)).shellWidth,
            expectedColumnMode = LayoutMode.Medium
        )

        assertByYouUnfolds(rowMustScroll = false)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_scrollByYouOnScreen_when_clearChipNarrowsWideColumnAsItUnfolds() {
        setLibrary(
            windowWidthDp = TABLET_LONG_SIDE,
            windowHeightDp = TABLET_SHORT_SIDE,
            column = NarrowestWideColumn,
            expectedColumnMode = LayoutMode.Wide
        )

        assertByYouUnfolds(rowMustScroll = true)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_scrollByYouOnScreen_when_playlistsPickedFromAnotherChipInNarrowWideColumn() {
        setLibrary(
            windowWidthDp = TABLET_LONG_SIDE,
            windowHeightDp = TABLET_SHORT_SIDE,
            column = NarrowestWideColumn,
            expectedColumnMode = LayoutMode.Wide,
            startTab = LibraryTab.Albums
        )

        assertByYouUnfolds(rowMustScroll = true)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_leaveTheRowAlone_when_columnNarrowsAfterByYouArrived() {
        setLibrary(
            windowWidthDp = TABLET_LONG_SIDE,
            windowHeightDp = TABLET_SHORT_SIDE,
            column = RoomyWideColumn,
            expectedColumnMode = LayoutMode.Wide
        )
        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()
        assertEquals("By You fits the roomy column as it is", 0f, chipRow().scrolled)

        // The pane's handle narrows the column: the row is the user's now, so
        // it stays where it was even with By You under its far fade.
        columnWidth = NarrowestWideColumn
        rule.waitForIdle()

        val row = chipRow()
        assertEquals("The row scrolled after By You arrived", 0f, row.scrolled)
        assertTrue("By You still clear of the narrowed row: the test proves nothing", byYouBounds().right > row.fadeEnd)
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun should_offerNoByYou_when_playlistsAreNotMixed() {
        setLibrary(windowWidthDp = 400, windowHeightDp = 860, byYou = PlaylistsByYou(available = false))

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("By You").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun should_scrollByYouClearOfTheEdge_when_rowIsTooShortForIt() {
        var selectedTab by mutableStateOf(LibraryTab.All)
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(ShortRowWidth)) {
                    LibraryFilterRow(
                        tabs = LibraryTab.Chips,
                        selectedTab = selectedTab,
                        label = { it.name },
                        onTabSelected = { selectedTab = it },
                        playlistsByYou = PlaylistsByYou(available = true)
                    )
                }
            }
        }

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        assertByYouClearOfTheEdges(rowMustScroll = true)
    }

    private fun assertByYouUnfolds(rowMustScroll: Boolean) {
        rule.onNodeWithText("By You").assertDoesNotExist()

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        assertByYouClearOfTheEdges(rowMustScroll)
        rule.onNodeWithText("By You")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(listOf(true), byYouPicks)
    }

    /**
     * By You lies whole inside the chip row, past the edge fade at either end.
     * With [rowMustScroll], the row also scrolled to get it there: the row is
     * too short for it, so it is the reveal that brought it on screen.
     */
    private fun assertByYouClearOfTheEdges(rowMustScroll: Boolean) {
        val chip = byYouBounds()
        val row = chipRow()
        // A pixel of slack: the scroll lands on whole pixels.
        assertTrue("By You starts at ${chip.left}, past the fade at ${row.fadeStart}", chip.left >= row.fadeStart - 1f)
        assertTrue("By You ends at ${chip.right}, before the fade at ${row.fadeEnd}", chip.right <= row.fadeEnd + 1f)
        if (rowMustScroll) {
            assertTrue("The row never scrolled: By You fitted without the reveal", row.scrolled > 0f)
        }
    }

    /** By You's whole bounds in the root, unclipped by the row. */
    private fun byYouBounds(): Rect {
        val node = rule.onNodeWithText("By You").fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    /** The scrolling chip row: where its fades begin, in root pixels, and how far it has scrolled. */
    private class ChipRow(val fadeStart: Float, val fadeEnd: Float, val scrolled: Float)

    private fun chipRow(): ChipRow {
        val node = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                hasAnyDescendant(hasText("By You"))
        ).fetchSemanticsNode()
        val fade = with(rule.density) { EdgeFadeWidth.toPx() }
        return ChipRow(
            fadeStart = node.boundsInRoot.left + fade,
            fadeEnd = node.boundsInRoot.right - fade,
            scrolled = node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        )
    }

    /**
     * Library in a [windowWidthDp] × [windowHeightDp] window, on [startTab].
     * With a [column], it is the shell column beside an open detail pane: the
     * page is laid out at the column's width and reads the column's layout
     * mode, as the shell gives it (rememberColumnWindowInfos). [columnWidth]
     * changes it later, as the pane's handle does.
     */
    private fun setLibrary(
        windowWidthDp: Int,
        windowHeightDp: Int,
        column: Dp? = null,
        expectedColumnMode: LayoutMode? = null,
        startTab: LibraryTab = LibraryTab.All,
        byYou: PlaylistsByYou = PlaylistsByYou(available = true)
    ) {
        val playlists = listOf(
            Playlist(MediaId.subsonic("mine"), "Mine", "me", null, null, null, ownedByMe = true),
            Playlist(MediaId.subsonic("theirs"), "Theirs", "them", null, null, null, ownedByMe = false)
        )
        var selectedTab by mutableStateOf(startTab)
        var columnMode: LayoutMode? = null
        columnWidth = column
        val library = @Composable {
            LibraryContent(
                uiState = LibraryUiState.Content(
                    selectedTab = selectedTab,
                    artists = emptyList(),
                    albums = emptyList(),
                    songs = emptyList(),
                    playlists = playlists,
                    favorites = null,
                    searchQuery = "",
                    searchResults = null,
                    isSearching = false,
                    availableTabs = LibraryTab.Chips,
                    allItems = playlists.map(LibraryItem::PlaylistItem),
                    playlistsByYou = byYou
                ),
                onTabSelected = { selectedTab = it },
                onPlaylistsByYouChange = { byYouPicks += it },
                onSearchQueryChanged = {},
                onClearSearch = {},
                onNavigateToSettings = {},
                onArtistClick = {},
                onAlbumClick = {},
                onPlaylistClick = {},
                onSongClick = {},
                onRetry = {},
                coverArtUrlBuilder = null
            )
        }
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = windowWidthDp, heightDp = windowHeightDp) {
                    val width = columnWidth
                    if (width == null) {
                        library()
                    } else {
                        val columnInfo = LocalYoinWindowInfo.current.forPaneWidth(width)
                        columnMode = columnInfo.layoutMode
                        CompositionLocalProvider(LocalYoinWindowInfo provides columnInfo) {
                            Box(
                                Modifier
                                    .width(width)
                                    .fillMaxHeight()
                            ) {
                                library()
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        if (expectedColumnMode != null) {
            assertEquals("The column is $column wide", expectedColumnMode, columnMode)
        }
    }
}
