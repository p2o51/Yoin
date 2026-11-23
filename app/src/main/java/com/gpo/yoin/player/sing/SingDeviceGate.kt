package com.gpo.yoin.player.sing

import android.os.Build
import android.os.PowerManager
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel

/**
 * Whether this device should separate on its own. Before Sing first turns on, one silent chunk
 * (~3 s of audio) goes through the model; a chunk slower than [MAX_CHUNK_MS], or a device already
 * throttling for heat, makes it [Verdict.Insufficient], and the UI can suggest Lyra instead
 * ([SingUnavailableReason.DeviceInsufficient]). ([Why.NoQuadDecode] is for the Lyra quad stream; live
 * on-device Sing plays the original and needs no 4-channel decode.)
 *
 * The benchmark runs with the device otherwise idle; live Sing separates while the song plays (decoder,
 * audio, UI, the chunk's own STFT work), which on a Pixel Tablet doubles a chunk: 650 ms idle,
 * 1 280–1 300 ms playing. So the verdict is on the idle figure times [LOAD_FACTOR] ([liveChunkMs]).
 */
object SingDeviceGate {
    /** A chunk while the song plays ≈ the idle benchmark times this (Pixel Tablet: 650 → ~1 290 ms). */
    const val LOAD_FACTOR = 2.0

    /**
     * Slowest acceptable chunk while playing (estimated, [liveChunkMs]). Each chunk adds one step of
     * audio (160 frames, ~1.86 s): 1.65 s of work per step keeps V ≥ 1.13× ahead of real time, so it
     * still gains on the playhead with the device warmer than when measured. (Pixel Tablet: ~1.3 s.)
     */
    const val MAX_LIVE_CHUNK_MS = 1_650L

    /** The slowest idle benchmark that passes: [MAX_LIVE_CHUNK_MS] / [LOAD_FACTOR]. */
    const val MAX_CHUNK_MS = 825L

    /** A warm-up run (the session's first) this slow is answer enough. */
    const val WARM_UP_GIVE_UP_MS = 3_000L

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
        liveChunkMs(chunkMs) > MAX_LIVE_CHUNK_MS -> Verdict.Insufficient(Why.TooSlow, chunkMs)
        else -> Verdict.Sufficient(chunkMs)
    }

    /** An idle benchmark's estimate of a chunk while the song plays. */
    fun liveChunkMs(benchmarkMs: Long): Long = Math.round(benchmarkMs * LOAD_FACTOR)

    /**
     * Times one silent chunk, after a warm-up run that pays the session's first-call costs. If the
     * warm-up alone takes [WARM_UP_GIVE_UP_MS], that's answer enough. [nanoTime] is for tests.
     */
    fun benchmarkChunkMs(model: SeparationModel, nanoTime: () -> Long = System::nanoTime): Long {
        val input = FloatArray(LyraSingSpec.CHUNK_FLOATS)
        val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
        val warmStart = nanoTime()
        model.run(input, output)
        val warmMs = (nanoTime() - warmStart) / 1_000_000L
        if (warmMs > WARM_UP_GIVE_UP_MS) return warmMs
        val start = nanoTime()
        model.run(input, output)
        return (nanoTime() - start) / 1_000_000L
    }

    /** The device's thermal status, or NONE (0) where Android doesn't report it (before API 29). */
    fun thermalStatus(power: PowerManager?): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) power.currentThermalStatus else 0
}
