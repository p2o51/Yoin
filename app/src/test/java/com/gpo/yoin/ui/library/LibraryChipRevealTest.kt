package com.gpo.yoin.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
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
 * A chip turned on comes to rest whole on screen, clear of the chip row's
 * edge fades (device QA LIB-05/LIB-09 [C], a Compact phone with Subsonic's
 * five chips): the ✕ unfolding in front of the row slides every chip over by
 * its width, which pushed Favorites at the row's end out of sight, and a chip
 * turned on in a scrolled row stayed half under the pinned ✕. Native
 * graphics, so the labels measure their real widths and the row overflows as
 * on the device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp")
class LibraryChipRevealTest {

    @get:Rule
    val rule = createComposeRule()

    private var selectedTab by mutableStateOf(LibraryTab.All)

    @Test
    fun should_keepTheChipOnScreen_when_theLastChipIsTurnedOnFromAll() {
        setLibrary()
        assertTrue("Favorites starts out at the row's end", chipBounds("Favorites").right > chipRow().bounds.right)

        rule.onNodeWithText("Favorites").performClick()
        rule.waitForIdle()

        assertEquals(LibraryTab.Favorites, selectedTab)
        assertClearOfTheEdges("Favorites")
    }

    @Test
    fun should_bringTheChipOutFromUnderTheClearChip_when_turnedOnInAScrolledRow() {
        setLibrary(byYou = PlaylistsByYou(available = false))
        scrollTheRowBy(40f)
        assertTrue("Playlists starts out cut at the start", chipBounds("Playlists").left < chipRow().bounds.left)

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        assertEquals(LibraryTab.Playlists, selectedTab)
        assertClearOfTheEdges("Playlists")
    }

    @Test
    fun should_bringPlaylistsAndByYouOnScreen_when_playlistsIsTurnedOnInAScrolledRow() {
        setLibrary(byYou = PlaylistsByYou(available = true))
        scrollTheRowBy(40f)

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        assertClearOfTheEdges("Playlists")
        assertClearOfTheEdges("By You")
    }

    @Test
    fun should_leaveTheRowAlone_when_aChipOnScreenIsTurnedOn() {
        setLibrary()

        rule.onNodeWithText("Artists").performClick()
        rule.waitForIdle()

        assertEquals("Artists fitted where it was", 0f, chipRow().scrolled)
        assertClearOfTheEdges("Artists")
    }

    /**
     * The chip lies whole inside the row, past the edge fade at either end —
     * a fade that shows: none at the start of a row scrolled all the way back,
     * none at the end of one scrolled all the way on.
     */
    private fun assertClearOfTheEdges(label: String) {
        val chip = chipBounds(label)
        val row = chipRow()
        val fade = with(rule.density) { 24.dp.toPx() }
        val start = row.bounds.left + if (row.scrolled > 0f) fade else 0f
        val end = row.bounds.right - if (row.scrolled < row.maxScroll) fade else 0f
        // A pixel of slack: the scroll lands on whole pixels.
        assertTrue("$label starts at ${chip.left}, before $start", chip.left >= start - 1f)
        assertTrue("$label ends at ${chip.right}, past $end", chip.right <= end + 1f)
    }

    /** The chip's whole bounds in the root, unclipped by the row. */
    private fun chipBounds(label: String): Rect {
        val node = rule.onNodeWithText(label).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private class ChipRow(val bounds: Rect, val scrolled: Float, val maxScroll: Float)

    private fun chipRowNode(): SemanticsNodeInteraction = rule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("Favorites"))
    )

    private fun chipRow(): ChipRow {
        val node = chipRowNode().fetchSemanticsNode()
        val range = node.config[SemanticsProperties.HorizontalScrollAxisRange]
        return ChipRow(node.boundsInRoot, range.value(), range.maxValue())
    }

    private fun scrollTheRowBy(dp: Float) {
        val px = with(rule.density) { dp.dp.toPx() }
        chipRowNode().performSemanticsAction(SemanticsActions.ScrollBy) { it(px, 0f) }
        rule.waitForIdle()
    }

    private fun setLibrary(byYou: PlaylistsByYou = PlaylistsByYou(available = true)) {
        val playlists = listOf(
            Playlist(MediaId.subsonic("mine"), "Mine", "me", null, null, null, ownedByMe = true),
            Playlist(MediaId.subsonic("theirs"), "Theirs", "them", null, null, null, ownedByMe = false)
        )
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = 411, heightDp = 914) {
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
            }
        }
        rule.waitForIdle()
    }
}
