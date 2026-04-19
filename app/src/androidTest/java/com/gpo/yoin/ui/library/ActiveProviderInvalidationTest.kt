package com.gpo.yoin.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.ui.sampleLibraryState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class ActiveProviderInvalidationTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun provider_change_recomposes_library_tabs_without_stale_playlists_tab() {
        lateinit var updateState: (LibraryUiState.Content) -> Unit

        rule.setContent {
            var state by mutableStateOf(sampleLibraryState(selectedTab = LibraryTab.Playlists))
            updateState = { state = it }
            YoinTheme {
                LibraryContent(
                    uiState = state,
                    onTabSelected = {},
                    onSearchQueryChanged = {},
                    onClearSearch = {},
                    onNavigateToSettings = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    onPlaylistClick = {},
                    onSongClick = {},
                    onRetry = {},
                    coverArtUrlBuilder = null,
                )
            }
        }

        rule.onNodeWithText("Playlists").assertIsDisplayed()

        rule.runOnIdle {
            updateState(
                sampleLibraryState(
                    selectedTab = LibraryTab.Artists,
                    availableTabs = listOf(
                        LibraryTab.Artists,
                        LibraryTab.Albums,
                        LibraryTab.Songs,
                    ),
                ),
            )
        }

        rule.onNodeWithText("Artists").assertIsDisplayed()
        rule.onAllNodesWithText("Playlists").assertCountEquals(0)
    }
}
