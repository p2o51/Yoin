package com.gpo.yoin.ui.home

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's side of an account in use that needs its owner (owner 2026-10-11):
 * the header avatar wears a dot only then, and springs it away (no cut) once
 * the account works; the dot goes with the avatar while the card or a switch
 * has it; the card offers Reconnect only then, goes back into the avatar when
 * the retry fixes it, and opens the account's page once it is back in the
 * avatar when the retry can't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class HomeAccountReconnectTest {

    @get:Rule
    val rule = createComposeRule()

    private val active = MutableStateFlow<String?>("a")
    private val state = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
    private val cards = MutableStateFlow(accounts())
    private val needsReconnect = MutableStateFlow(false)
    private var retryFixes = false

    // A switch ProfileManager runs: held open for the whole test.
    private val switchEnds = CompletableDeferred<Unit>()
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns active
        every { manager.switchingState } returns state
        coEvery { manager.retryActiveSource() } coAnswers {
            if (retryFixes) needsReconnect.value = false
            retryFixes
        }
        coEvery { manager.switchTo(any()) } coAnswers {
            state.value = ProfileManager.SwitchState.Switching(firstArg(), ProfileManager.SwitchState.Stage.Priming)
            switchEnds.await()
        }
    }
    private val switcher = AccountSwitcherViewModel(profiles, cards, needsReconnect)
    private val pagesOpened = mutableListOf<String>()

    // What the host last handed the header.
    private var chrome: HomeAccountChrome? = null

    private fun dot() = rule.onNodeWithTag(HOME_ACCOUNT_RECONNECT_DOT_TAG, useUnmergedTree = true)

    private fun reconnectButton() =
        rule.onNode(hasTestTag(HOME_ACCOUNT_RECONNECT_TAG) and hasAnyAncestor(isDialog()), useUnmergedTree = true)

    private fun headerAvatar() = rule.onNodeWithContentDescription("Account: qa")

    /** Home's header as HomeScreen wires it: the switcher host around the header avatar. */
    private fun setHome() {
        rule.setContent {
            YoinTheme {
                HomeAccountSwitcherHost(
                    accountSwitcher = switcher,
                    onNavigateToSettings = {},
                    onEditHome = {},
                    onOpenAccountPage = { account -> pagesOpened += account.id }
                ) { handed ->
                    SideEffect { chrome = handed }
                    val account = handed.account ?: return@HomeAccountSwitcherHost
                    HomeAccountButton(
                        card = account,
                        onClick = handed.onOpen,
                        hidden = handed.windowUp,
                        onAvatarPositioned = handed.onAnchor,
                        needsReconnect = handed.needsReconnect
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openCard() {
        headerAvatar().performClick()
        rule.waitForIdle()
    }

    @Test
    fun should_showTheDot_when_theAccountInUseNeedsReconnect() {
        needsReconnect.value = true

        setHome()

        dot().assertExists()
        // TalkBack hears it with the avatar.
        rule.onNode(hasContentDescription("Account: qa") and hasContentDescription("Account needs reconnecting"))
            .assertExists()
    }

    @Test
    fun should_showNoDot_when_theAccountInUseIsFine() {
        setHome()

        dot().assertDoesNotExist()
    }

    @Test
    fun should_springTheDotAway_when_theAccountWorksAgain() {
        var needs by mutableStateOf(true)
        rule.setContent {
            YoinTheme {
                HomeAccountButton(card = accounts().first(), onClick = {}, needsReconnect = needs)
            }
        }
        rule.waitForIdle()
        dot().assertExists()

        rule.mainClock.autoAdvance = false
        needs = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()

        // Not cut: still there, on its way out.
        dot().assertExists()

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        dot().assertDoesNotExist()
    }

    @Test
    fun should_offerReconnect_when_theAccountInUseNeedsReconnect() {
        needsReconnect.value = true
        setHome()

        openCard()

        reconnectButton().assertExists()
    }

    @Test
    fun should_offerNoReconnect_when_theAccountInUseIsFine() {
        setHome()

        openCard()

        reconnectButton().assertDoesNotExist()
    }

    @Test
    fun should_closeTheCardIntoTheAvatarWithoutTheDotOrAPage_when_theRetryFixesTheAccount() {
        needsReconnect.value = true
        retryFixes = true
        setHome()
        openCard()

        reconnectButton().performClick()
        rule.waitForIdle()

        coVerify(exactly = 1) { profiles.retryActiveSource() }
        // The card went back into the avatar, which wears no dot now.
        rule.onNode(isDialog()).assertDoesNotExist()
        headerAvatar().assertExists()
        dot().assertDoesNotExist()
        assertTrue(pagesOpened.isEmpty())
    }

    @Test
    fun should_hideTheDotWithTheAvatar_when_theCardIsOpen() {
        needsReconnect.value = true
        setHome()
        dot().assertExists()

        rule.mainClock.autoAdvance = false
        headerAvatar().performClick()
        // Frame by frame until the card's window is up (the tap lands a frame or two on).
        var frames = 0
        while (chrome?.windowUp != true && frames++ < MAX_FRAMES) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }

        // The avatar is the card now: its dot went with it, in the same frame
        // (not left on the header springing away on its own).
        assertTrue(chrome!!.windowUp)
        dot().assertDoesNotExist()

        // Closed: back with the avatar, as it was.
        rule.mainClock.autoAdvance = true
        rule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(isDialog()).assertDoesNotExist()
        dot().assertExists()
    }

    @Test
    fun should_showNoDotOnTheTarget_when_aSwitchBegunFromTheCardIsUnderWay() {
        needsReconnect.value = true
        setHome()
        openCard()

        rule.onNodeWithTag(homeAccountRowTag("b"), useUnmergedTree = true).performClick()
        rule.waitForIdle()

        // The card's window has gone; B is in the header while the switch
        // runs, and the dot (A's) isn't on it.
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals("b", chrome!!.account?.id)
        assertTrue(chrome!!.switchPending)
        assertFalse(chrome!!.needsReconnect)
        rule.onNodeWithContentDescription("Account: Spotify").assertExists()
        dot().assertDoesNotExist()
    }

    @Test
    fun should_openTheAccountsPageOnceTheCardIsHome_when_theRetryCannotFixIt() {
        needsReconnect.value = true
        setHome()
        openCard()

        reconnectButton().performClick()
        rule.waitForIdle()

        coVerify(exactly = 1) { profiles.retryActiveSource() }
        // The card went back into the avatar first, then the page.
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("a"), pagesOpened)
        dot().assertExists()
    }

    private fun accounts() = listOf(
        ProfileCard(
            id = "a",
            displayName = "qa",
            subtitle = "music.example.com",
            provider = ProviderKind.SUBSONIC,
            isActive = true
        ),
        ProfileCard(
            id = "b",
            displayName = "Spotify",
            subtitle = null,
            provider = ProviderKind.SPOTIFY,
            isActive = false,
            avatarShape = 1
        )
    )

    private companion object {
        const val MAX_FRAMES = 10
    }
}
