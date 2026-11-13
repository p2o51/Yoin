package com.gpo.yoin.player.continueon

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which intents bring the shell a hand-off to show ([ContinueOnWindows.handoffExtras]). */
@RunWith(RobolectricTestRunner::class)
class ContinueOnWindowsTest {
    private fun handoff() = Intent().putExtra(ContinueOnCursor.KEY_VERSION, ContinueOnCursor.VERSION)
        .putExtra(ContinueOnCursor.KEY_TRACK, "t2")

    @Test
    fun should_readTheHandOff_when_theSystemStartsTheShellWithOne() {
        assertEquals("t2", ContinueOnWindows.handoffExtras(handoff())?.getString(ContinueOnCursor.KEY_TRACK))
    }

    @Test
    fun should_showNoCardAgain_when_theTaskIsReopenedFromRecents() {
        // Recents starts the task again with the intent that first started it, cursor and all.
        val replayed = handoff().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(ContinueOnWindows.handoffExtras(replayed))
    }

    @Test
    fun should_readNothing_when_theIntentBringsNoHandOff() {
        assertNull(ContinueOnWindows.handoffExtras(null))
        assertNull(ContinueOnWindows.handoffExtras(Intent(Intent.ACTION_MAIN)))
        assertNull(ContinueOnWindows.handoffExtras(Intent().putExtra("other", 1)))
    }
}
