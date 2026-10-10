package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
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

/**
 * By You unfolds after Playlists, on screen, in every layout the chip row has:
 * its own row on a phone and a tablet in portrait, the head row on a tablet in
 * landscape, on a narrow Wide window and on a landscape phone. Robolectric's
 * text is all but zero-width, so a short row of its own stands in for a row
 * that has to scroll By You into view. Device QA still owns the look and the
 * motion.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryByYouChipTest {

    private companion object {
        val ShortRowWidth = 150.dp

        /** The row's scroll-aware edge fade (horizontalEdgeFadeOnScroll). */
        val EdgeFadeWidth = 24.dp
    }

    @get:Rule
    val rule = createComposeRule()

    private val byYouPicks = mutableListOf<Boolean>()

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnPhone() = assertByYouUnfolds(widthDp = 400, heightDp = 860)

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnTabletPortrait() =
        assertByYouUnfolds(widthDp = 800, heightDp = 1280)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnTabletLandscape() =
        assertByYouUnfolds(widthDp = 1280, heightDp = 800)

    @Test
    @Config(qualifiers = "w840dp-h900dp-land")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnNarrowWide() =
        assertByYouUnfolds(widthDp = 840, heightDp = 900)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land")
    fun should_unfoldByYouOnScreen_when_playlistsPickedOnPhoneLandscape() =
        assertByYouUnfolds(widthDp = 740, heightDp = 360)

    @Test
    @Config(qualifiers = "w400dp-h860dp")
    fun should_offerNoByYou_when_playlistsAreNotMixed() {
        setLibrary(widthDp = 400, heightDp = 860, byYou = PlaylistsByYou(available = false))

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
                // Room for the ✕ and Playlists, not for By You after it.
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

        val chip = rule.onNodeWithText("By You").fetchSemanticsNode()
        val row = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and hasAnyDescendant(hasText("By You"))
        ).fetchSemanticsNode().boundsInRoot
        val fade = with(rule.density) { EdgeFadeWidth.toPx() }
        val left = chip.positionInRoot.x
        val right = left + chip.size.width
        // A pixel of slack: the scroll lands on whole pixels.
        assertTrue("By You starts at $left, the row at ${row.left}", left >= row.left + fade - 1f)
        assertTrue("By You ends at $right, the row at ${row.right}", right <= row.right - fade + 1f)
    }

    private fun assertByYouUnfolds(widthDp: Int, heightDp: Int) {
        setLibrary(widthDp, heightDp, byYou = PlaylistsByYou(available = true))
        rule.onNodeWithText("By You").assertDoesNotExist()

        rule.onNodeWithText("Playlists").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("By You")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(listOf(true), byYouPicks)
    }

    private fun setLibrary(widthDp: Int, heightDp: Int, byYou: PlaylistsByYou) {
        val playlists = listOf(
            Playlist(MediaId.subsonic("mine"), "Mine", "me", null, null, null, ownedByMe = true),
            Playlist(MediaId.subsonic("theirs"), "Theirs", "them", null, null, null, ownedByMe = false)
        )
        var selectedTab by mutableStateOf(LibraryTab.All)
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
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
            }
        }
        rule.waitForIdle()
    }
}
