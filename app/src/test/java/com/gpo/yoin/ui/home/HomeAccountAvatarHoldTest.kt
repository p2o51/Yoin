package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's header avatar through a switch begun from Home (T7): the target from
 * the tap, held while the feed goes to Loading, handed back to the header
 * once the next feed is in — or, failed, sprung back to the account in use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h720dp")
class HomeAccountAvatarHoldTest {

    @get:Rule
    val rule = createComposeRule()

    private var uiState by mutableStateOf<HomeUiState>(feedOf("a"))
    private var account by mutableStateOf(A)
    private var pending by mutableStateOf(false)

    private fun setHome() {
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
                        buildCoverArtUrl = { "" },
                        activeAccount = account,
                        accountSwitchPending = pending,
                        accountAnchor = ANCHOR
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HOME_ACCOUNT_HOLD_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** The tap: B pends and Home's feed goes to Loading (HomeViewModel's hold). */
    private fun beginSwitchToB() {
        rule.runOnIdle {
            pending = true
            account = B
            uiState = HomeUiState.Loading
        }
        rule.waitForIdle()
    }

    private fun standIn() = rule.onNodeWithTag(HOME_ACCOUNT_HOLD_TAG, useUnmergedTree = true)

    private fun shows(id: String) = SemanticsMatcher.expectValue(HomeAccountShownKey, id)

    private fun headerAvatar(title: String) = rule.onNodeWithContentDescription("Account: $title")

    @Test
    fun should_holdTheTargetsAvatar_when_theFeedGoesToLoading() {
        setHome()

        beginSwitchToB()

        standIn().assert(shows("b"))
        // On the header avatar's spot, where the switch's flight lands.
        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("at $center", (center - ANCHOR.center).getDistance() < NEAR_PX)
    }

    @Test
    fun should_handTheAvatarBackToTheHeader_when_theSwitchLands() {
        setHome()
        beginSwitchToB()

        rule.runOnIdle {
            pending = false
            account = B.copy(isActive = true)
            uiState = feedOf("b")
        }
        rule.waitForIdle()

        standIn().assertDoesNotExist()
        headerAvatar("Spotify").assertIsEnabled()
    }

    @Test
    fun should_springBackToTheAccountInUse_when_theSwitchFails() {
        setHome()
        beginSwitchToB()
        // While held, the header's own avatar waits (disabled) under the stand-in.
        rule.runOnIdle { uiState = feedOf("a") }
        rule.waitForIdle()
        headerAvatar("Spotify").assertIsNotEnabled()
        standIn().assert(shows("b"))

        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            pending = false
            account = A
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()

        // Giving way to the account in use, still in the header's spot.
        standIn().assert(shows("a"))

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        standIn().assertDoesNotExist()
        headerAvatar("qa").assertIsEnabled()
    }

    @Test
    fun should_fadeTheStandInOut_when_theNextAccountsFeedFails() {
        setHome()
        beginSwitchToB()

        rule.runOnIdle {
            pending = false
            account = B.copy(isActive = true)
            uiState = HomeUiState.Error("Failed to connect to server")
        }
        rule.waitForIdle()

        standIn().assertDoesNotExist()
    }

    private fun feedOf(profileId: String) = HomeUiState.Content(
        activities = emptyList(),
        recentlyPlayed = List(3) { index -> album("$profileId-played-$index") },
        ownerProfileId = profileId
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
        const val NEAR_PX = 2f

        val ANCHOR = Rect(320f, 60f, 352f, 92f)

        val A = ProfileCard(
            id = "a",
            displayName = "qa",
            subtitle = "music.example.com",
            provider = ProviderKind.SUBSONIC,
            isActive = true
        )
        val B = ProfileCard(
            id = "b",
            displayName = "Spotify",
            subtitle = null,
            provider = ProviderKind.SPOTIFY,
            isActive = false,
            avatarShape = 1
        )
    }
}
