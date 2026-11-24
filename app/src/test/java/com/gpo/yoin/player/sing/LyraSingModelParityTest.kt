package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.SingFixtures.maxAbsError
import com.gpo.yoin.player.sing.SingFixtures.peak
import com.gpo.yoin.player.sing.SingFixtures.slice
import com.gpo.yoin.player.sing.SingFixtures.snrDb
import com.gpo.yoin.player.sing.SingFixtures.toArray
import com.gpo.yoin.player.sing.dsp.FullBandSeparator
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole pipeline with the real model on the desktop ONNX Runtime ([OrtReferenceModel], test only), against
 * Python onnxruntime (gen_sing_fixtures.py). Needs the model, which isn't in the repo:
 * `-PyoinSingModel=/path/lyra_sing_lite_fp32.onnx`; skipped otherwise.
 *
 * Tolerance: max |error| ≤ 2e-3 of the vocal's peak and SNR ≥ 60 dB. The paths differ only in float
 * rounding (torch's float32 STFT vs a double one here, ORT's kernel choice), which a deep net amplifies
 * a little.
 */
class LyraSingModelParityTest {
    private val modelPath = System.getProperty("yoin.sing.model")?.takeIf { it.isNotBlank() }

    private fun model(): OrtReferenceModel {
        assumeTrue("set -PyoinSingModel to run", modelPath != null && File(modelPath).isFile)
        return OrtReferenceModel.open(File(modelPath!!), threads = 4)
    }

    @Test
    fun should_matchPythonOnnxRuntime_when_synthetic3sClip() {
        model().use { model ->
            val rate = 44_100
            val (left, right) = SingFixtures.synth(3 * rate, rate)
            val started = System.nanoTime()
            val result = FullBandSeparator().separate(left.buffer(), right.buffer(), left.size, rate, model)
            val ms = (System.nanoTime() - started) / 1e6
            val expected = SingFixtures.load("model_synth3s_vocal_planar_stride4.f32")
            val n = expected.size / 2
            val gotL = FloatArray(n) { result.vocalLeft.get(it * 4) }
            val gotR = FloatArray(n) { result.vocalRight.get(it * 4) }
            val expL = expected.slice(0, n)
            val expR = expected.slice(n, n)
            report("synthetic 3 s", expL, gotL, expR, gotR, ms, result.chunks)
        }
    }

    @Test
    fun should_matchPythonOnnxRuntime_when_realClip() {
        val dir = System.getProperty("yoin.sing.clipDir")?.let(::File)
        assumeTrue("set -PyoinSingClipDir to run", dir != null && dir.isDirectory)
        model().use { model ->
            val rate = File(dir, "rate.txt").readText().trim().toInt()
            val clip = SingFixtures.loadFile(File(dir, "clip_planar.f32"))
            val n = clip.size / 2
            val expected = SingFixtures.loadFile(File(dir, "vocal_planar.f32"))
            val started = System.nanoTime()
            val result = FullBandSeparator().separate(clip.slice(0, n).buffer(), clip.slice(n, n).buffer(), n, rate, model)
            val ms = (System.nanoTime() - started) / 1e6
            val m = expected.size / 2
            report("real clip @ $rate Hz", expected.slice(0, m), result.vocalLeft.toArray(m), expected.slice(m, m), result.vocalRight.toArray(m), ms, result.chunks)
        }
    }

    /** Live Sing (chunks in the listener's order, a seek) against the whole-song run, with the real model. */
    @Test
    fun should_matchWholeSong_when_separatedLive() {
        model().use { model ->
            val rate = 44_100
            val frames = 20 * rate
            val (left, right) = SingFixtures.aperiodicSynth(frames, rate)
            val whole = FullBandSeparator().separate(left.buffer(), right.buffer(), frames, rate, model)
            val live = LiveVocalSeparator(left.buffer(), right.buffer(), frames, rate)
            val gotL = FloatArray(frames)
            val gotR = FloatArray(frames)
            val sink = LiveVocalSeparator.VocalSink { from, to, l, r, offset ->
                for (i in from until to) {
                    gotL[i] = l.get(i - offset)
                    gotR[i] = r.get(i - offset)
                }
            }
            live.start(sink)
            var run = 0
            while (true) {
                val at = if (run < 3) 12L * rate else 2L * rate
                val chunk = live.nextChunk(live.frameAtSource(at))
                if (chunk < 0) break
                live.runChunk(chunk, model, sink)
                run++
            }
            val err = maxOf(maxAbsError(whole.vocalLeft.toArray(frames), gotL), maxAbsError(whole.vocalRight.toArray(frames), gotR))
            println("live vs whole song (real model, 20 s, $run chunks): max |error| $err")
            assertTrue("live vs whole: $err", err <= 1e-5)
        }
    }

    /** Desktop JVM speed, for the report: one chunk through the model, and a 3-minute song end to end. */
    @Test
    fun should_reportDesktopSpeed_when_asked() {
        assumeTrue("set -PyoinSingPerf=true (with -PyoinSingModel) to run", System.getProperty("yoin.sing.perf") == "true")
        model().use { model ->
            val input = FloatArray(LyraSingSpec.CHUNK_FLOATS) { (it % 97) / 97f - 0.5f }
            val output = FloatArray(LyraSingSpec.CHUNK_FLOATS)
            repeat(2) { model.run(input, output) }
            val runs = 10
            val t0 = System.nanoTime()
            repeat(runs) { model.run(input, output) }
            val chunkMs = (System.nanoTime() - t0) / 1e6 / runs
            println("perf: one chunk ${"%.1f".format(chunkMs)} ms (CPU EP, 4 threads)")

            val rate = 44_100
            val (left, right) = SingFixtures.synth(180 * rate, rate)
            val started = System.nanoTime()
            val result = FullBandSeparator().separate(left.buffer(), right.buffer(), left.size, rate, model)
            val s = (System.nanoTime() - started) / 1e9
            println("perf: 180 s song in ${"%.1f".format(s)} s (${"%.1f".format(180 / s)}× real time, ${result.chunks} chunks)")
        }
    }

    private fun report(name: String, expL: FloatArray, gotL: FloatArray, expR: FloatArray, gotR: FloatArray, ms: Double, chunks: Int) {
        val errL = maxAbsError(expL, gotL)
        val errR = maxAbsError(expR, gotR)
        val peak = maxOf(peak(expL), peak(expR))
        val snrL = snrDb(expL, gotL)
        val snrR = snrDb(expR, gotR)
        println(
            "model parity $name: max |error| L $errL R $errR (vocal peak $peak), SNR L ${"%.1f".format(snrL)} dB " +
                "R ${"%.1f".format(snrR)} dB; $chunks chunks in ${"%.0f".format(ms)} ms",
        )
        assertTrue("max error ${maxOf(errL, errR)} vs peak $peak", maxOf(errL, errR) <= 2e-3 * peak)
        assertTrue("SNR $snrL / $snrR", minOf(snrL, snrR) >= 60.0)
    }
}
