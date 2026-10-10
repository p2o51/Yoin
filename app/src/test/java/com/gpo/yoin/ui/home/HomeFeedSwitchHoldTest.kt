package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q14b's hold in the page: Loading stands over an account's feed while a
 * switch runs; a failed switch brings that feed back where it was, the next
 * account's feed starts at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h360dp")
class HomeFeedSwitchHoldTest {

    @get:Rule
    val rule = createComposeRule()

    private var uiState by mutableStateOf<HomeUiState>(HomeUiState.Loading)

    private fun setHome(first: HomeUiState) {
        uiState = first
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    HomeContent(
                        uiState = uiState,
                        isPlaying = false,
                        playbackSignal = 0f,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        onRetry = {},
                        buildCoverArtUrl = { "" }
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** Scroll the feed to its end, then let Loading take the page until the feed has left. */
    private fun scrollDownThenHold() {
        rule.onNode(hasScrollToNodeAction() and IS_VERTICAL).performScrollToNode(hasText(EDIT_HOME))
        rule.waitForIdle()
        assertTrue(shown(EDIT_HOME))
        assertFalse(shown(RECENTLY_PLAYED))

        rule.runOnIdle { uiState = HomeUiState.Loading }
        rule.waitForIdle()
        rule.onNodeWithText(EDIT_HOME, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun shown(text: String): Boolean =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
            rule.onNodeWithText(text, useUnmergedTree = true).isDisplayed()

    @Test
    fun should_bringTheFeedBackWhereItWas_when_aSwitchFails() {
        val feed = feedOf("a")
        setHome(feed)
        scrollDownThenHold()

        rule.runOnIdle { uiState = feed }
        rule.waitForIdle()

        assertTrue(shown(EDIT_HOME))
        assertFalse(shown(RECENTLY_PLAYED))
    }

    @Test
    fun should_startTheNextAccountsFeedAtTheTop_when_aSwitchLands() {
        setHome(feedOf("a"))
        scrollDownThenHold()

        rule.runOnIdle { uiState = feedOf("b") }
        rule.waitForIdle()

        assertFalse(shown(EDIT_HOME))
    }

    private fun feedOf(profileId: String) = HomeUiState.Content(
        activities = emptyList(),
        recentlyPlayed = List(4) { index -> album("$profileId-played-$index") },
        playlists = List(4) { index -> playlist("$profileId-list-$index") },
        ownerProfileId = profileId
    )

    private fun playlist(rawId: String) = Playlist(
        id = MediaId.subsonic(rawId),
        name = "Playlist $rawId",
        owner = "owner",
        coverArt = null,
        songCount = 5,
        durationSec = 600
    )

    private fun album(rawId: String) = Album(
        id = MediaId.subsonic(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null
    )

    private companion object {
        val IS_VERTICAL = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        const val EDIT_HOME = "Edit Home"
        const val RECENTLY_PLAYED = "Recently Played"
    }
}
