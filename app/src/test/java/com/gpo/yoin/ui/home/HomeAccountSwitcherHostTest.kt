package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HomeScreen's side of a switch begun from Home (T7), wired as HomeScreen
 * wires it: the pick pends the target, the card closes from the picked row
 * into the header as the target, and the stand-in waits for the card's window
 * to go before it shows — then holds the target, or springs back to the
 * account in use if the switch fails, and Home says it couldn't switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class HomeAccountSwitcherHostTest {

    @get:Rule
    val rule = createComposeRule()

    private val active = MutableStateFlow<String?>("a")
    private val state = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
    private val cards = MutableStateFlow(cardsWithActive("a"))

    // The switch ProfileManager runs: held open until the test lets it end.
    private val outcome = CompletableDeferred<() -> Unit>()
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns active
        every { manager.switchingState } returns state
        every { manager.acknowledgeSwitchError() } answers { state.value = ProfileManager.SwitchState.Idle }
        coEvery { manager.switchTo(any()) } coAnswers {
            state.value = ProfileManager.SwitchState.Switching(firstArg(), ProfileManager.SwitchState.Stage.Priming)
            outcome.await().invoke()
        }
    }
    private val switcher = AccountSwitcherViewModel(profiles, cards)
    private val collector = CoroutineScope(Dispatchers.Main)
    private val failures = mutableListOf<Unit>()
    private var editHomeTaps = 0

    @After
    fun tearDown() {
        collector.cancel()
    }

    private fun setHome() {
        collector.launch { switcher.switchFailures.collect { failures += it } }
        rule.setContent {
            YoinTheme {
                HomeAccountSwitcherHost(
                    accountSwitcher = switcher,
                    onNavigateToSettings = {},
                    onEditHome = { editHomeTaps++ }
                ) { account ->
                    val inUse by active.collectAsState()
                    // Home's own page under reduced motion (no title marquee for the
                    // clock to wait on); the card's window keeps the full motion.
                    CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                        HomeContent(
                            // HomeViewModel's hold: Loading from the switch's start
                            // until the next account's feed (or the old one back).
                            uiState = if (account.switchPending) HomeUiState.Loading else feedOf(inUse.orEmpty()),
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
                            activeAccount = account.account,
                            onOpenAccounts = account.onOpen,
                            accountButtonHidden = account.windowUp,
                            onAccountAnchor = account.onAnchor,
                            accountSwitchPending = account.switchPending,
                            accountAnchor = account.anchor,
                            accountHandedUp = account.landed
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun headerAvatar(title: String) = rule.onNodeWithContentDescription("Account: $title")

    private fun standIn() = rule.onNodeWithTag(HOME_ACCOUNT_HOLD_TAG, useUnmergedTree = true)

    private fun flightUp(): Boolean =
        rule.onAllNodes(hasTestTag(HOME_ACCOUNT_FLIGHT_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(id: String) = SemanticsMatcher.expectValue(HomeAccountShownKey, id)

    private fun up(up: Boolean) = SemanticsMatcher.expectValue(HomeAccountHoldUpKey, up)

    /** Opens the card from the header avatar, and picks B; returns where the header avatar was. */
    private fun pickB(): Offset {
        val header = headerAvatar("qa").fetchSemanticsNode().boundsInRoot.center
        headerAvatar("qa").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(homeAccountRowTag("b"), useUnmergedTree = true).performClick()
        frame()
        return header
    }

    @Test
    fun should_showTheTargetOnlyAsTheCardsWindowGoes_when_anAccountIsPicked() {
        setHome()

        val header = pickB()

        // B pends from the tap. The card's window is still up (the row's beat,
        // then the flight): the stand-in has B but stays down.
        frame()
        frame()
        standIn().assert(shows("b")).assert(up(false))
        var frames = 0
        while (!flightUp() && frames++ < MAX_FRAMES) frame()
        // The card closes as the switch: B's avatar flies up, not A's way home.
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assert(shows("b"))
        standIn().assert(up(false))

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // The window has gone: the stand-in holds the header's spot, as B.
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        standIn().assert(shows("b")).assert(up(true))
        val center = standIn().fetchSemanticsNode().boundsInRoot.center
        assertTrue("on the header avatar (at $center, header at $header)", (center - header).getDistance() < NEAR_PX)
    }

    @Test
    fun should_springBackAndSayItCouldNotSwitch_when_theSwitchFails() {
        setHome()
        pickB()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        standIn().assert(shows("b"))

        rule.runOnIdle {
            outcome.complete { state.value = ProfileManager.SwitchState.Error("b", "Server did not respond within 8s") }
        }
        rule.waitForIdle()

        // A's feed is back with A's avatar in the header, and Home says it, once.
        standIn().assertDoesNotExist()
        headerAvatar("qa").assertIsEnabled()
        assertEquals(1, failures.size)
    }

    @Test
    fun should_handTheTargetToTheHeader_when_theSwitchLands() {
        setHome()
        pickB()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        rule.runOnIdle {
            outcome.complete {
                active.value = "b"
                state.value = ProfileManager.SwitchState.Idle
            }
            cards.value = cardsWithActive("b")
        }
        rule.waitForIdle()

        standIn().assertDoesNotExist()
        headerAvatar("Spotify").assertIsEnabled()
        assertTrue(failures.isEmpty())
    }

    @Test
    fun should_leaveEditHomeAlone_when_aSwitchIsUnderWay() {
        setHome()
        pickB()

        // Still on screen through the row's beat, but a switch is under way.
        rule.onNode(hasText("Edit Home") and hasAnyAncestor(isDialog())).performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(0, editHomeTaps)
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

    private fun cardsWithActive(id: String) = listOf(
        ProfileCard(
            id = "a",
            displayName = "qa",
            subtitle = "music.example.com",
            provider = ProviderKind.SUBSONIC,
            isActive = id == "a"
        ),
        ProfileCard(
            id = "b",
            displayName = "Spotify",
            subtitle = null,
            provider = ProviderKind.SPOTIFY,
            isActive = id == "b",
            avatarShape = 1
        ),
        ProfileCard(
            id = "c",
            displayName = "Apple Music",
            subtitle = null,
            provider = ProviderKind.APPLE_MUSIC,
            isActive = id == "c",
            avatarShape = 2
        )
    )

    private companion object {
        const val MAX_FRAMES = 30
        const val NEAR_PX = 2f
    }
}
