package com.gpo.yoin.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All mixes artists, albums and playlists (no songs); the ✕ and a re-tap clear a chip; the sort row's menu. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class LibraryAllViewTest {

    @get:Rule
    val rule = createComposeRule()

    private val artist = Artist(MediaId.subsonic("ar"), "Artist one", albumCount = null, coverArt = null)
    private val album = Album(
        id = MediaId.subsonic("al"),
        name = "Album one",
        artist = "Album artist",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null
    )
    private val playlist = Playlist(
        MediaId.subsonic("pl"),
        "Playlist one",
        owner = "Owner",
        coverArt = null,
        songCount = null,
        durationSec = null
    )
    private val song = Track(
        id = MediaId.subsonic("so"),
        title = "Song one",
        artist = "Artist one",
        artistId = null,
        album = "Album one",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private val tabsPicked = mutableListOf<LibraryTab>()
    private val sortsPicked = mutableListOf<Pair<LibraryTab, LibrarySort>>()

    private fun setLibrary(tab: LibraryTab) {
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                    LibraryContent(
                        uiState = LibraryUiState.Content(
                            selectedTab = tab,
                            artists = listOf(artist),
                            albums = listOf(album),
                            songs = listOf(song),
                            playlists = listOf(playlist),
                            favorites = null,
                            searchQuery = "",
                            searchResults = null,
                            isSearching = false,
                            availableTabs = listOf(
                                LibraryTab.Playlists,
                                LibraryTab.Artists,
                                LibraryTab.Albums,
                                LibraryTab.Songs
                            ),
                            allItems = listOf(
                                LibraryItem.PlaylistItem(playlist),
                                LibraryItem.AlbumItem(album),
                                LibraryItem.ArtistItem(artist)
                            ),
                            sorts = mapOf(
                                LibraryTab.All to LibrarySort.Recents,
                                LibraryTab.Albums to LibrarySort.Recents
                            ),
                            sortOptions = mapOf(
                                LibraryTab.All to LibrarySort.entries,
                                LibraryTab.Albums to LibrarySort.entries
                            )
                        ),
                        onTabSelected = { tabsPicked += it },
                        onSortSelected = { view, sort -> sortsPicked += view to sort },
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

    @Test
    fun should_showArtistsAlbumsAndPlaylistsButNoSongs_when_allIsOn() {
        setLibrary(LibraryTab.All)

        rule.onNodeWithText("Artist one").assertExists()
        rule.onNodeWithText("Album one").assertExists()
        rule.onNodeWithText("Playlist one").assertExists()
        rule.onNodeWithText("Song one").assertDoesNotExist()
    }

    @Test
    fun should_offerNoClear_when_noChipIsOn() {
        setLibrary(LibraryTab.All)

        rule.onNodeWithContentDescription("Clear filter").assertDoesNotExist()
        rule.onNodeWithText("Albums").performClick()

        assertEquals(listOf(LibraryTab.Albums), tabsPicked)
    }

    @Test
    fun should_returnToAll_when_clearTappedOrChipTappedAgain() {
        setLibrary(LibraryTab.Albums)

        rule.onNodeWithContentDescription("Clear filter").performClick()
        rule.onNodeWithText("Albums").performClick()
        rule.onNodeWithText("Artists").performClick()

        assertEquals(listOf(LibraryTab.All, LibraryTab.All, LibraryTab.Artists), tabsPicked)
    }

    @Test
    fun should_pickTheViewsOrder_when_sortMenuItemTapped() {
        setLibrary(LibraryTab.All)

        rule.onNodeWithText("Recents").performClick()
        rule.onNodeWithText("Alphabetical").performClick()

        assertEquals(listOf(LibraryTab.All to LibrarySort.Alphabetical), sortsPicked)
    }

    @Test
    fun should_showNoSortRow_when_viewHasOneOrder() {
        setLibrary(LibraryTab.Songs)

        rule.onNodeWithText("Song one").assertExists()
        rule.onNodeWithText("Recents").assertDoesNotExist()
    }
}
