package com.gpo.yoin.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.ui.sampleLibraryState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class LazyItemKeySaveableRegressionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun library_content_restores_with_mediaid_backed_lists_without_crashing() {
        val restorationTester = StateRestorationTester(rule)

        restorationTester.setContent {
            YoinTheme {
                LibraryContent(
                    uiState = sampleLibraryState(selectedTab = LibraryTab.Artists),
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

        rule.onNodeWithText("Artist artist-a").assertIsDisplayed()
        restorationTester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Artist artist-a").assertIsDisplayed()
    }
}
