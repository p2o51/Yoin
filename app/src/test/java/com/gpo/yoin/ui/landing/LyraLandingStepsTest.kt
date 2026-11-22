package com.gpo.yoin.ui.landing

import com.gpo.yoin.ui.settings.service.SetupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyraLandingStepsTest {
    @Test
    fun lyraOnlyLandingSkipsMultiServicePickAndConnectsDirectlyToLyra() {
        val steps = landingSteps(picked = emptySet(), hasAccount = false, lyraOnly = true)
        assertEquals(
            listOf(
                LandingStep.Hello,
                LandingStep.About,
                LandingStep.Subsonic,
                LandingStep.Edge,
                LandingStep.Corners,
                LandingStep.Ready,
            ),
            steps,
        )
        assertFalse(LandingStep.Pick in steps)
        assertFalse(LandingStep.Spotify in steps)
        assertFalse(LandingStep.AppleMusic in steps)
        assertFalse(LandingStep.Spotoolfy in steps)
    }

    @Test
    fun standardLandingKeepsMultiServicePicker() {
        val steps = landingSteps(picked = setOf(SetupService.Spotify), hasAccount = true, lyraOnly = false)
        assertTrue(LandingStep.Pick in steps)
        assertTrue(LandingStep.Spotoolfy in steps)
        assertTrue(LandingStep.Spotify in steps)
        assertTrue(LandingStep.Home in steps)
    }
}
