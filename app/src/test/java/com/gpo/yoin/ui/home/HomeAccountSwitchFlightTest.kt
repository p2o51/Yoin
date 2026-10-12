package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.settingsSurfaces
import com.gpo.yoin.ui.settings.tone
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
    private var anchor by mutableStateOf(ANCHOR)
    private var pending by mutableStateOf<String?>(null)
    private var closeFrom by mutableStateOf<Rect?>(null)
    private var closeInto by mutableStateOf<ProfileCard?>(null)
    private var picked: Pair<String, Rect>? = null
    private var dismissed: Boolean? = null
    private var editHomeTaps = 0
    private var manageTaps = 0
    private val selection = HomeAccountSelection()

    // A plain row's colour, and the Spotify row's when selected (the card's theme).
    private var rowColor = Color.Unspecified
    private var spotifySelected = Color.Unspecified

    private fun openCard(direction: LayoutDirection = LayoutDirection.Ltr) {
        rule.setContent {
            CompositionLocalProvider(
                LocalMotionProfile provides MotionProfile.Full,
                LocalLayoutDirection provides direction
            ) {
                YoinTheme {
                    rowColor = settingsSurfaces().row
                    spotifySelected = ProviderKind.SPOTIFY.serviceIdentity.hue.tone().container
                    HomeAccountSwitcherDialog(
                        visible = visible,
                        anchor = anchor,
                        cards = cards,
                        onRequestClose = { visible = false },
                        onDismissed = { landed -> dismissed = landed },
                        onSwitch = { card, avatar -> picked = card.id to avatar },
                        onManageAccounts = { manageTaps++ },
                        onAddAccount = {},
                        onOpenSettings = {},
                        onEditHome = { editHomeTaps++ },
                        pendingTarget = pending,
                        closeFrom = closeFrom,
                        closeInto = closeInto,
                        selection = selection
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

    private fun flightCenter(): Offset = flight().fetchSemanticsNode().boundsInRoot.center

    private fun framesUntilTheFlight() {
        repeat(MAX_FRAMES) {
            frame()
            if (flightUp()) return
        }
    }

    private fun flightUp(): Boolean =
        rule.onAllNodes(hasTestTag(HOME_ACCOUNT_FLIGHT_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(id: String) = SemanticsMatcher.expectValue(HomeAccountShownKey, id)

    /** The colour [id]'s row's segment was drawn in. */
    private fun rowColor(id: String): Color = rule.onNodeWithTag(homeAccountRowTag(id), useUnmergedTree = true)
        .fetchSemanticsNode().config[HomeAccountRowColorKey]

    /** How far [point] is off the straight path from the row's avatar to [landing]. */
    private fun offPath(point: Offset, landing: Offset): Float {
        val start = TAKE_OFF.center
        val path = landing - start
        val along = ((point - start).x * path.x + (point - start).y * path.y) / path.getDistanceSquared()
        return (point - (start + path * along.coerceIn(0f, 1f))).getDistance()
    }

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
    fun should_startNothingElse_when_aSwitchIsPending() {
        openCard()
        // Open, the card's own entries do what they say.
        rule.onNodeWithText("Edit Home").performClick()
        assertEquals(1, editHomeTaps)

        rule.mainClock.autoAdvance = false
        switchTo(cards[1])
        frame()

        // Through the row's beat the card is still all there, but a switch is
        // under way: Edit Home (which would freeze the feed that's leaving)
        // and Manage accounts start nothing.
        rule.onNodeWithText("Edit Home").performClick()
        rule.onNodeWithText("Manage accounts").performClick()
        assertEquals(1, editHomeTaps)
        assertEquals(0, manageTaps)
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
        val start = flightCenter()
        assertTrue("takes off from the row (at $start)", (start - TAKE_OFF.center).getDistance() < NEAR_PX)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(true, dismissed)
    }

    @Test
    fun should_drawThePickOnTheCardsSelectionAndCloseOnItsBeat_when_anAccountIsPicked() {
        openCard()
        rule.mainClock.autoAdvance = false

        switchTo(cards[1])
        // Frame by frame until it flies: how far the card's selection had got,
        // the colour the picked row's segment was drawn in, and the flight.
        data class Frame(val selection: Float, val drawn: Color, val flying: Boolean)
        val frames = mutableListOf<Frame>()
        while (frames.lastOrNull()?.flying != true && frames.size < MAX_FRAMES) {
            frame()
            frames += Frame(selection.colourOf("b"), rowColor("b"), flightUp())
        }

        // The segment is drawn from the card's own (paced) spring, in-between frames and all…
        frames.forEach { f ->
            assertEquals("frames: $frames", lerp(rowColor, spotifySelected, f.selection.coerceIn(0f, 1f)), f.drawn)
        }
        assertTrue("frames: $frames", frames.count { it.selection > 0f && it.selection < 1f } >= MIN_IN_BETWEEN)
        // …and that spring is the beat the close waits for: nothing flies before it.
        val beat = frames.indexOfFirst { it.selection >= SWITCH_SELECTION_BEAT }
        val flight = frames.indexOfFirst { it.flying }
        assertTrue("frames: $frames", beat >= 0 && flight > beat && flight <= beat + 2)
    }

    @Test
    fun should_landOnTheHeaderAvatar_when_theFlightEnds() {
        openCard()
        rule.mainClock.autoAdvance = false
        switchTo(cards[1])
        framesUntilTheFlight()

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // The window is told an avatar landed, and it is in the header's slot.
        assertEquals(true, dismissed)
        flight().assert(shows("b"))
        val end = flightCenter()
        assertTrue("lands on the header avatar (at $end)", (end - ANCHOR.center).getDistance() < NEAR_PX)
    }

    @Test
    fun should_followTheHeader_when_itMovesMidFlight() {
        openCard()
        rule.mainClock.autoAdvance = false
        switchTo(cards[1])
        framesUntilTheFlight()
        // Past half way up.
        val halfway = (TAKE_OFF.center.y + ANCHOR.center.y) / 2f
        var frames = 0
        while (flightCenter().y >= halfway && frames++ < MAX_FRAMES) frame()
        val before = flightCenter()
        assertTrue("past half way (at $before)", before.y < halfway)

        // The next feed comes in under it: the header avatar is lower now.
        rule.runOnIdle { anchor = MOVED }
        frame()

        // Still on its way up: no jump toward the new spot.
        val after = flightCenter()
        assertTrue("from $before to $after", after.y <= before.y)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(true, dismissed)
        val end = flightCenter()
        assertTrue("lands on the moved header avatar (at $end)", (end - MOVED.center).getDistance() < NEAR_PX)
    }

    @Test
    fun should_giveWayToTheAccountInUseMidAir_when_theSwitchFailsInFlight() {
        openCard()
        rule.mainClock.autoAdvance = false
        switchTo(cards[1])
        framesUntilTheFlight()
        frame()
        frame()

        // Failed on the way up: the header is back on the account in use.
        rule.runOnIdle {
            pending = null
            closeInto = cards[0]
        }
        frame()

        flight().assert(shows("a"))
        val now = flightCenter()
        assertTrue("still on its way up (at $now)", offPath(now, ANCHOR.center) < NEAR_PX)
        assertTrue("not landed yet (at $now)", (now - ANCHOR.center).getDistance() > NEAR_PX)
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

    @Test
    fun should_flyFromTheRowToTheHeader_when_theLayoutIsRightToLeft() {
        // Window px are absolute: an RTL layout doesn't mirror where they land.
        openCard(direction = LayoutDirection.Rtl)
        rule.mainClock.autoAdvance = false
        switchTo(cards[1])
        framesUntilTheFlight()

        val start = flightCenter()
        assertTrue("takes off from the row (at $start)", (start - TAKE_OFF.center).getDistance() < NEAR_PX)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val end = flightCenter()
        assertTrue("lands on the header avatar (at $end)", (end - ANCHOR.center).getDistance() < NEAR_PX)
    }

    private companion object {
        const val MAX_FRAMES = 30
        const val MIN_IN_BETWEEN = 3
        const val NEAR_PX = 24f

        val ANCHOR = Rect(1000f, 120f, 1084f, 204f)
        val MOVED = Rect(1000f, 720f, 1084f, 804f)
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
