package com.gpo.yoin.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pull itself, on the real Library page: a view dragged down from its top
 * asks for a refresh once, whatever it shows (a grid, a list, an empty
 * state); a pull let go short, a list scrolled away from its top, and a page
 * with no refresh wired ask for none. What the refresh reads is
 * [LibraryPullRefreshTest]'s; how it looks is device QA's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class LibraryPullGestureTest {

    @get:Rule
    val rule = createComposeRule()

    private var pulls = 0

    @Test
    fun should_askForARefreshOnce_when_allsGridIsPulledDown() {
        setLibrary(LibraryTab.All)

        pullDown()

        assertEquals(1, pulls)
        // The grid is where it was.
        rule.onNodeWithText("Album 0").assertExists()
    }

    @Test
    fun should_askForARefresh_when_aListIsPulledDown() {
        setLibrary(LibraryTab.Playlists)

        pullDown()

        assertEquals(1, pulls)
    }

    @Test
    fun should_askForARefresh_when_anEmptyViewIsPulledDown() {
        setLibrary(LibraryTab.Playlists, playlists = emptyList())
        rule.onNodeWithText("No playlists").assertExists()

        pullDown()

        assertEquals(1, pulls)
    }

    @Test
    fun should_askForNothing_when_thePullIsLetGoShort() {
        setLibrary(LibraryTab.All)

        rule.onNodeWithTag(LIBRARY_VIEW_TAG).performTouchInput { swipeDown(startY = top + 40f, endY = top + 120f) }
        rule.waitForIdle()

        assertEquals(0, pulls)
    }

    @Test
    fun should_scrollBackUpInstead_when_theListIsAwayFromItsTop() {
        setLibrary(LibraryTab.Playlists)
        rule.onNodeWithTag(LIBRARY_VIEW_TAG).performTouchInput { swipeUp(startY = bottom - 40f, endY = top + 40f) }
        rule.waitForIdle()
        rule.onNodeWithText("Playlist 0").assertDoesNotExist()

        rule.onNodeWithTag(LIBRARY_VIEW_TAG).performTouchInput { swipeDown(startY = top + 40f, endY = top + 340f) }
        rule.waitForIdle()

        assertEquals(0, pulls)
    }

    @Test
    fun should_askForNothing_when_noRefreshIsWired() {
        setLibrary(LibraryTab.All, wired = false)

        pullDown()

        assertEquals(0, pulls)
        rule.onNodeWithText("Album 0").assertExists()
    }

    private fun pullDown() {
        rule.onNodeWithTag(LIBRARY_VIEW_TAG).performTouchInput { swipeDown(startY = top + 40f, endY = bottom - 40f) }
        rule.waitForIdle()
    }

    private fun setLibrary(
        tab: LibraryTab,
        playlists: List<Playlist> = (0 until 60).map { i ->
            Playlist(MediaId.subsonic("pl$i"), "Playlist $i", "me", null, null, null)
        },
        wired: Boolean = true,
    ) {
        val albums = (0 until 6).map { i ->
            Album(
                id = MediaId.subsonic("al$i"),
                name = "Album $i",
                artist = "Someone",
                artistId = null,
                coverArt = null,
                songCount = null,
                durationSec = null,
                year = null,
                genre = null
            )
        }
        rule.setContent {
            YoinTheme {
                LibraryContent(
                    uiState = LibraryUiState.Content(
                        selectedTab = tab,
                        artists = emptyList(),
                        albums = albums,
                        songs = emptyList(),
                        playlists = playlists,
                        favorites = null,
                        searchQuery = "",
                        searchResults = null,
                        isSearching = false,
                        allItems = albums.map(LibraryItem::AlbumItem)
                    ),
                    onTabSelected = {},
                    onSearchQueryChanged = {},
                    onClearSearch = {},
                    onNavigateToSettings = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    onPlaylistClick = {},
                    onSongClick = {},
                    onRetry = {},
                    onRefresh = if (wired) ({ pulls += 1 }) else null,
                    coverArtUrlBuilder = null
                )
            }
        }
        rule.waitForIdle()
    }
}
