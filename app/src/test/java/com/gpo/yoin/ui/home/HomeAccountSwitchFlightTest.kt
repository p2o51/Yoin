package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * A switch closes Home's account card as the pick going up (T7): the picked
 * row turns selected, the other accounts wait, and the avatar that flies into
 * the header is the target's, from the row — not the old account's way home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class HomeAccountSwitchFlightTest {

    @get:Rule
    val rule = createComposeRule()

    private var visible by mutableStateOf(true)
    private var pending by mutableStateOf<String?>(null)
    private var closeFrom by mutableStateOf<Rect?>(null)
    private var closeInto by mutableStateOf<ProfileCard?>(null)
    private var picked: Pair<String, Rect>? = null
    private var dismissed = false

    private fun openCard() {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.Full) {
                YoinTheme {
                    HomeAccountSwitcherDialog(
                        visible = visible,
                        anchor = ANCHOR,
                        cards = cards,
                        onRequestClose = { visible = false },
                        onDismissed = { dismissed = true },
                        onSwitch = { card, avatar -> picked = card.id to avatar },
                        onManageAccounts = {},
                        onAddAccount = {},
                        onOpenSettings = {},
                        onEditHome = {},
                        pendingTarget = pending,
                        closeFrom = closeFrom,
                        closeInto = closeInto
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** What HomeScreen does on a pick: the target pends, the card closes from the row. */
    private fun switchTo(card: ProfileCard) {
        rule.runOnIdle {
            pending = card.id
            closeFrom = TAKE_OFF
            closeInto = card
            visible = false
        }
    }

    /** One frame, the card's own window (the dialog) included. */
    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun flight(): SemanticsNodeInteraction = rule.onNodeWithTag(HOME_ACCOUNT_FLIGHT_TAG, useUnmergedTree = true)

    private fun framesUntilTheFlight() {
        repeat(MAX_FRAMES) {
            frame()
            if (flightUp()) return
        }
    }

    private fun flightUp(): Boolean =
        rule.onAllNodes(hasTestTag(HOME_ACCOUNT_FLIGHT_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(id: String) = SemanticsMatcher.expectValue(HomeAccountShownKey, id)

    @Test
    fun should_reportThePickAndItsAvatar_when_anAccountRowIsTapped() {
        openCard()

        rule.onNodeWithTag(homeAccountRowTag("b"), useUnmergedTree = true).performClick()

        val (id, avatar) = checkNotNull(picked)
        assertEquals("b", id)
        assertTrue("the row's avatar has bounds to take off from", !avatar.isEmpty)
    }

    @Test
    fun should_selectThePickAndHoldTheOthers_when_aSwitchIsPending() {
        openCard()

        rule.runOnIdle { pending = "b" }
        rule.waitForIdle()

        rule.onNode(isSelected() and hasAnyChild(hasTestTag(homeAccountRowTag("b"))), useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithTag(homeAccountRowTag("c"), useUnmergedTree = true).assertIsNotEnabled()

        rule.runOnIdle { pending = null }
        rule.waitForIdle()

        rule.onNodeWithTag(homeAccountRowTag("c"), useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun should_flyTheTargetUpFromItsRow_when_aSwitchClosesTheCard() {
        openCard()
        rule.mainClock.autoAdvance = false

        switchTo(cards[1])
        // The row takes its selection first: nothing flies yet.
        frame()
        flight().assertDoesNotExist()

        framesUntilTheFlight()

        flight().assert(shows("b"))
        val start = flight().fetchSemanticsNode().boundsInRoot.center
        assertTrue("takes off from the row (at $start)", (start - TAKE_OFF.center).getDistance() < NEAR_PX)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun should_flyTheAccountInUseHome_when_theSwitchFailsBeforeTakeOff() {
        openCard()
        rule.mainClock.autoAdvance = false

        switchTo(cards[1])
        frame()
        // Failed during the row's beat: the header is back on the account in use.
        rule.runOnIdle {
            pending = null
            closeInto = cards[0]
        }

        framesUntilTheFlight()

        // The plain close's flight (from the card's big avatar), not the pick's.
        flight().assert(shows("a"))
    }

    private companion object {
        const val MAX_FRAMES = 30
        const val NEAR_PX = 24f

        val ANCHOR = Rect(1000f, 120f, 1084f, 204f)
        val TAKE_OFF = Rect(120f, 1500f, 225f, 1605f)

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
            ),
            ProfileCard(
                id = "c",
                displayName = "Apple Music",
                subtitle = null,
                provider = ProviderKind.APPLE_MUSIC,
                isActive = false,
                avatarShape = 2
            )
        )
    }
}
