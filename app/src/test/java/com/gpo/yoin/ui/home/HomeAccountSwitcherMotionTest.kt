package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account card closes the way it opened (Q14b): a switch begun from it
 * takes Home to Loading, whose motion pressure flips the app's profile just
 * as the card heads back into the avatar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class HomeAccountSwitcherMotionTest {

    @get:Rule
    val rule = createComposeRule()

    private var visible by mutableStateOf(true)
    private var profile by mutableStateOf(MotionProfile.Full)
    private var dismissed = false

    private fun openCard(openedUnder: MotionProfile) {
        profile = openedUnder
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides profile) {
                YoinTheme {
                    HomeAccountSwitcherDialog(
                        visible = visible,
                        anchor = Rect(340f, 60f, 404f, 124f),
                        cards = cards,
                        onRequestClose = { visible = false },
                        onDismissed = { dismissed = true },
                        onSwitch = {},
                        onManageAccounts = {},
                        onAddAccount = {},
                        onOpenSettings = {},
                        onEditHome = {}
                    )
                }
            }
        }
        rule.waitForIdle()
        // Open and at rest: no avatar in flight.
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { visible = false }
        repeat(CLOSE_INTO_FLIGHT_FRAMES) { frame() }
    }

    /** One frame, the card's own window (the dialog) included. */
    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test
    fun should_keepTheAvatarFlight_when_pressureFlipsMidClose() {
        openCard(openedUnder = MotionProfile.Full)
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertExists()

        rule.runOnIdle { profile = MotionProfile.AdaptiveReduced }
        frame()

        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun should_fadeInPlace_when_openedReducedAndPressureLiftsMidClose() {
        openCard(openedUnder = MotionProfile.AdaptiveReduced)
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { profile = MotionProfile.Full }
        frame()

        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertFalse(dismissed)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    private companion object {
        // A few frames into the close: well short of the spring's end.
        const val CLOSE_INTO_FLIGHT_FRAMES = 4

        val cards = listOf(
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
}
