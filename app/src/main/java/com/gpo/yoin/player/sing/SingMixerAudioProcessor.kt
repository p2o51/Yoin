package com.gpo.yoin.player.sing

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Sing mixer in the audio sink's chain (PlaybackService). A Sing stream is 4-channel PCM
 * [I_L, I_R, V_L, V_R] — instrumental and vocal — and leaves as stereo
 *
 *     Y_L = I_L + g·V_L,  Y_R = I_R + g·V_R
 *
 * at the gain [SingMixControl] asks for. Every change of g ramps sample by sample along the control's
 * curve (20 ms linear for slider drags, ~950 ms ease-out cubic on Sing cut-in, ~750 ms ease-in-out on
 * Sing exit), so nothing clicks and transitions feel silky. Anything else — stereo, mono, 5.1, a quad
 * file while Sing is off — makes this processor inactive, and the sink passes it by untouched.
 */
/**
 * The Sing mixer in the audio sink's chain (PlaybackService), first in the chain so volume levelling
 * sees what it always sees. Two modes, chosen per stream format:
 *
 * **Live** (stereo or mono PCM — every on-device Sing): the original keeps playing, and the mixer
 * takes the vocal out of it as it passes, from the song's [SingVocalTrack] bound in
 * [SingMixControl.liveBinding]:
 *
 *     out = X − a·(1 − g)·V
 *
 * - Position: every frame's place on the song's timeline is Media3's `StreamMetadata.positionOffsetUs`
 *   from the last [flush] plus the frames since (a flush with no metadata — [AudioProcessor.flush()] —
 *   leaves the position unknown, and the mixer passes through until a flush brings one). The stream's
 *   `periodUid` must be the bound song's. The position is then made sample-exact by finding a short
 *   capture of the input in the track's mix reference ([SingLiveAligner], off the audio thread).
 * - a, availability: 0 until the stretch is aligned; then 1 wherever V is in, gliding linearly over
 *   [AVAILABILITY_RAMP_MS] — down *before* a stretch without V (the mixer looks that far ahead), up
 *   once it's in. So a gap in V is the original vocal fading back in and out, never a step.
 * - g, the vocal gain (the slider, [SingMixControl]): ramps along the control's curve — 20 ms linear
 *   for the slider, ~950 ms ease-out cubic when Sing cuts in (from 1.0, the moment V first applies for
 *   a binding: [SingMixControl.startAtUnity]), ~750 ms ease-in-out back to 1.0 when Sing turns off.
 * - Unbound, or bound to another song: X passes through untouched.
 *
 * **Quad** (4-channel PCM while [SingMixControl.expectQuad] — the Lyra server's quad.m4a,
 * [I_L, I_R, V_L, V_R]): leaves as stereo Y = I + g·V, g ramped as above.
 *
 * Anything else (5.1, 24-bit) makes the mixer inactive and the sink passes it by. 16-bit and float PCM,
 * the two encodings the sink's chain carries. The audio thread allocates nothing and takes no lock.
 */
