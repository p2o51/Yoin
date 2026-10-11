package com.gpo.yoin.ui.home

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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's side of an account in use that needs its owner (owner 2026-10-11):
 * the header avatar wears a dot only then, and springs it away (no cut) once
 * the account works; the card offers Reconnect only then; a Reconnect the
 * retry can't fix opens the account's page once the card is back in the avatar.
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
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns active
        every { manager.switchingState } returns state
        coEvery { manager.retryActiveSource() } coAnswers {
            if (retryFixes) needsReconnect.value = false
            retryFixes
        }
    }
    private val switcher = AccountSwitcherViewModel(profiles, cards, needsReconnect)
    private val pagesOpened = mutableListOf<String>()

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
                ) { chrome ->
                    val account = chrome.account ?: return@HomeAccountSwitcherHost
                    HomeAccountButton(
                        card = account,
                        onClick = chrome.onOpen,
                        hidden = chrome.windowUp,
                        onAvatarPositioned = chrome.onAnchor,
                        needsReconnect = chrome.needsReconnect
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
    fun should_dropReconnectAndOpenNoPage_when_theRetryFixesTheAccount() {
        needsReconnect.value = true
        retryFixes = true
        setHome()
        openCard()

        reconnectButton().performClick()
        rule.waitForIdle()

        coVerify(exactly = 1) { profiles.retryActiveSource() }
        reconnectButton().assertDoesNotExist()
        assertTrue(pagesOpened.isEmpty())
        // The card stays up; the dot is gone for when it closes.
        rule.onNode(isDialog()).assertExists()
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
}
