package com.gpo.yoin.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedPlaylistEpochTest {
    @Test
    fun should_dropNaturalHandoff_when_pauseStartsNewEpoch() {
        val gate = MixedPlaylistEpoch()
        val playing = gate.begin("A")
        gate.arm(playing)
        val paused = gate.begin("A")

        assertFalse(gate.isCurrent(playing))
        assertFalse(gate.claimNaturalEnd(playing))
        assertFalse(gate.claimNaturalEnd(paused))
    }

    @Test
    fun should_claimNaturalEndOnce_when_backendPublishesDuplicateEvents() {
        val gate = MixedPlaylistEpoch()
        val playing = gate.begin("A")
        gate.arm(playing)

        assertTrue(gate.claimNaturalEnd(playing))
        assertFalse(gate.claimNaturalEnd(playing))
    }

    @Test
    fun should_dropOldCycleEvent_when_sameEntryPlaysAgain() {
        val gate = MixedPlaylistEpoch()
        val first = gate.begin("A")
        gate.arm(first)
        gate.begin("B")
        val nextCycle = gate.begin("A")
        gate.arm(nextCycle)

        assertFalse(gate.claimNaturalEnd(first))
        assertTrue(gate.claimNaturalEnd(nextCycle))
    }

    @Test
    fun should_requireFreshStableNativeStop_when_localIntentAlreadySaysPaused() {
        val gate = MixedNativeStopGate(beforeSequence = 10)

        assertFalse(gate.observe(10, 0, true, false, false, 2000))
        assertFalse(gate.observe(11, 100, false, false, false, 2000))
        assertFalse(gate.observe(12, 200, true, false, false, 2000))
        assertFalse(gate.observe(12, 1200, true, false, false, 2000))
        assertTrue(gate.observe(13, 1300, true, false, false, 2000))
    }

    @Test
    fun should_resetStopStability_when_nativePositionStillAdvances() {
        val gate = MixedNativeStopGate(beforeSequence = 10)

        assertFalse(gate.observe(11, 100, true, false, false, 2000))
        assertFalse(gate.observe(12, 700, true, false, false, 2300))
        assertTrue(gate.observe(13, 1200, true, false, false, 2300))
    }

    @Test
    fun should_rejectStopConfirmation_when_nativeStillPlaysOrReportsError() {
        val gate = MixedNativeStopGate(beforeSequence = 10)

        assertFalse(gate.observe(11, 100, true, false, false, 2000))
        assertFalse(gate.observe(12, 700, true, true, false, 2000))
        assertFalse(gate.observe(13, 800, true, false, true, 2000))
        assertFalse(gate.observe(14, 900, true, false, false, 2000))
        assertTrue(gate.observe(15, 1500, true, false, false, 2000))
    }
}
