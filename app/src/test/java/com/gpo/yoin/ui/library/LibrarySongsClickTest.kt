package com.gpo.yoin.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Songs-tab row plays the whole list from itself; other song rows keep their own callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class LibrarySongsClickTest {

    @get:Rule
    val rule = createComposeRule()

    private val songs = listOf(song("a"), song("b"), song("c"), song("d"))
    private val favorite = song("fav")

    private val singles = mutableListOf<Track>()
    private val listStarts = mutableListOf<Triple<Track, List<Track>, Int>>()
    private val favoriteStarts = mutableListOf<Triple<Track, List<Track>, Int>>()

    private fun setLibrary(tab: LibraryTab) {
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                    LibraryContent(
                        uiState = LibraryUiState.Content(
                            selectedTab = tab,
                            artists = emptyList(),
                            albums = emptyList(),
                            songs = songs,
                            playlists = emptyList(),
                            favorites = Starred(tracks = listOf(favorite)),
                            searchQuery = "",
                            searchResults = null,
                            isSearching = false
                        ),
                        onTabSelected = {},
                        onSearchQueryChanged = {},
                        onClearSearch = {},
                        onNavigateToSettings = {},
                        onArtistClick = {},
                        onAlbumClick = {},
                        onPlaylistClick = {},
                        onSongClick = { singles += it },
                        onFavoriteSongClick = { track, queue, index -> favoriteStarts += Triple(track, queue, index) },
                        onSongsListClick = { track, queue, index -> listStarts += Triple(track, queue, index) },
                        onRetry = {},
                        coverArtUrlBuilder = null
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun should_startFromClickedRow_when_songsRowTapped() {
        setLibrary(LibraryTab.Songs)

        rule.onNodeWithText("Song c").performClick()

        assertEquals(listOf(Triple(songs[2], songs, 2)), listStarts)
        assertTrue(singles.isEmpty())
        assertTrue(favoriteStarts.isEmpty())
    }

    @Test
    fun should_keepFavoritesCallback_when_favoriteSongTapped() {
        setLibrary(LibraryTab.Favorites)

        rule.onNodeWithText("Song fav").performClick()

        assertEquals(listOf(Triple(favorite, listOf(favorite), 0)), favoriteStarts)
        assertTrue(listStarts.isEmpty())
        assertTrue(singles.isEmpty())
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
