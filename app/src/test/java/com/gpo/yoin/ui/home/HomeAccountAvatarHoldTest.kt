package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
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
    private var anchor by mutableStateOf(ANCHOR)
    private var covered by mutableStateOf(false)
    private var width by mutableStateOf(400.dp)

    private fun setHome(direction: LayoutDirection = LayoutDirection.Ltr) {
        rule.setContent {
            CompositionLocalProvider(
                LocalMotionProfile provides MotionProfile.AdaptiveReduced,
                LocalLayoutDirection provides direction
            ) {
                YoinTheme {
                    Box(modifier = Modifier.width(width).fillMaxHeight()) {
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
                            homeCovered = covered,
                            onRetry = {},
                            buildCoverArtUrl = { "" },
                            activeAccount = account,
                            accountSwitchPending = pending,
                            accountAnchor = anchor
                        )
                    }
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

    /** B lands, and its feed comes in. */
    private fun landOnB() {
        rule.runOnIdle {
            pending = false
            account = B.copy(isActive = true)
            uiState = feedOf("b")
        }
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun standIn() = rule.onNodeWithTag(HOME_ACCOUNT_HOLD_TAG, useUnmergedTree = true)

    private fun standInUp(): Boolean =
        rule.onAllNodes(SemanticsMatcher.expectValue(HomeAccountHoldUpKey, true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

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

        landOnB()
        rule.waitForIdle()

        standIn().assertDoesNotExist()
        headerAvatar("Spotify").assertIsEnabled()
    }

    @Test
    fun should_bringTheHeadersAvatarUpUnderTheStandIn_when_handingBack() {
        setHome()
        beginSwitchToB()
        rule.mainClock.autoAdvance = false

        landOnB()
        // Frame by frame until the header's own avatar is back: the stand-in
        // is still there over it, fading off (no one-frame swap).
        var frames = 0
        while (!headerEnabled("Spotify") && frames++ < MAX_FRAMES) frame()

        headerAvatar("Spotify").assertIsEnabled()
        standIn().assertExists().assert(shows("b"))

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        standIn().assertDoesNotExist()
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
        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("at $center", (center - ANCHOR.center).getDistance() < NEAR_PX)

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

    @Test
    fun should_letTheStandInGo_when_theWindowChangesWithNoHeaderOnThePage() {
        setHome()
        beginSwitchToB()
        assertTrue(standInUp())

        // Rotated, unfolded or resized during Loading: the header isn't there
        // to say where it is now, so the stand-in doesn't keep the old spot.
        rule.runOnIdle { width = 360.dp }
        rule.waitForIdle()
        assertTrue("stand-in lets the spot go", !standInUp())

        // The next feed brings the header's own avatar in with it.
        landOnB()
        rule.waitForIdle()
        standIn().assertDoesNotExist()
        headerAvatar("Spotify").assertIsEnabled()
    }

    @Test
    fun should_followTheHeader_when_theWindowChangesWithTheHeaderOnThePage() {
        setHome()
        // The tap's frame: the feed (and its header) is still on the page.
        rule.runOnIdle {
            pending = true
            account = B
        }
        rule.waitForIdle()

        rule.runOnIdle {
            width = 360.dp
            anchor = MOVED
        }
        rule.waitForIdle()

        // The header said where it went: the stand-in is there, still up.
        assertTrue(standInUp())
        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("at $center", (center - MOVED.center).getDistance() < NEAR_PX)
    }

    @Test
    fun should_handBackAtOnce_when_theFeedStopsResting() {
        setHome()
        beginSwitchToB()
        rule.mainClock.autoAdvance = false

        // B's header is somewhere else: the stand-in glides there…
        rule.runOnIdle {
            pending = false
            account = B.copy(isActive = true)
            uiState = feedOf("b")
            anchor = MOVED
        }
        frame()
        frame()
        headerAvatar("Spotify").assertIsNotEnabled()

        // …but the feed under it stops resting (a scroll, a pull, something
        // over Home): it hands back now instead of chasing the header.
        rule.runOnIdle { covered = true }
        frame()
        frame()

        headerAvatar("Spotify").assertIsEnabled()
        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("short of the spot (at $center)", (center - MOVED.center).getDistance() > NEAR_PX)
    }

    @Test
    fun should_holdTheHeadersSpot_when_theLayoutIsRightToLeft() {
        // Window px are absolute: an RTL layout doesn't mirror the stand-in.
        setHome(direction = LayoutDirection.Rtl)

        beginSwitchToB()

        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("at $center", (center - ANCHOR.center).getDistance() < NEAR_PX)
    }

    @Test
    fun should_measureTheWholeAvatar_when_itsHeaderIsHalfScrolledAway() {
        var reported: Rect? = null
        rule.setContent {
            // A list's viewport, and an avatar half scrolled up out of it.
            Box(modifier = Modifier.padding(top = 100.dp).size(200.dp).clipToBounds()) {
                Box(
                    modifier = Modifier
                        .offset(y = (-16).dp)
                        .size(32.dp)
                        .onGloballyPositioned { reported = it.unclippedBoundsInWindow() }
                )
            }
        }
        rule.waitForIdle()

        // Where it draws, not the half the viewport shows: avatars land on its centre.
        val bounds = checkNotNull(reported)
        with(rule.density) {
            assertEquals(32.dp.toPx(), bounds.height, 0.5f)
            assertEquals(84.dp.toPx(), bounds.top, 0.5f)
        }
    }

    /** The header's own avatar is on the page and taking taps (not stepped aside). */
    private fun headerEnabled(title: String): Boolean =
        rule.onAllNodes(hasContentDescription("Account: $title")).fetchSemanticsNodes()
            .any { SemanticsProperties.Disabled !in it.config }

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
        const val MAX_FRAMES = 120

        val ANCHOR = Rect(320f, 60f, 352f, 92f)
        val MOVED = Rect(300f, 400f, 332f, 432f)

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
