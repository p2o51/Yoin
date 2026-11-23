package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.ChunkRunner
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CORE_RATE
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.HOP
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.N_FFT
import com.gpo.yoin.player.sing.dsp.PolyphaseResampler
import com.gpo.yoin.player.sing.dsp.SeparationModel
import com.gpo.yoin.player.sing.dsp.Stft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop timings of the separation pipeline's own work per model chunk (the model left out), on
 * direct buffers as the device's memory-mapped work files are. Prints, asserts nothing:
 * `-PyoinSingPerf=true`. HotSpot compiles a direct-buffer access down to a load, which ART doesn't (it
 * calls out per element); a JVM with `-Xint` is closer to what the device pays.
 */
class SingDspBenchTest {
    @get:Rule val temp = TemporaryFolder()

    private object NoModel : SeparationModel {
        override fun run(input: FloatArray, output: FloatArray) = output.fill(0f)
    }

    private fun direct(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun direct(values: FloatArray): FloatBuffer = direct(values.size).also { it.put(values).clear() }

    /** Median ms of [reps] runs of [block], after [warmup] untimed ones. */
    private fun time(warmup: Int = 3, reps: Int = 9, block: (rep: Int) -> Unit): Double {
        repeat(warmup) { block(it) }
        val ms = DoubleArray(reps) { rep ->
            val t0 = System.nanoTime()
            block(warmup + rep)
            (System.nanoTime() - t0) / 1e6
        }
        ms.sort()
        return ms[reps / 2]
    }

    @Test
    fun should_printPerChunkTimings_when_asked() {
        assumeTrue("set -PyoinSingPerf=true to run", System.getProperty("yoin.sing.perf") == "true")
        for (rate in intArrayOf(44_100, 48_000)) bench(rate)
    }

    private fun bench(rate: Int) {
        val seconds = 30
        val frames = seconds * rate
        val (l, r) = SingFixtures.aperiodicSynth(frames, rate)
        val mixL = direct(l)
        val mixR = direct(r)
        val down = PolyphaseResampler(rate, CORE_RATE)
        val up = PolyphaseResampler(CORE_RATE, rate)
        val coreLength = down.outputLength(frames)
        val coreL = direct(coreLength)
        val coreR = direct(coreLength)
        down.process(mixL, frames, coreL)
        down.process(mixR, frames, coreR)
        // One chunk step of new audio: 160 frames of 256 core samples.
        val stepCore = LyraSingSpec.DEFAULT_STEP_FRAMES * HOP
        val stepSource = (stepCore.toLong() * rate / CORE_RATE).toInt()
        val chunkStarts = (coreLength - stepCore) / stepCore

        val resampleDown = time { rep ->
            val s = (rep % chunkStarts) * stepCore
            down.process(mixL, frames, coreL, s, s + stepCore)
            down.process(mixR, frames, coreR, s, s + stepCore)
        }

        val runner = ChunkRunner()
        val totalFrames = runner.stft.frameCount(coreLength)
        val stftForward = time { rep ->
            val start = (rep * LyraSingSpec.DEFAULT_STEP_FRAMES) % (totalFrames - LyraSingSpec.CHUNK_FRAMES)
            runner.run(coreL, coreR, coreLength, start, LyraSingSpec.CHUNK_FRAMES, NoModel)
        }

        val stft = Stft()
        val windowed = DoubleArray(N_FFT) { 1e-3 * (it % 17) }
        val olaL = direct(coreLength)
        val olaR = direct(coreLength)
        val overlapAdd = time { rep ->
            val first = (rep * LyraSingSpec.DEFAULT_STEP_FRAMES) % (totalFrames - LyraSingSpec.DEFAULT_STEP_FRAMES)
            for (frame in first until first + LyraSingSpec.DEFAULT_STEP_FRAMES) {
                stft.overlapAdd(frame, windowed, olaL, coreLength)
                stft.overlapAdd(frame, windowed, olaR, coreLength)
            }
        }

        val upL = FloatBuffer.allocate(stepSource)
        val upR = FloatBuffer.allocate(stepSource)
        val upLimit = minOf(frames, up.outputLength(coreLength)) - stepSource
        val resampleUp = time { rep ->
            val at = (rep.toLong() * stepSource % upLimit).toInt()
            up.process(coreL, coreLength, upL, at, at + stepSource, outputOffset = at)
            up.process(coreR, coreLength, upR, at, at + stepSource, outputOffset = at)
        }

        val track = SingVocalTrack.create(temp.newFile(), rate, frames)
        val writeVocal = time { rep ->
            val at = (rep.toLong() * stepSource % (frames - stepSource)).toInt()
            track.writeVocal(at, at + stepSource, upL, upR, sourceOffset = at)
        }
        val writeReference = time(warmup = 1, reps = 3) { track.writeReference(mixL, mixR) } / seconds

        // The whole live loop with a model that costs nothing: everything Sing does per chunk but ORT.
        var chunks = 0
        val liveTotal = time(warmup = 1, reps = 3) {
            val live = LiveVocalSeparator(mixL, mixR, frames, rate, { direct(it) })
            val out = SingVocalTrack.create(temp.newFile(), rate, frames)
            val sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> out.writeVocal(from, to, left, right, offset) }
            live.start(sink)
            while (true) {
                val chunk = live.nextChunk(0)
                if (chunk < 0) break
                live.runChunk(chunk, NoModel, sink)
            }
            chunks = live.chunkCount
        } / chunks

        println(
            "dsp bench @ $rate Hz, per chunk (160-frame step, direct buffers, median ms): " +
                "resample down ${"%.2f".format(resampleDown)}, STFT forward (256 frames + pack) ${"%.2f".format(stftForward)}, " +
                "overlap-add ${"%.2f".format(overlapAdd)}, resample up ${"%.2f".format(resampleUp)}, " +
                "track writeVocal ${"%.2f".format(writeVocal)}; live loop without the model ${"%.2f".format(liveTotal)} ms/chunk; " +
                "writeReference ${"%.2f".format(writeReference)} ms per second of audio",
        )
    }
}
