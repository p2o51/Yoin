package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Playlists' New playlist button names itself to accessibility services:
 * M3's extended FAB keeps its label row out of the tree, so the icon carries
 * the name — expanded, and folded to the icon once the list scrolls down.
 * Native graphics, so the label measures its real width and the fold shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class LibraryPlaylistsFabTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_nameTheNewPlaylistButton_when_itShowsItsLabel() {
        setPlaylists()

        newPlaylistButton()
            .assertExists()
            // Named once: M3 keeps the drawn label out of the button's name.
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    @Test
    fun should_keepTheNewPlaylistButtonsName_when_scrollingFoldsItToTheIcon() {
        setPlaylists()
        val expandedWidth = newPlaylistButton().fetchSemanticsNode().size.width

        rule.onNodeWithTag(ROOT).performTouchInput { swipeUp(startY = centerY, endY = top + 10f) }
        rule.waitForIdle()

        // Folded to the icon: M3 keeps the label out of the tree either way, so
        // only the button's width tells the two states apart.
        val foldedWidth = newPlaylistButton().fetchSemanticsNode().size.width
        assertTrue(
            "the button should fold to its icon: $foldedWidth px of $expandedWidth px expanded",
            foldedWidth < expandedWidth * FOLDED_WIDTH_SHARE
        )
        newPlaylistButton().assertExists()
    }

    private fun newPlaylistButton(): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(NEW_PLAYLIST) and hasClickAction())

    private fun setPlaylists() {
        val playlists = (0 until 60).map { i ->
            Playlist(MediaId.subsonic("pl$i"), "Playlist $i", "me", null, null, null)
        }
        rule.setContent {
            YoinTheme {
                Box(Modifier.fillMaxSize().testTag(ROOT)) {
                    LibraryContent(
                        uiState = LibraryUiState.Content(
                            selectedTab = LibraryTab.Playlists,
                            artists = emptyList(),
                            albums = emptyList(),
                            songs = emptyList(),
                            playlists = playlists,
                            favorites = null,
                            searchQuery = "",
                            searchResults = null,
                            isSearching = false,
                            canCreatePlaylists = true
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
        rule.waitForIdle()
    }

    private companion object {
        const val ROOT = "root"
        const val NEW_PLAYLIST = "New playlist"

        /** Folded to the icon, the button is about a third of its labelled width (56 of 149dp). */
        const val FOLDED_WIDTH_SHARE = 0.6f
    }
}
