package com.gpo.yoin.ui.experience

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun should_liftPressure_when_reportOutlastsItsCap() = runTest {
        val provider = MotionCapabilityProvider(lowRamDevice = false)

        val report = launch { provider.reportPressure(tag = "home", isHighPressure = true, maxDurationMs = 3_000L) }
        runCurrent()
        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)

        advanceTimeBy(2_999L)
        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)

        advanceTimeBy(2L)
        assertEquals(MotionProfile.Full, provider.profile.value)
        report.join()
    }

    @Test
    fun should_holdPressure_when_reportHasNoCap() = runTest {
        val provider = MotionCapabilityProvider(lowRamDevice = false)

        val report = launch { provider.reportPressure(tag = "memories", isHighPressure = true, maxDurationMs = null) }
        advanceTimeBy(60_000L)

        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)
        report.join()
    }
}
