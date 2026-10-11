package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.gpo.yoin.data.model.MediaId
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

/**
 * Songs keeps a new first row in sight (Spotify's Liked Songs: a new like is
 * filed first, device QA LIB-33): resting at the top, the list shows it there,
 * whether it landed with Songs on or while another view was; scrolled down,
 * the list keeps the user's place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class LibrarySongsHeadTest {

    @get:Rule
    val rule = createComposeRule()

    private val liked = (0 until 40).map { song("s$it", "Song $it") }
    private val newLike = song("new", "New like")

    private var tab by mutableStateOf(LibraryTab.Songs)
    private var songs by mutableStateOf(liked)

    @Test
    fun should_showTheNewFirstRowOnTop_when_itLandsWhileTheListRestsAtTheTop() {
        setLibrary()
        val firstTop = topOf("Song 0")

        songs = listOf(newLike) + liked
        rule.waitForIdle()

        assertEquals("the new row takes the top", firstTop, topOf("New like"), 1f)
        assertTrue("the old first row moved down", topOf("Song 0") > firstTop + 1f)
    }

    @Test
    fun should_showTheNewFirstRowOnTop_when_itLandedWhileAnotherViewWasOn() {
        setLibrary()
        val firstTop = topOf("Song 0")
        tab = LibraryTab.Playlists
        rule.waitForIdle()

        songs = listOf(newLike) + liked
        rule.waitForIdle()
        tab = LibraryTab.Songs
        rule.waitForIdle()

        assertEquals("the new row takes the top", firstTop, topOf("New like"), 1f)
    }

    @Test
    fun should_keepTheUsersPlace_when_aNewFirstRowLandsAfterTheyScrolled() {
        setLibrary()
        rule.onNodeWithTag(ROOT).performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f) }
        rule.waitForIdle()
        // A row mid-screen, well clear of the top.
        val shown = rule.onAllNodes(hasText("Song ", substring = true)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        assertTrue("the list never scrolled", "Song 0" !in shown)
        val held = shown[shown.size / 2]
        val heldTop = topOf(held)

        songs = listOf(newLike) + liked
        rule.waitForIdle()

        assertEquals("the list kept its place", heldTop, topOf(held), 1f)
        rule.onNodeWithText("New like").assertDoesNotExist()
    }

    private fun topOf(title: String): Float = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top

    private fun setLibrary() {
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                    Box(Modifier.fillMaxSize().testTag(ROOT)) {
                        LibraryContent(
                            uiState = LibraryUiState.Content(
                                selectedTab = tab,
                                artists = emptyList(),
                                albums = emptyList(),
                                songs = songs,
                                playlists = emptyList(),
                                favorites = null,
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
                            onSongClick = {},
                            onRetry = {},
                            coverArtUrlBuilder = null
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun song(id: String, title: String) = Track(
        id = MediaId.spotify(id),
        title = title,
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

    private companion object {
        const val ROOT = "root"
    }
}
