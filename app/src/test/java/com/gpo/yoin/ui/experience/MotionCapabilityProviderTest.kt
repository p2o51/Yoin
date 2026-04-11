package com.gpo.yoin.ui.experience

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionCapabilityProviderTest {

    @Test
    fun should_default_to_full_motion_on_capable_device() {
        val provider = MotionCapabilityProvider(lowRamDevice = false)

        assertEquals(MotionProfile.Full, provider.profile.value)
    }

    @Test
    fun should_reduce_motion_on_low_ram_device() {
        val provider = MotionCapabilityProvider(lowRamDevice = true)

        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)
    }

    @Test
    fun should_toggle_profile_when_scene_pressure_changes() {
        val provider = MotionCapabilityProvider(lowRamDevice = false)

        provider.setHighPressure(tag = "memories", isHighPressure = true)
        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)

        provider.setHighPressure(tag = "memories", isHighPressure = false)
        assertEquals(MotionProfile.Full, provider.profile.value)
    }
}
