package com.gpo.yoin.ui.landing.guide

import androidx.lifecycle.Lifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide window's lifecycle decisions ([GuideSession]) in the orders the system delivers them. On Android 17's
 * picture-in-picture (pip2), ✕ doesn't destroy the window: it leaves the corner, goes to the back and stops; a
 * launch of a floating window expands it.
 */
class GuideSessionTest {

    /** Stopped; [screenInUse] off is the screen going off or locking. */
    private fun GuideSession.stop(
        inCorner: Boolean = false,
        recreating: Boolean = false,
        finishing: Boolean = false,
        screenInUse: Boolean = true
    ) = onStop(
        inPictureInPicture = inCorner,
        changingConfigurations = recreating,
        finishing = finishing,
        screenInUse = screenInUse
    )

    /** Into the corner or out of it, at [state]. */
    private fun GuideSession.corner(inCorner: Boolean, state: State, screenInUse: Boolean = true) =
        onPictureInPictureChanged(inPictureInPicture = inCorner, state = state, screenInUse = screenInUse)

    /** Opened, card shown, shrunk into the corner and the other side handed the stage. */
    private fun floatingSession(): GuideSession = GuideSession().apply {
        assertEquals(GuideAction.Enter, onResume(inPictureInPicture = false))
        assertEquals(GuideAction.HandOff, corner(true, State.STARTED))
    }

    @Test
    fun should_showTheCardThenHandOffOnce_when_theWindowFirstFloats() {
        val session = GuideSession()
        assertEquals(GuideAction.Enter, session.onResume(inPictureInPicture = false))
        assertEquals(GuideAction.HandOff, session.corner(true, State.STARTED))
        assertTrue(session.handedOff)
        // Back in the corner again later (e.g. after a recreation) never hands off twice.
        assertEquals(GuideAction.None, session.corner(true, State.STARTED))
    }

    @Test
    fun should_returnToYoin_when_expandedAndResumedAfterLeavingTheCorner() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.ReturnToYoin, session.onResume(inPictureInPicture = false))
    }

    @Test
    fun should_returnToYoin_when_resumedBeforeTheSystemSaysItLeftTheCorner() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.onResume(inPictureInPicture = true))
        assertEquals(GuideAction.ReturnToYoin, session.corner(false, State.RESUMED))
    }

    @Test
    fun should_neverReturnToYoin_when_theWindowStillFloats() {
        // Android 17 blocks a start from a window that only floats; finishing then strands the user (P10-1).
        val session = floatingSession()
        assertEquals(GuideAction.None, session.onResume(inPictureInPicture = true))
        assertEquals(GuideAction.None, session.stop(inCorner = true))
    }

    @Test
    fun should_endTheGuide_when_dismissedAndTheCornerIsLeftBeforeTheStop() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.End, session.stop())
    }

    @Test
    fun should_endTheGuide_when_dismissedAndStoppedBeforeLeavingTheCorner() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.stop(inCorner = true))
        assertEquals(GuideAction.End, session.corner(false, State.CREATED))
    }

    @Test
    fun should_keepFloating_when_theScreenGoesOffWhileInTheCorner() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.stop(inCorner = true))
        assertEquals(GuideAction.None, session.onResume(inPictureInPicture = true))
    }

    @Test
    fun should_keepTheGuide_when_theScreenGoesOffAndTheWindowLeavesTheCornerBeforeTheStop() {
        // P10-2 review: in case the system takes a sleeping window out of the corner, that's no ✕.
        val session = floatingSession()
        assertEquals(GuideAction.None, session.corner(false, State.STARTED, screenInUse = false))
        assertEquals(GuideAction.None, session.stop(screenInUse = false))
    }

    @Test
    fun should_keepTheGuide_when_theScreenLocksAndTheWindowLeavesTheCornerAfterTheStop() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.stop(inCorner = true, screenInUse = false))
        assertEquals(GuideAction.None, session.corner(false, State.CREATED, screenInUse = false))
    }

    @Test
    fun should_stillEndTheGuide_when_dismissedOnceTheScreenIsBack() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.stop(screenInUse = false))
        // Unlocked, back in the corner, then ✕.
        assertEquals(GuideAction.None, session.corner(true, State.STARTED))
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.End, session.stop())
    }

    @Test
    fun should_keepTheGuide_when_recreatedOrAlreadyFinishing() {
        val session = floatingSession()
        assertEquals(GuideAction.None, session.stop(recreating = true))
        assertEquals(GuideAction.None, session.stop(finishing = true))
    }

    @Test
    fun should_keepTheCardBeneathTheBrowser_when_pictureInPictureIsRefused() {
        val session = GuideSession()
        assertEquals(GuideAction.Enter, session.onResume(inPictureInPicture = false))
        assertEquals(GuideAction.HandOff, session.onEnterRefused())
        // The browser covers the card: it never floated, so this stop isn't a dismissal.
        assertEquals(GuideAction.None, session.stop())
        assertFalse(session.floated)
        // Back from the browser: the card goes back to Yoin.
        assertEquals(GuideAction.ReturnToYoin, session.onResume(inPictureInPicture = false))
    }

    @Test
    fun should_startOver_when_yoinLaunchesItAgainAfterTheHandOff() {
        // P10-2/P10-3: the full screen that follows a launch from Yoin isn't "done over there".
        val session = floatingSession()
        assertTrue(session.onLaunchedAgain(returning = false))
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.Enter, session.onResume(inPictureInPicture = false))
        assertEquals(GuideAction.HandOff, session.corner(true, State.STARTED))
    }

    @Test
    fun should_startOver_when_yoinLaunchesTheCardLeftBeneathTheBrowser() {
        val session = GuideSession()
        session.onResume(inPictureInPicture = false)
        session.onEnterRefused()
        session.stop()
        assertTrue(session.onLaunchedAgain(returning = false))
        assertEquals(GuideAction.Enter, session.onResume(inPictureInPicture = false))
    }

    @Test
    fun should_returnToYoin_when_itsOwnBackToYoinLaunchesItAndTheSystemExpandsIt() {
        val session = floatingSession()
        assertFalse(session.onLaunchedAgain(returning = true))
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.ReturnToYoin, session.onResume(inPictureInPicture = false))
    }

    @Test
    fun should_keepFloating_when_backToYoinIsNotFollowedByAnExpand() {
        val session = floatingSession()
        session.onLaunchedAgain(returning = true)
        assertEquals(GuideAction.None, session.onResume(inPictureInPicture = true))
        // The user can still dismiss it: it ends.
        assertEquals(GuideAction.None, session.corner(false, State.STARTED))
        assertEquals(GuideAction.End, session.stop())
    }

    @Test
    fun should_returnToYoin_when_restoredAfterTheHandOff() {
        val session = GuideSession(handedOff = true, floated = true)
        assertEquals(GuideAction.ReturnToYoin, session.onResume(inPictureInPicture = false))
        val floating = GuideSession(handedOff = true, floated = true)
        assertEquals(GuideAction.None, floating.onResume(inPictureInPicture = true))
    }
}
