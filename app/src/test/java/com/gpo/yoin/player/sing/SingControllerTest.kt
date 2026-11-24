package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingControllerTest {
    private class FakeProvider : SingStreamProvider {
        val flows = mutableMapOf<String, MutableStateFlow<SingStatus>>()
        val cancelled = mutableListOf<String>()
        val requests = mutableListOf<SingRequest>()

        override fun stream(request: SingRequest): Flow<SingStatus> {
            requests += request
            return flows.getOrPut(request.key) { MutableStateFlow(SingStatus.Preparing()) }
        }

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
    fun should_resetVocalLevelToDefault_when_exitingAtMax_and_keepZeroOrCustomOtherwise() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeProvider())
        c.onCurrentItem(song1)
        c.enter()

        // Exiting at the top (the drag past 100 % exit) resets the remembered level to the 12 % default.
        c.setVocalLevel(1f)
        c.exit()
        assertEquals(VocalLevel.DEFAULT, c.vocalLevel.value)
        c.enter()
        c.setVocalLevel(0.996f)
        c.exit()
        assertEquals(VocalLevel.DEFAULT, c.vocalLevel.value)

        // Exiting at 0 % (mic or exit button) keeps 0 %.
        c.enter()
        c.setVocalLevel(0f)
        assertEquals(0f, c.vocalLevel.value.position, 0f)
        c.exit()
        assertEquals(0f, c.vocalLevel.value.position, 0f)

        // Exiting anywhere in between keeps the user's level.
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

    @Test
    fun should_askFromThePlayersPositionNow_when_enteredMidSong() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        // The song came on at 0.2 s; Sing is turned on at 112.5 s.
        c.onCurrentItem(song1.copy(positionMs = 200L))
        c.currentRequestSource = { song1.copy(positionMs = 112_500L, playing = true) }
        assertTrue(c.enter())
        assertEquals(112_500L, provider.requests.single().positionMs)
        assertEquals(112_500L, c.state.value.request?.positionMs)
    }

    @Test
    fun should_askPaused_when_enteredWhilePaused() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        c.currentRequestSource = { song1.copy(positionMs = 61_000L, playing = false) }
        assertTrue(c.enter())
        val asked = provider.requests.single()
        assertEquals(61_000L, asked.positionMs)
        assertFalse(asked.playing)
    }

    @Test
    fun should_fallBackToTheKnownRequest_when_noPositionSourceOrAnotherSong() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1.copy(positionMs = 5_000L))
        assertTrue(c.enter())
        assertEquals(5_000L, provider.requests.last().positionMs)
        c.exit()
        // The player moved on to another song the controller hasn't heard of yet: not that one's position.
        c.currentRequestSource = { song2.copy(positionMs = 40_000L) }
        assertTrue(c.enter())
        assertEquals(song1.key, provider.requests.last().key)
        assertEquals(5_000L, provider.requests.last().positionMs)
    }

    @Test
    fun should_neverBeOnWithoutItsRequest_when_seenByAnImmediateCollector() = runTest(UnconfinedTestDispatcher()) {
        // SingPlaybackSwitcher collects on Main.immediate and reports the playhead the moment Sing is on;
        // a state with Sing on but no request dropped that first report.
        val c = controller(FakeProvider())
        val seen = mutableListOf<SingController.State>()
        backgroundScope.launch { c.state.collect { seen += it } }
        c.onCurrentItem(song1.copy(positionMs = 200L))
        c.currentRequestSource = { song1.copy(positionMs = 112_500L) }
        c.enter()
        c.onCurrentItem(song2.copy(positionMs = 0L))
        val on = seen.filter { it.enabled }
        assertTrue(on.isNotEmpty())
        for (state in on) {
            assertNotNull("Sing on without its request", state.request)
            assertEquals("the playing song is the one Sing works on", state.current?.key, state.request?.key)
        }
        assertEquals(112_500L, on.first().request?.positionMs)
    }

    @Test
    fun should_followFromTheTrackChangePosition_when_songChangesWhileOn() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider()
        val c = controller(provider)
        c.onCurrentItem(song1)
        c.enter()
        // Skipped while paused to a song resuming at 30 s.
        c.onCurrentItem(song2.copy(positionMs = 30_000L, playing = false))
        val asked = provider.requests.last()
        assertEquals(song2.key, asked.key)
        assertEquals(30_000L, asked.positionMs)
        assertFalse(asked.playing)
    }
}
