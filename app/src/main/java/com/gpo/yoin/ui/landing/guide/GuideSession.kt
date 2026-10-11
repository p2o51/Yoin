package com.gpo.yoin.ui.landing.guide

import androidx.lifecycle.Lifecycle

/** What [ConnectGuideActivity] does in answer to one of its lifecycle callbacks, as [GuideSession] decides it. */
internal enum class GuideAction {
    None,

    /** Show the full-screen card for a beat, then float into the corner. */
    Enter,

    /** Give the other side the stage: open the browser, or tell Yoin to open the sign-in. */
    HandOff,

    /** Bring Yoin to the front and end the guide. Only ever from full screen (see [GuideSession.onResume]). */
    ReturnToYoin,

    /** End the guide without raising Yoin. */
    End
}

/**
 * The guide window's bookkeeping, apart from [ConnectGuideActivity] so the orders the system calls it in can be
 * tested. The window is one singleTask instance in its own task: it shows the card, floats into the corner and
 * hands the stage to the other side. Then one of three things happens to it:
 * - it is expanded (the system's full-screen button, or "back to Yoin", which launches it again so the system
 *   expands it): back to Yoin;
 * - it is dismissed (✕, or dragged away): the guide ends. The system only stops the window and sends it to the
 *   back, so nothing would end it otherwise, and the next launch would find it still there;
 * - Yoin launches it again while it is still there: it starts over.
 *
 * A window that only floats may not start Yoin itself (Android 17 doesn't count a pinned window as visible), so
 * [GuideAction.ReturnToYoin] only comes once it is full screen.
 */
internal class GuideSession(handedOff: Boolean = false, floated: Boolean = false) {

    /** The other side has the stage: the browser opened, or Yoin told to start the sign-in. */
    var handedOff: Boolean = handedOff
        private set

    /**
     * Has been in the corner since this start. Leaving the corner while stopped is then a dismissal; the no-PiP
     * fallback (the card stays full screen beneath the browser) never floats, so it is left alone.
     */
    var floated: Boolean = floated
        private set

    private var entered: Boolean = handedOff

    fun onResume(inPictureInPicture: Boolean): GuideAction = when {
        !entered -> {
            entered = true
            GuideAction.Enter
        }
        // Full screen again after the hand-off: expanded, so the user is done over there.
        handedOff && !inPictureInPicture -> GuideAction.ReturnToYoin
        else -> GuideAction.None
    }

    /** The shrink into the corner was refused (picture-in-picture turned off for Yoin, or unsupported). */
    fun onEnterRefused(): GuideAction {
        // Hand off anyway, so nothing waits on a window that will never float.
        handedOff = true
        return GuideAction.HandOff
    }

    /** The window went into the corner or left it, with the activity at [state] when the system said so. */
    fun onPictureInPictureChanged(inPictureInPicture: Boolean, state: Lifecycle.State): GuideAction {
        if (inPictureInPicture) {
            floated = true
            if (handedOff) return GuideAction.None
            handedOff = true
            return GuideAction.HandOff
        }
        return when {
            // Left the corner while stopped: dismissed, not expanded (expanding resumes it).
            floated && !state.isAtLeast(Lifecycle.State.STARTED) -> GuideAction.End
            // Resumed before the system said it left the corner: expanded (onResume saw it still floating).
            handedOff && state.isAtLeast(Lifecycle.State.RESUMED) -> GuideAction.ReturnToYoin
            else -> GuideAction.None
        }
    }

    /**
     * Stopped. [inPictureInPicture] still true is the screen going off while the window floats; a recreation is
     * not leaving. Otherwise a window that floated and is now neither floating nor finishing was dismissed.
     */
    fun onStop(inPictureInPicture: Boolean, changingConfigurations: Boolean, finishing: Boolean): GuideAction {
        val dismissed = floated && !inPictureInPicture && !changingConfigurations && !finishing
        return if (dismissed) GuideAction.End else GuideAction.None
    }

    /**
     * Launched again while this instance is still there. [returning]: the window's own "back to Yoin", which only
     * needs the system to expand the window — [onResume] then returns. Otherwise Yoin opened the guide again:
     * start over (card, corner, hand-off) instead of reading the full screen that follows as "done over there".
     * Returns whether it starts over.
     */
    fun onLaunchedAgain(returning: Boolean): Boolean {
        if (returning) return false
        handedOff = false
        floated = false
        entered = false
        return true
    }
}
