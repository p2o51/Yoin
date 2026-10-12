package com.gpo.yoin.ui.home

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header's avatar and the card's window hand it over without a frame
 * that shows neither (ACC-03: the avatar, and its dot, went 105–170 ms
 * before the card's window drew; a blank frame again as it went). Opening,
 * the header keeps it until the window is up with its twin on the same spot;
 * closing, the header has it back before the window goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class HomeAccountAvatarHandoffTest {

    @get:Rule
    val rule = createComposeRule()

    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns MutableStateFlow<String?>("a")
        every { manager.switchingState } returns MutableStateFlow(ProfileManager.SwitchState.Idle)
    }

    // The dot wears the header avatar's visibility: it is there only while the avatar is.
    private val switcher = AccountSwitcherViewModel(profiles, MutableStateFlow(accounts()), MutableStateFlow(true))
    private var chrome: HomeAccountChrome? = null

    /** One frame, the card's window included, and what it showed. */
    private data class Frame(val windowUp: Boolean, val dialog: Boolean, val headerAvatar: Boolean, val twin: Boolean)

    private fun frame(): Frame {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        return Frame(
            windowUp = chrome!!.windowUp,
            dialog = rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty(),
            headerAvatar = rule.onAllNodes(hasTestTag(HOME_ACCOUNT_RECONNECT_DOT_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            twin = rule.onAllNodes(
                hasTestTag(HOME_ACCOUNT_FLIGHT_TAG) and hasAnyAncestor(isDialog()),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun setHome() {
        rule.setContent {
            YoinTheme {
                HomeAccountSwitcherHost(
                    accountSwitcher = switcher,
                    onNavigateToSettings = {},
                    onEditHome = {},
                    onOpenAccountPage = {}
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

    @Test
    fun should_keepTheHeaderAvatar_until_theCardsWindowIsUpWithItsTwin() {
        setHome()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Account: qa").performClick()
        val frames = mutableListOf<Frame>()
        while (frames.lastOrNull()?.windowUp != true && frames.size < MAX_FRAMES) frames += frame()

        // The window came up while the header still had the avatar…
        assertTrue("frames: $frames", frames.any { it.dialog && it.headerAvatar })
        // …and took it with its twin of it already up there.
        val taken = frames.last()
        assertTrue("frames: $frames", taken.windowUp && taken.twin && !taken.headerAvatar)
        // Never a frame with neither.
        assertTrue("frames: $frames", frames.all { it.headerAvatar || it.twin })
    }

    @Test
    fun should_bringTheHeaderAvatarBack_before_theCardsWindowGoes() {
        setHome()
        rule.onNodeWithContentDescription("Account: qa").performClick()
        rule.waitForIdle()
        assertTrue(chrome!!.windowUp)

        rule.mainClock.autoAdvance = false
        rule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
        val frames = mutableListOf<Frame>()
        while (frames.lastOrNull()?.dialog != false && frames.size < MAX_FRAMES) frames += frame()

        // The header had it back while the window (its twin on the same spot) was still up…
        assertTrue("frames: $frames", frames.any { it.dialog && it.twin && it.headerAvatar && !it.windowUp })
        // …and the window went after that.
        val gone = frames.last()
        assertTrue("frames: $frames", !gone.dialog && gone.headerAvatar)
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
        const val MAX_FRAMES = 120
    }
}