@OptIn(UnstableApi::class)
class SingMixerAudioProcessor(
    private val control: SingMixControl,
    private val aligner: SingAlignExecutor = SingAlignThread(),
) : BaseAudioProcessor() {
    private var currentGain = 1f
    private var rampStartGain = 1f
    private var rampTarget = 1f
    private var rampTotal = 0
    private var rampElapsed = 0
    private var rampCurve = SingRampCurve.Linear
    private var seenGeneration = -1
    private var begun = false

    /** Set by [onConfigure] for the stream to come, applied at the next flush (as the formats are). */
    private var pendingMode = MODE_OFF
    private var mode = MODE_OFF

    // Live mode, all on the audio thread.
    private var epoch = 0
    private var epochKnown = false
    private var epochPeriodUid: Any? = null
    private var epochBaseFrame = 0L
    private var framesSinceFlush = 0L
    private var active: SingLiveBinding? = null
    private var aligned = false
    private var alignOffset = 0L
    private var retryAfterFrame = 0L
    private var avail = 0f
    private var availRampFrames = 1
    private var availStep = 1f
    private var captureFrames = 1
    private var skipFrames = 0
    private val job = SingAlignJob(MAX_CAPTURE_FRAMES)

    /** The gain applied to the last frame out, for tests and the controller's state. */
    val appliedGain: Float get() = currentGain

    /** Live mode's availability a (0..1) after the last frame, for tests. */
    val availability: Float get() = avail

    /** Whether the live stream is placed on the bound track, and the correction found. */
    val isAligned: Boolean get() = aligned
    val alignmentOffset: Long get() = alignOffset

    /** The claimed track frame of the next input frame (Media3's position; null when unknown). */
    val nextMediaFrame: Long? get() = if (epochKnown) epochBaseFrame + framesSinceFlush else null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!pcm) return notActive()
        return when {
            inputAudioFormat.channelCount == 4 && control.expectQuad -> {
                pendingMode = MODE_QUAD
                AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
            }
            inputAudioFormat.channelCount == 1 || inputAudioFormat.channelCount == 2 -> {
                pendingMode = MODE_LIVE
                (aligner as? SingAlignThread)?.prepare()
                inputAudioFormat
            }
            else -> notActive()
        }
    }

    private fun notActive(): AudioProcessor.AudioFormat {
        pendingMode = MODE_OFF
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        begun = false
        mode = pendingMode
        val sampleRate = inputAudioFormat.sampleRate
        if (mode == MODE_LIVE && sampleRate > 0) {
            availRampFrames = maxOf(1, AVAILABILITY_RAMP_MS * sampleRate / 1000)
            availStep = 1f / availRampFrames
            captureFrames = (CAPTURE_MS * sampleRate / 1000).coerceIn(1, MAX_CAPTURE_FRAMES)
            skipFrames = SKIP_MS * sampleRate / 1000
        }
        // DEFAULT is what a plain flush() sends: no position. Media3's sink always sends its own.
        val known = streamMetadata !== AudioProcessor.StreamMetadata.DEFAULT
        val base = if (known && sampleRate > 0) {
            Math.round(streamMetadata.positionOffsetUs.toDouble() * sampleRate / 1_000_000.0)
        } else {
            0L
        }
        val continuation = epochBaseFrame + framesSinceFlush
        if (known && epochKnown && streamMetadata.periodUid == epochPeriodUid && abs(base - continuation) <= CONTINUITY_FRAMES) {
            // The sink re-flushed mid-stream (a speed change, a timestamp resync) where the stream
            // carries on: keep the stream's place and its alignment, so V doesn't drop out.
            epochBaseFrame = continuation
            framesSinceFlush = 0L
            return
        }
        resetEpoch(known, streamMetadata.periodUid, base)
    }

    private fun resetEpoch(known: Boolean, periodUid: Any?, baseFrame: Long) {
        epoch++
        epochKnown = known
        epochPeriodUid = periodUid
        epochBaseFrame = baseFrame
        framesSinceFlush = 0L
        aligned = false
        alignOffset = 0L
        retryAfterFrame = 0L
        avail = 0f
        if (job.state == SingAlignJob.FILLING) job.state = SingAlignJob.IDLE
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val frames = (inputBuffer.limit() - position) / inputAudioFormat.bytesPerFrame
        if (frames == 0) return
        if (mode == MODE_QUAD) queueQuad(inputBuffer, position, frames) else queueLive(inputBuffer, position, frames)
    }

    private fun queueLive(input: ByteBuffer, position: Int, frames: Int) {
        if (seenGeneration == -1) {
            // First samples since the mixer was set up: take the gain as it stands, no glide.
            currentGain = control.targetGain
            rampTotal = 0
            seenGeneration = control.generation
        }
        pickUpTarget()
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val desired = control.liveBinding
        if (active !== desired && avail == 0f) switchTo(desired)
        val binding = active
        val output = replaceOutputBuffer(frames * bytesPerFrame)
        if (binding == null) {
            // Nothing bound: the original, untouched.
            advanceGain(frames)
            output.put(input)
            framesSinceFlush += frames
            output.flip()
            return
        }

        val track = binding.track
        val channels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val placed = epochKnown && track.sampleRate == inputAudioFormat.sampleRate &&
            (binding.periodUid == null || epochPeriodUid == null || binding.periodUid == epochPeriodUid)
        if (placed && !aligned && binding === desired) serviceAlignment(binding)
        val readable = placed && aligned
        val base = epochBaseFrame + framesSinceFlush + alignOffset
        val coveredEnd = if (readable) track.readyEnd(base, base + frames + availRampFrames + 1) else Long.MIN_VALUE
        var capturing = job.state == SingAlignJob.FILLING && job.epoch == epoch
        val trackFrames = track.frames

        var at = position
        for (f in 0 until frames) {
            val left: Float
            val right: Float
            if (float) {
                left = input.getFloat(at)
                right = if (channels == 2) input.getFloat(at + 4) else left
            } else {
                left = input.getShort(at) * FROM_16
                right = if (channels == 2) input.getShort(at + 2) * FROM_16 else left
            }
            if (capturing) {
                job.capture[job.count++] = if (channels == 2) 0.5f * (left + right) else left
                if (job.count >= captureFrames) {
                    job.state = SingAlignJob.READY
                    aligner.submit(job)
                    capturing = false
                }
            }
            val index = base + f
            val want = readable && binding === desired && coveredEnd - index > availRampFrames
            if (want && !binding.engaged) engage(binding)
            val g = nextGain()
            avail = if (want) minOf(1f, avail + availStep) else maxOf(0f, avail - availStep)
            val subtract = avail > 0f && readable && index >= 0 && index < coveredEnd && index < trackFrames
            if (!subtract) {
                // Can't happen while V is read ahead; never subtract what isn't there.
                if (avail > 0f && !(readable && index < coveredEnd)) avail = 0f
                if (float) {
                    output.putFloat(left)
                    if (channels == 2) output.putFloat(right)
                } else {
                    output.putShort(input.getShort(at))
                    if (channels == 2) output.putShort(input.getShort(at + 2))
                }
            } else {
                val s = avail * (1f - g)
                val i = index.toInt()
                if (channels == 2) {
                    val outL = left - s * track.vocalLeft(i)
                    val outR = right - s * track.vocalRight(i)
                    if (float) {
                        output.putFloat(outL.coerceIn(-1f, 1f))
                        output.putFloat(outR.coerceIn(-1f, 1f))
                    } else {
                        output.putShort(clip16(outL * 32768f))
                        output.putShort(clip16(outR * 32768f))
                    }
                } else {
                    val out = left - s * track.vocalMono(i)
                    if (float) output.putFloat(out.coerceIn(-1f, 1f)) else output.putShort(clip16(out * 32768f))
                }
            }
            at += bytesPerFrame
        }
        input.position(position + frames * bytesPerFrame)
        framesSinceFlush += frames
        output.flip()
    }

    /** V applies for [binding] for the first time: Sing cuts in, from 1.0 when it was just turned on. */
    private fun engage(binding: SingLiveBinding) {
        binding.engaged = true
        if (control.startAtUnity) {
            control.startAtUnity = false
            currentGain = 1f
            startRamp(control.targetGain, SingMixControl.CUT_IN_GLIDE_MS, SingRampCurve.EaseOutCubic)
            seenGeneration = control.generation
        }
    }

    private fun switchTo(binding: SingLiveBinding?) {
        active = binding
        aligned = false
        alignOffset = 0L
        retryAfterFrame = 0L
        if (job.state == SingAlignJob.FILLING) job.state = SingAlignJob.IDLE
    }

    /** Picks up a finished alignment, or starts a capture: once per buffer, before its frames. */
    private fun serviceAlignment(binding: SingLiveBinding) {
        when (job.state) {
            SingAlignJob.DONE -> {
                val result = job.result
                val current = job.epoch == epoch && job.track === binding.track
                job.state = SingAlignJob.IDLE
                if (current) {
                    when (result) {
                        is SingLiveAligner.Result.Found -> {
                            aligned = true
                            alignOffset = result.offset
                            return
                        }
                        SingLiveAligner.Result.Quiet -> retryAfterFrame = framesSinceFlush + QUIET_RETRY_MS.toLong() * inputAudioFormat.sampleRate / 1000
                        SingLiveAligner.Result.NoMatch -> retryAfterFrame = framesSinceFlush + NO_MATCH_RETRY_MS.toLong() * inputAudioFormat.sampleRate / 1000
                    }
                }
                startCapture(binding)
            }
            SingAlignJob.IDLE -> startCapture(binding)
            else -> Unit // filling, or the aligner has it
        }
    }

    private fun startCapture(binding: SingLiveBinding) {
        if (framesSinceFlush < skipFrames || framesSinceFlush < retryAfterFrame) return
        job.track = binding.track
        job.epoch = epoch
        job.claimedStart = epochBaseFrame + framesSinceFlush
        job.count = 0
        job.state = SingAlignJob.FILLING
    }

    private fun advanceGain(frames: Int) {
        if (rampTotal == 0) return
        repeat(frames) { nextGain() }
    }

    private fun queueQuad(inputBuffer: ByteBuffer, position: Int, frames: Int) {
        if (!begun) begin()
        pickUpTarget()
        val map = control.channelMap
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        val output = replaceOutputBuffer(frames * 2 * bytes)
        var at = position
        repeat(frames) {
            val g = nextGain()
            if (float) {
                val iL = inputBuffer.getFloat(at + map[0] * 4)
                val iR = inputBuffer.getFloat(at + map[1] * 4)
                val vL = inputBuffer.getFloat(at + map[2] * 4)
                val vR = inputBuffer.getFloat(at + map[3] * 4)
                output.putFloat((iL + g * vL).coerceIn(-1f, 1f))
                output.putFloat((iR + g * vR).coerceIn(-1f, 1f))
            } else {
                val iL = inputBuffer.getShort(at + map[0] * 2).toFloat()
                val iR = inputBuffer.getShort(at + map[1] * 2).toFloat()
                val vL = inputBuffer.getShort(at + map[2] * 2).toFloat()
                val vR = inputBuffer.getShort(at + map[3] * 2).toFloat()
                output.putShort(clip16(iL + g * vL))
                output.putShort(clip16(iR + g * vR))
            }
            at += 4 * bytes
        }
        inputBuffer.position(position + frames * 4 * bytes)
        output.flip()
    }

    override fun onReset() {
        pendingMode = MODE_OFF
        mode = MODE_OFF
        resetEpoch(known = false, periodUid = null, baseFrame = 0L)
        active = null
        currentGain = 1f
        rampStartGain = 1f
        rampTarget = 1f
        rampTotal = 0
        rampElapsed = 0
        rampCurve = SingRampCurve.Linear
        seenGeneration = -1
        begun = false
    }

    /** First samples of a stream (after configure, a seek or a new song). */
    private fun begin() {
        begun = true
        if (control.startAtUnity) {
            control.startAtUnity = false
            currentGain = 1f
            startRamp(control.targetGain, SingMixControl.CUT_IN_GLIDE_MS, SingRampCurve.EaseOutCubic)
            seenGeneration = control.generation
        } else if (seenGeneration == -1) {
            // Already on when this stream began (the next song, a seek): no glide to make.
            currentGain = control.targetGain
            rampStartGain = currentGain
            rampTarget = currentGain
            rampTotal = 0
            rampElapsed = 0
            seenGeneration = control.generation
        }
    }

    private fun pickUpTarget() {
        val generation = control.generation
        if (generation == seenGeneration) return
        seenGeneration = generation
        startRamp(control.targetGain, control.rampMs, control.rampCurve)
    }

    private fun startRamp(target: Float, ms: Int, curve: SingRampCurve) {
        val samples = (ms.toLong() * inputAudioFormat.sampleRate / 1000L).toInt()
        if (samples <= 0) {
            currentGain = target
            rampStartGain = target
            rampTarget = target
            rampTotal = 0
            rampElapsed = 0
            rampCurve = curve
            return
        }
        rampStartGain = currentGain
        rampTarget = target
        rampTotal = samples
        rampElapsed = 0
        rampCurve = curve
    }

    private fun nextGain(): Float {
        if (rampTotal > 0 && rampElapsed < rampTotal) {
            rampElapsed++
            currentGain = if (rampElapsed >= rampTotal) {
                rampTotal = 0
                rampTarget
            } else {
                val progress = rampCurve.evaluate(rampElapsed.toFloat() / rampTotal.toFloat())
                rampStartGain + (rampTarget - rampStartGain) * progress
            }
        }
        return currentGain
    }

    private fun clip16(v: Float): Short = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    companion object {
        /** Availability (V in or not) glides over this long. */
        const val AVAILABILITY_RAMP_MS = 120

        /** The stretch of input captured to align a stream. */
        const val CAPTURE_MS = 150

        /** Skipped after a flush before capturing: a decoder's first frames after a seek can differ. */
        const val SKIP_MS = 60

        /** After a stretch that matched nowhere, wait this long before trying again. */
        const val NO_MATCH_RETRY_MS = 1_000

        /** After a stretch too quiet to place (a silent intro), wait this long. */
        const val QUIET_RETRY_MS = 100

        /** A flush this close to where the stream would have carried on is the same stream. */
        private const val CONTINUITY_FRAMES = 2

        private const val MAX_CAPTURE_FRAMES = CAPTURE_MS * 192
        private const val MODE_OFF = 0
        private const val MODE_QUAD = 1
        private const val MODE_LIVE = 2
        private const val FROM_16 = 1f / 32768f
    }
}
