package com.gpo.yoin.player.sing

import android.os.Build
import android.os.PowerManager
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel

/**
 * Whether this device should separate on its own. Before Sing first turns on, one silent chunk
 * (~3 s of audio) goes through the model; a chunk slower than [MAX_CHUNK_MS] (RTF > ~0.7 on a
 * 2.97 s chunk), a device already throttling for heat, or a decoder that won't give 4 channels back
 * makes it [Verdict.Insufficient], and the UI can suggest Lyra instead
 * ([SingUnavailableReason.DeviceInsufficient]).
 */
object SingDeviceGate {
    /** Slowest acceptable chunk. 2.0 s of work for 2.97 s of audio is an RTF of ~0.67. */
    const val MAX_CHUNK_MS = 2_000L

    /**
     * `PowerManager.THERMAL_STATUS_MODERATE` (2) and up count as throttling. LIGHT (1) is "UX not
     * impacted" and common while charging, so it passes.
     */
    const val THROTTLING_STATUS = 2

    sealed interface Verdict {
        data class Sufficient(val chunkMs: Long) : Verdict

        data class Insufficient(val why: Why, val chunkMs: Long? = null) : Verdict {
            val reason: SingUnavailableReason get() = SingUnavailableReason.DeviceInsufficient
        }
    }

    enum class Why { TooSlow, Throttling, NoQuadDecode, NoRuntime }

    fun evaluate(chunkMs: Long?, thermalStatus: Int, quadDecodes: Boolean = true): Verdict = when {
        thermalStatus >= THROTTLING_STATUS -> Verdict.Insufficient(Why.Throttling, chunkMs)
        !quadDecodes -> Verdict.Insufficient(Why.NoQuadDecode, chunkMs)
        chunkMs == null -> Verdict.Insufficient(Why.NoRuntime)
        chunkMs > MAX_CHUNK_MS -> Verdict.Insufficient(Why.TooSlow, chunkMs)
        else -> Verdict.Sufficient(chunkMs)
    }

    /**
     * Times one silent chunk, after a warm-up run that pays the session's first-call costs. If the
     * warm-up alone takes twice the limit, that's answer enough. [nanoTime] is for tests.
     */
    fun benchmarkChunkMs(model: SeparationModel, nanoTime: () -> Long = System::nanoTime): Long {
        val input = FloatArray(LyraSingSpec.CHUNK_FLOATS)
        val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
        val warmStart = nanoTime()
        model.run(input, output)
        val warmMs = (nanoTime() - warmStart) / 1_000_000L
        if (warmMs > 2 * MAX_CHUNK_MS) return warmMs
        val start = nanoTime()
        model.run(input, output)
        return (nanoTime() - start) / 1_000_000L
    }

    /** The device's thermal status, or NONE (0) where Android doesn't report it (before API 29). */
    fun thermalStatus(power: PowerManager?): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) power.currentThermalStatus else 0
}
