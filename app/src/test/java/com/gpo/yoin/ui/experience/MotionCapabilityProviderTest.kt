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

    @Test
    fun should_reduceMotion_when_animatorDurationScaleIsZero() {
        assertEquals(
            MotionProfile.AdaptiveReduced,
            resolveMotionProfile(
                lowRamDevice = false,
                powerSaveEnabled = false,
                hasHighPressure = false,
                animatorDurationScale = 0f,
            ),
        )
    }

    @Test
    fun should_keepFullMotion_when_animatorDurationScaleIsSlowedOrNormal() {
        listOf(0.5f, 1f, 10f).forEach { scale ->
            assertEquals(
                MotionProfile.Full,
                resolveMotionProfile(
                    lowRamDevice = false,
                    powerSaveEnabled = false,
                    hasHighPressure = false,
                    animatorDurationScale = scale,
                ),
            )
        }
    }

    @Test
    fun should_reduceMotion_when_anyReasonHolds() {
        assertEquals(MotionProfile.AdaptiveReduced, resolveMotionProfile(true, false, false, 1f))
        assertEquals(MotionProfile.AdaptiveReduced, resolveMotionProfile(false, true, false, 1f))
        assertEquals(MotionProfile.AdaptiveReduced, resolveMotionProfile(false, false, true, 1f))
        assertEquals(MotionProfile.Full, resolveMotionProfile(false, false, false, 1f))
    }

    @Test
    fun should_followAnimatorScale_when_itChangesAndProviderRefreshes() {
        var scale = 1f
        val provider = MotionCapabilityProvider(lowRamDevice = false, animatorDurationScale = { scale })
        assertEquals(MotionProfile.Full, provider.profile.value)

        scale = 0f
        provider.refresh()
        assertEquals(MotionProfile.AdaptiveReduced, provider.profile.value)

        scale = 1f
        provider.refresh()
        assertEquals(MotionProfile.Full, provider.profile.value)
    }
}
