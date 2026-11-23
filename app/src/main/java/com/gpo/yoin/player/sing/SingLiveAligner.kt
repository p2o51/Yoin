package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.Fft
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the audio the player is playing sits on a [SingVocalTrack]'s timeline, to the sample.
 *
 * The mixer knows a position for every frame (Media3's `StreamMetadata.positionOffsetUs` plus frames
 * since the flush), but that position isn't sample-exact everywhere: after a seek Media3 doesn't trim
 * the encoder delay again and shifts its offset by the trimmed delay and padding, MP3 seeking (Xing
 * table, constant-bitrate estimate) can land tens of milliseconds off, and Media3's extractors and the
 * framework's (which decoded X for the track) may count gapless delay differently. Subtracting V even
 * one millisecond off would comb-filter the voice instead of removing it.
 *
 * So the mixer captures a short stretch of what it is about to play ([SingAlignJob]) and this finds
 * it in the track's mono mix reference by normalised cross-correlation within ±[maxLagMs] of the
 * claimed position (FFT, off the audio thread). The audio is the same decoded file, so the true lag
 * correlates at ~1.0; anything under [MIN_CORRELATION] — another song, silence, a decoder that differs
 * — is no match, and the mixer passes the original through.
 */
object SingLiveAligner {
    /**
     * A match must correlate at least this well. The same decode correlates at ~0.9999 (16-bit
     * quantisation either side); a repeated chorus elsewhere in the song can reach ~0.98.
     */
    const val MIN_CORRELATION = 0.99

    /** Quieter captures than this (RMS, ≈ −66 dBFS) can't be placed; the mixer tries a later stretch. */
    const val MIN_RMS = 5e-4

    /** How far from the claimed position to look. */
    const val MAX_LAG_MS = 500

    /** Peaks this close to the best count as ties; the one nearest the claimed position wins. */
    private const val TIE = 0.002

    sealed interface Result {
        /** The capture starts at track frame (claimed start + [offset]). */
        data class Found(val offset: Long, val correlation: Double) : Result

        /** Too quiet to place. */
        data object Quiet : Result

        /** Nothing within reach correlates well enough. */
        data object NoMatch : Result
    }

    fun align(
        capture: FloatArray,
        count: Int,
        claimedStart: Long,
        track: SingVocalTrack,
        maxLagMs: Int = MAX_LAG_MS,
    ): Result {
        var energy = 0.0
        for (i in 0 until count) energy += capture[i].toDouble() * capture[i]
        if (count == 0 || sqrt(energy / count) < MIN_RMS) return Result.Quiet

        val maxLag = maxLagMs.toLong() * track.sampleRate / 1000L
        val refStart = (claimedStart - maxLag).coerceIn(0L, track.frames.toLong()).toInt()
        val refEnd = (claimedStart + count + maxLag).coerceIn(0L, track.frames.toLong()).toInt()
        val refLength = refEnd - refStart
        if (refLength < count) return Result.NoMatch

        var n = 1
        while (n < refLength + count) n = n shl 1
        val fft = Fft(n)
        val aRe = DoubleArray(n)
        val aIm = DoubleArray(n)
        val bRe = DoubleArray(n)
        val bIm = DoubleArray(n)
        val prefix = DoubleArray(refLength + 1)
        for (i in 0 until refLength) {
            val v = track.reference(refStart + i).toDouble()
            aRe[i] = v
            prefix[i + 1] = prefix[i] + v * v
        }
        for (i in 0 until count) bRe[i] = capture[i].toDouble()
        fft.forward(aRe, aIm)
        fft.forward(bRe, bIm)
        // A·conj(B): its inverse is r[k] = Σ ref[k + i]·capture[i].
        for (k in 0 until n) {
            val re = aRe[k] * bRe[k] + aIm[k] * bIm[k]
            val im = aIm[k] * bRe[k] - aRe[k] * bIm[k]
            aRe[k] = re
            aIm[k] = im
        }
        fft.inverse(aRe, aIm)

        val lags = refLength - count
        val score = DoubleArray(lags + 1)
        var best = -1.0
        for (k in 0..lags) {
            val refEnergy = prefix[k + count] - prefix[k]
            score[k] = if (refEnergy > 1e-12) aRe[k] / sqrt(energy * refEnergy) else 0.0
            if (score[k] > best) best = score[k]
        }
        if (best < MIN_CORRELATION) return Result.NoMatch
        var chosen = -1
        var chosenDistance = Long.MAX_VALUE
        for (k in 0..lags) {
            if (score[k] < best - TIE) continue
            val distance = abs(refStart + k - claimedStart)
            if (distance < chosenDistance) {
                chosen = k
                chosenDistance = distance
            }
        }
        return Result.Found(refStart + chosen - claimedStart, score[chosen])
    }
}

/**
 * One alignment request, owned by one [SingMixerAudioProcessor] and reused: the processor fills
 * [capture] (state [FILLING]), hands it over ([READY]); the executor runs [SingLiveAligner.align] and
 * leaves the answer ([DONE]) for the processor to pick up. The volatile [state] orders the hand-offs,
 * so neither side touches the arrays while the other owns them.
 */
class SingAlignJob(captureFrames: Int) {
    val capture = FloatArray(captureFrames)
    var count = 0
    var claimedStart = 0L
    var track: SingVocalTrack? = null
    var epoch = 0

    @Volatile var state = IDLE

    var result: SingLiveAligner.Result = SingLiveAligner.Result.NoMatch

    /** Runs the alignment (executor side): READY → DONE. */
    fun run() {
        val t = track
        result = try {
            if (t == null) SingLiveAligner.Result.NoMatch else SingLiveAligner.align(capture, count, claimedStart, t)
        } catch (e: RuntimeException) {
            SingLiveAligner.Result.NoMatch
        }
        state = DONE
    }

    companion object {
        const val IDLE = 0
        const val FILLING = 1
        const val READY = 2
        const val DONE = 3
    }
}

/** Runs [SingAlignJob]s somewhere other than the audio thread (tests run them inline). */
fun interface SingAlignExecutor {
    fun submit(job: SingAlignJob)

    companion object {
        /** Runs the job right away, on the caller's thread: for tests. */
        val INLINE = SingAlignExecutor { it.run() }
    }
}

/**
 * One daemon thread for the mixer's alignments. [submit] only stores a reference and unparks the
 * thread: no allocation and no lock on the audio thread.
 */
class SingAlignThread : SingAlignExecutor {
    @Volatile private var pending: SingAlignJob? = null

    private val thread: Thread by lazy {
        Thread(::loop, "YoinSingAlign").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /** Starts the thread (call off the audio thread, e.g. when the mixer is configured). */
    fun prepare() {
        thread
    }

    override fun submit(job: SingAlignJob) {
        pending = job
        LockSupport.unpark(thread)
    }

    private fun loop() {
        while (true) {
            val job = pending
            if (job == null) {
                LockSupport.park(this)
                continue
            }
            pending = null
            job.run()
        }
    }
}
