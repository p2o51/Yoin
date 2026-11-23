package com.gpo.yoin.player.sing

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
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
@OptIn(UnstableApi::class)
class SingMixerAudioProcessor(private val control: SingMixControl) : BaseAudioProcessor() {
    private var currentGain = 1f
    private var rampStartGain = 1f
    private var rampTarget = 1f
    private var rampTotal = 0
    private var rampElapsed = 0
    private var rampCurve = SingRampCurve.Linear
    private var seenGeneration = -1
    private var begun = false

    /** The gain applied to the last frame out, for tests and the controller's state. */
    val appliedGain: Float get() = currentGain

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!pcm || inputAudioFormat.channelCount != 4 || !control.expectQuad) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        begun = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frames = (limit - position) / inputAudioFormat.bytesPerFrame
        if (frames == 0) return
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
}
