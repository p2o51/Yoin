package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingControllerTest {
    private class FakeProvider : SingStreamProvider {
        val flows = mutableMapOf<String, MutableStateFlow<SingStatus>>()
        val cancelled = mutableListOf<String>()

        override fun stream(request: SingRequest): Flow<SingStatus> =
            flows.getOrPut(request.key) { MutableStateFlow(SingStatus.Preparing()) }

        override fun cancel(request: SingRequest) {
            cancelled += request.key
        }
    }

    private val song1 = SingRequest(MediaId.subsonic("1"), "p")
    private val song2 = SingRequest(MediaId.subsonic("2"), "p")
    private val spotifySong = SingRequest(MediaId.spotify("s"), "p")

    private fun TestScope.controller(provider: FakeProvider) =
        SingController(backgroundScope, SingProviderSelector(provider))

    @Test
    fun should_beOff_when_new() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        assertEquals(SingMode.Off, c.mode.value)
        assertEquals(VocalLevel.DEFAULT, c.vocalLevel.value)
        assertFalse(c.enter()) // nothing playing
    }

    @Test
    fun should_offerSing_when_subsonicPlaying() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        c.onCurrentItem(song1)
        assertEquals(SingAvailability.Available, c.availability.value)
        c.onCurrentItem(spotifySong)
        assertEquals(SingAvailability.Unavailable(SingUnavailableReason.ProviderUnsupported), c.availability.value)
        assertFalse(c.enter())
        assertEquals(SingMode.Off, c.mode.value)
    }

    @Test
    fun should_prepareThenGlideFromUnity_when_entered() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        assertTrue(c.enter())
        assertEquals(SingMode.Preparing(null), c.mode.value)
        assertTrue(c.mixControl.startAtUnity)
        assertEquals(0.12f, c.mixControl.targetGain, 1e-5f)
        assertEquals(SingMixControl.GLIDE_MS, c.mixControl.rampMs)

        provider.flows.getValue(song1.key).value = SingStatus.Preparing(0.5f)
        assertEquals(SingMode.Preparing(0.5f), c.mode.value)
        provider.flows.getValue(song1.key).value = SingStatus.Ready(SingSource.LocalFile(File("x.m4a")))
        assertEquals(SingMode.Ready, c.mode.value)
    }

    @Test
    fun should_glideBackToUnity_when_exited() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        c.enter()
        c.exit()
        assertEquals(SingMode.Off, c.mode.value)
        assertEquals(1f, c.mixControl.targetGain, 0f)
        assertEquals(SingMixControl.CUT_OUT_GLIDE_MS, c.mixControl.rampMs)
        assertEquals(SingRampCurve.EaseInOut, c.mixControl.rampCurve)
        assertFalse(c.mixControl.startAtUnity)
        assertEquals(listOf(song1.key), provider.cancelled)
    }

    @Test
    fun should_stayPreparingUntilHandover_when_waitForHandoverEnabled() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = SingController(backgroundScope, SingProviderSelector(provider), waitForHandover = true)
        c.onCurrentItem(song1)
        assertTrue(c.enter())
        provider.flows.getValue(song1.key).value = SingStatus.Ready(SingSource.LocalFile(File("x.m4a")))
        // Still preparing while SingPlaybackSwitcher pre-buffers & swaps in.
        assertEquals(SingMode.Preparing(null), c.mode.value)
        c.onStreamHandedOver(song1.key)
        assertEquals(SingMode.Ready, c.mode.value)
    }

    @Test
    fun should_turnOffAndBumpFailureTick_when_streamFails() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        assertTrue(c.enter())
        assertEquals(0, c.failureTick.value)

        provider.flows.getValue(song1.key).value = SingStatus.Failed(SingFailure.Decode, "decode error")
        assertFalse(c.state.value.enabled)
        assertEquals(SingMode.Off, c.mode.value)
        assertEquals(1, c.failureTick.value)

        // Next song does NOT auto-prepare & fail again because Sing cleanly turned off.
        c.onCurrentItem(song2)
        assertFalse(c.state.value.enabled)
        assertEquals(SingMode.Off, c.mode.value)

        // Single tap immediately retries.
        assertTrue(c.enter())
        assertTrue(c.state.value.enabled)
    }

    @Test
    fun should_resetVocalLevelToDefault_when_exitingAtZero_and_preserveWhenAboveZero() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        c.onCurrentItem(song1)
        c.enter()

        // Exiting at 0% (two-step left-drag dismiss) resets remembered level to 12% default.
        c.setVocalLevel(0f)
        assertEquals(0f, c.vocalLevel.value.position, 0f)
        c.exit()
        assertEquals(VocalLevel.DEFAULT, c.vocalLevel.value)

        // Exiting above 0% preserves user's custom level.
        c.enter()
        val custom = SingGainCurve.positionFor(0.45f)
        c.setVocalLevel(custom)
        c.exit()
        assertEquals(custom, c.vocalLevel.value.position, 1e-5f)
    }

    @Test
    fun should_ramp20msAndSnap_when_sliderMoves() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        c.onCurrentItem(song1)
        c.enter()
        c.setVocalLevel(SingGainCurve.positionFor(0.5f))
        assertEquals(0.5f, c.mixControl.targetGain, 1e-4f)
        assertEquals(SingMixControl.DEFAULT_RAMP_MS, c.mixControl.rampMs)
        assertEquals(50, c.vocalLevel.value.percent)
        c.setVocalLevel(SingGainCurve.positionFor(0.11f))
        assertTrue(c.vocalLevel.value.atDefault)
        assertEquals(12, c.vocalLevel.value.percent)
    }

    @Test
    fun should_keepLevelButNotMix_when_sliderMovedWhileOff() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        val before = c.mixControl.generation
        c.setVocalLevel(1f)
        assertEquals(before, c.mixControl.generation)
        assertEquals(100, c.vocalLevel.value.percent)
    }

    @Test
    fun should_carryOverToNextSong_when_trackChanges() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        c.enter()
        provider.flows.getValue(song1.key).value = SingStatus.Ready(SingSource.LocalFile(File("1.m4a")))
        c.mixControl.startAtUnity = false // consumed by the first Sing stream

        c.onCurrentItem(song2)
        assertEquals(song2, c.state.value.request)
        assertEquals(SingMode.Preparing(null), c.mode.value)
        assertTrue(c.mixControl.startAtUnity)

        // Gapless into a song whose Sing stream is already playing: no glide.
        c.onCurrentItem(song1, singing = true)
        assertFalse(c.mixControl.startAtUnity)
    }

    @Test
    fun should_stayOnButUnavailable_when_nextSongCantBeSung() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        c.onCurrentItem(song1)
        c.enter()
        c.onCurrentItem(spotifySong)
        assertEquals(SingMode.Unavailable(SingUnavailableReason.ProviderUnsupported), c.mode.value)
        assertTrue(c.state.value.enabled)
    }
}
