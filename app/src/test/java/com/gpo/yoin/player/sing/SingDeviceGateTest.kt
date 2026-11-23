package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingDeviceGate.Verdict
import com.gpo.yoin.player.sing.SingDeviceGate.Why
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingDeviceGateTest {
    @Test
    fun should_pass_when_fastCoolAndQuad() {
        assertEquals(Verdict.Sufficient(400), SingDeviceGate.evaluate(400, thermalStatus = 0))
        assertEquals(Verdict.Sufficient(825), SingDeviceGate.evaluate(825, thermalStatus = 1))
    }

    @Test
    fun should_passPixelTablet_when_itsIdleBenchmarkDoublesWhilePlaying() {
        // Round-2 tablet logs: 650 ms idle, 1 280–1 297 ms/chunk while the song played.
        assertEquals(Verdict.Sufficient(650), SingDeviceGate.evaluate(650, thermalStatus = 0))
        assertEquals(1_300L, SingDeviceGate.liveChunkMs(650))
        assertTrue(SingDeviceGate.liveChunkMs(650) <= SingDeviceGate.MAX_LIVE_CHUNK_MS)
        // A chunk step is ~1.86 s of audio: the limit keeps live separation ahead of the song.
        assertTrue(SingDeviceGate.MAX_LIVE_CHUNK_MS < 1_858)
    }

    @Test
    fun should_reportInsufficient_when_chunkTooSlowForLive() {
        // Fine idle (the old 1.5 s limit passed it) but ~2.0 s a chunk while playing: can't keep up.
        assertEquals(Why.TooSlow, (SingDeviceGate.evaluate(1_000, 0) as Verdict.Insufficient).why)
        val v = SingDeviceGate.evaluate(826, 0)
        assertEquals(Verdict.Insufficient(Why.TooSlow, 826), v)
        assertEquals(SingUnavailableReason.DeviceInsufficient, (v as Verdict.Insufficient).reason)
        assertEquals(com.gpo.yoin.R.string.sing_device_insufficient, v.reason.messageRes)
    }

    @Test
    fun should_reportInsufficient_when_throttling() {
        assertEquals(Why.Throttling, (SingDeviceGate.evaluate(100, thermalStatus = 2) as Verdict.Insufficient).why)
        assertEquals(Why.Throttling, (SingDeviceGate.evaluate(100, thermalStatus = 4) as Verdict.Insufficient).why)
    }

    @Test
    fun should_reportInsufficient_when_noQuadDecodeOrRuntime() {
        assertEquals(Why.NoQuadDecode, (SingDeviceGate.evaluate(100, 0, quadDecodes = false) as Verdict.Insufficient).why)
        assertEquals(Why.NoRuntime, (SingDeviceGate.evaluate(null, 0) as Verdict.Insufficient).why)
    }

    private class FakeModel(private val clock: LongArray, private val costsMs: List<Long>) : SeparationModel {
        var calls = 0
        override fun run(input: FloatArray, output: FloatArray) {
            assertEquals(LyraSingSpec.CHUNK_FLOATS, input.size)
            assertTrue(input.all { it == 0f }) // silent chunk
            clock[0] += costsMs[calls++] * 1_000_000L
        }
    }

    @Test
    fun should_timeSecondRun_when_benchmarking() {
        val clock = longArrayOf(0)
        val model = FakeModel(clock, listOf(3_000, 700))
        assertEquals(700L, SingDeviceGate.benchmarkChunkMs(model) { clock[0] })
        assertEquals(2, model.calls)
    }

    @Test
    fun should_stopAfterWarmUp_when_warmUpAlreadyTooSlow() {
        val clock = longArrayOf(0)
        val model = FakeModel(clock, listOf(3_500))
        assertEquals(3_500L, SingDeviceGate.benchmarkChunkMs(model) { clock[0] })
        assertEquals(1, model.calls)
    }
}
