package com.gpo.yoin.ui.memories.emblem

import com.gpo.yoin.ui.experience.MotionProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may change Memories' motion (QA B2): the user's setting moves the choreography, pressure only effects. */
class MemoriesMotionPolicyTest {

    @Test
    fun should_keep_full_choreography_when_only_adaptive_pressure_is_high() {
        // a Memories load (or Home's, or Now Playing's) reported pressure: the profile is AdaptiveReduced
        val choreography = MemoriesMotionPolicy.choreographyReduced(animatorDurationScale = 1f)
        assertFalse(choreography)
        // the ambient effects (tilt, ripple) may still be quieted by it
        assertTrue(MemoriesMotionPolicy.ambientReduced(choreography, MotionProfile.AdaptiveReduced))
        assertFalse(MemoriesMotionPolicy.ambientReduced(choreography, MotionProfile.Full))
    }

    @Test
    fun should_reduce_choreography_when_user_removes_animations() {
        val choreography = MemoriesMotionPolicy.choreographyReduced(animatorDurationScale = 0f)
        assertTrue(choreography)
        assertTrue(MemoriesMotionPolicy.ambientReduced(choreography, MotionProfile.Full))
    }

    @Test
    fun should_keep_full_choreography_when_scale_is_slowed_or_unknown() {
        assertFalse(MemoriesMotionPolicy.choreographyReduced(animatorDurationScale = 0.5f))
        assertFalse(MemoriesMotionPolicy.choreographyReduced(animatorDurationScale = 5f))
        assertFalse(MemoriesMotionPolicy.choreographyReduced(animatorDurationScale = null))
    }
}
