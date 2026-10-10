package com.gpo.yoin.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Songs read a page at a time: the list asks for more near its end, and its foot retries a failed read. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class LibrarySongsFootTest {

    @get:Rule
    val rule = createComposeRule()

    private var moreAsked = 0

    /** The rows on hand and what follows them, as the view model publishes them. */
    private var shown by mutableStateOf(0 to LibrarySongsMore.None)

    private fun setSongs(count: Int, more: LibrarySongsMore) {
        shown = count to more
        rule.setContent {
            val (rows, foot) = shown
            YoinTheme {
                ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                    LibraryContent(
                        uiState = LibraryUiState.Content(
                            selectedTab = LibraryTab.Songs,
                            artists = emptyList(),
                            albums = emptyList(),
                            songs = (1..rows).map { song("s$it") },
                            playlists = emptyList(),
                            favorites = null,
                            searchQuery = "",
                            searchResults = null,
                            isSearching = false,
                            songsMore = foot
                        ),
                        onTabSelected = {},
                        onSearchQueryChanged = {},
                        onClearSearch = {},
                        onLoadMoreSongs = { moreAsked += 1 },
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

    @Test
    fun should_askForMoreSongs_when_theListShowsItsEnd() {
        setSongs(count = 4, more = LibrarySongsMore.Available)

        assertEquals(1, moreAsked)
    }

    @Test
    fun should_notAskForMoreSongs_when_theListIsFarFromItsEnd() {
        setSongs(count = 200, more = LibrarySongsMore.Available)

        assertEquals(0, moreAsked)
    }

    @Test
    fun should_notAskForMoreSongs_when_theListIsWhole() {
        setSongs(count = 4, more = LibrarySongsMore.None)

        assertEquals(0, moreAsked)
        rule.onNodeWithContentDescription("Retry").assertDoesNotExist()
    }

    @Test
    fun should_notAskForMoreSongs_when_aPageIsBeingRead() {
        setSongs(count = 4, more = LibrarySongsMore.Loading)

        assertEquals(0, moreAsked)
    }

    @Test
    fun should_askAgain_when_aPageLeavesTheListStillShort() {
        setSongs(count = 4, more = LibrarySongsMore.Available)
        assertEquals(1, moreAsked)

        // The view model reads the page, then hands back a list still within a screen of its end.
        shown = 4 to LibrarySongsMore.Loading
        rule.waitForIdle()
        assertEquals(1, moreAsked)
        shown = 8 to LibrarySongsMore.Available
        rule.waitForIdle()

        assertEquals(2, moreAsked)
    }

    @Test
    fun should_readAgainOnlyFromRetry_when_theLastReadFailed() {
        setSongs(count = 4, more = LibrarySongsMore.Failed)
        // Being at the end doesn't ask again after a failure.
        assertEquals(0, moreAsked)

        rule.onNodeWithContentDescription("Retry").performClick()

        assertEquals(1, moreAsked)
    }

    private fun song(id: String) = Track(
        id = MediaId.subsonic(id),
        title = "Song $id",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
