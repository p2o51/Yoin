package com.gpo.yoin.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * All at the widths LibraryAllViewTest's phone doesn't cover: the tablet's two
 * postures (Medium portrait, Expanded landscape, where the chips join the
 * head row) and a landscape phone. Each shows the three kinds, the sort row
 * and chips that turn on and clear. Device QA still owns the look.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryAllLayoutsTest {

    @get:Rule
    val rule = createComposeRule()

    private val tabsPicked = mutableListOf<LibraryTab>()

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_mixKindsWithSortRowAndChips_when_tabletPortrait() = assertAllWorks(widthDp = 800, heightDp = 1280)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_mixKindsWithSortRowAndChips_when_tabletLandscape() = assertAllWorks(widthDp = 1280, heightDp = 800)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun should_mixKindsWithSortRowAndChips_when_phoneLandscape() = assertAllWorks(widthDp = 915, heightDp = 412)

    private fun assertAllWorks(widthDp: Int, heightDp: Int) {
        setLibrary(LibraryTab.All, widthDp, heightDp)

        rule.onNodeWithText("Artist one").assertExists()
        rule.onNodeWithText("Album one").assertExists()
        rule.onNodeWithText("Playlist one").assertExists()
        rule.onNodeWithText("Recents").assertExists()
        rule.onNodeWithContentDescription("Clear filter").assertDoesNotExist()
        rule.onNodeWithText("Albums").performClick()
        assertEquals(listOf(LibraryTab.Albums), tabsPicked)
    }

    private fun setLibrary(tab: LibraryTab, widthDp: Int, heightDp: Int) {
        val artist = Artist(MediaId.subsonic("ar"), "Artist one", albumCount = null, coverArt = null)
        val album = Album(
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
        val playlist = Playlist(MediaId.subsonic("pl"), "Playlist one", "Owner", null, null, null)
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
                    LibraryContent(
                        uiState = LibraryUiState.Content(
                            selectedTab = tab,
                            artists = listOf(artist),
                            albums = listOf(album),
                            songs = emptyList(),
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
                            sorts = mapOf(LibraryTab.All to LibrarySort.Recents),
                            sortOptions = mapOf(LibraryTab.All to LibrarySort.entries)
                        ),
                        onTabSelected = { tabsPicked += it },
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
