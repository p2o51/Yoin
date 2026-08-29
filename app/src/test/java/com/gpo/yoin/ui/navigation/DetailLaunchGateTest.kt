package com.gpo.yoin.ui.navigation

import androidx.lifecycle.Lifecycle
import com.gpo.yoin.ui.experience.DetailBackPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailLaunchGateTest {
    @Test
    fun should_allowLaunch_when_shellIsInteractiveAndBackIsIdle() {
        assertTrue(
            canLaunchDetailFromShell(
                lifecycleState = Lifecycle.State.RESUMED,
                backPhase = DetailBackPhase.Idle,
            ),
        )
    }

    @Test
    fun should_rejectLaunch_when_shellIsPaused() {
        assertFalse(
            canLaunchDetailFromShell(
                lifecycleState = Lifecycle.State.STARTED,
                backPhase = DetailBackPhase.Idle,
            ),
        )
    }

    @Test
    fun should_rejectLaunch_when_detailBackIsActive() {
        assertFalse(
            canLaunchDetailFromShell(
                lifecycleState = Lifecycle.State.RESUMED,
                backPhase = DetailBackPhase.Gesture,
            ),
        )
        assertFalse(
            canLaunchDetailFromShell(
                lifecycleState = Lifecycle.State.RESUMED,
                backPhase = DetailBackPhase.Committed,
            ),
        )
    }

    @Test
    fun should_rejectSecondLaunch_while_firstStartIsPending() {
        assertFalse(
            canLaunchDetailFromShell(
                lifecycleState = Lifecycle.State.RESUMED,
                backPhase = DetailBackPhase.Idle,
                launchPending = true,
            ),
        )
    }
}
