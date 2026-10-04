package com.gpo.yoin.ui.memories.emblem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA B2 through composition: pressure never reaches the choreography, a flip never rebuilds the award. */
@RunWith(RobolectricTestRunner::class)
class GrooveAwardRememberTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_not_reduce_choreography_when_profile_is_adaptive_reduced() {
        var choreography: Boolean? = null
        var ambient: Boolean? = null
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                choreography = rememberGrooveReducedMotion()
                ambient = rememberGrooveAmbientReduced()
            }
        }
        rule.waitForIdle()
        assertEquals(false, choreography)
        assertEquals(true, ambient)
    }

    @Test
    fun should_keep_award_state_when_reduced_motion_flips() {
        var reduced by mutableStateOf(true)
        val seen = mutableListOf<GrooveAwardState>()
        rule.setContent {
            val state = rememberGrooveAwardState(
                model = GrooveSamples.M1,
                size = 96.dp,
                reducedMotion = reduced,
                haptics = null,
            )
            SideEffect { seen += state }
        }
        rule.runOnIdle { seen.single().setPending(true) }

        rule.runOnIdle { reduced = false }
        rule.waitForIdle()

        val states = seen.distinct()
        assertEquals("one state across the flip", 1, states.size)
        val state = states.single()
        assertFalse(state.reducedMotion)
        // still waiting for its award (frame 0 of the ceremony), not rebuilt as a finished emblem
        assertEquals(0f, state.tintAlpha, 0f)
        assertTrue(state.cutProgress(0) < 0.01f)
    }
}
