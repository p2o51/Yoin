package com.gpo.yoin.player.sing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingMixerAudioProcessorTest {
    private val rate = 44_100
    private val control = SingMixControl().apply { expectQuad = true }
    private val mixer = SingMixerAudioProcessor(control)

    private fun configure(channels: Int, encoding: Int = C.ENCODING_PCM_FLOAT): AudioProcessor.AudioFormat {
        val out = mixer.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        mixer.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return out
    }

    /** Frames of [I_L, I_R, V_L, V_R], all the same. */
    private fun quadFloat(frames: Int, iL: Float, iR: Float, vL: Float, vR: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 16).order(ByteOrder.nativeOrder())
        repeat(frames) {
            b.putFloat(iL)
            b.putFloat(iR)
            b.putFloat(vL)
            b.putFloat(vR)
        }
        b.flip()
        return b
    }

    private fun mixFloat(input: ByteBuffer): FloatArray {
        mixer.queueInput(input)
        val out = mixer.output
        return FloatArray(out.remaining() / 4) { out.getFloat(out.position() + it * 4) }
    }

    @Test
    fun should_passThrough_when_stereo() {
        configure(2)
        assertFalse(mixer.isActive)
    }

    @Test
    fun should_passThrough_when_quadButSingOff() {
        control.expectQuad = false
        configure(4)
        assertFalse(mixer.isActive)
    }

    @Test
    fun should_passThrough_when_notPcm16OrFloat() {
        configure(4, C.ENCODING_PCM_24BIT)
        assertFalse(mixer.isActive)
    }

    @Test
    fun should_outputStereo_when_quadSingStream() {
        val out = configure(4)
        assertTrue(mixer.isActive)
        assertEquals(2, out.channelCount)
        assertEquals(rate, out.sampleRate)
    }

    @Test
    fun should_addGainTimesVocal_when_floatMixed() {
        control.setTarget(0.25f, rampMs = 0)
        configure(4)
        val y = mixFloat(quadFloat(10, iL = 0.1f, iR = -0.2f, vL = 0.4f, vR = 0.8f))
        assertEquals(20, y.size)
        for (f in 0 until 10) {
            assertEquals(0.1f + 0.25f * 0.4f, y[f * 2], 1e-6f)
            assertEquals(-0.2f + 0.25f * 0.8f, y[f * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun should_mixAndClip_when_pcm16() {
        control.setTarget(1f, rampMs = 0)
        configure(4, C.ENCODING_PCM_16BIT)
        val b = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.nativeOrder())
        b.putShort(1000).putShort(30000).putShort(500).putShort(10000)
        b.putShort(-1000).putShort(-30000).putShort(-500).putShort(-10000)
        b.flip()
        mixer.queueInput(b)
        val out = mixer.output
        assertEquals(8, out.remaining())
        assertEquals(1500, out.getShort(0).toInt())
        assertEquals(Short.MAX_VALUE, out.getShort(2))
        assertEquals(-1500, out.getShort(4).toInt())
        assertEquals(Short.MIN_VALUE, out.getShort(6))
    }

    @Test
    fun should_rampLinearlyOver20ms_when_gainChanges() {
        control.setTarget(1f, rampMs = 0)
        configure(4)
        mixFloat(quadFloat(10, 0f, 0f, 1f, 1f))
        control.setTarget(0f) // 20 ms default ramp = 882 samples
        val ramp = SingMixControl.DEFAULT_RAMP_MS * rate / 1000
        val y = mixFloat(quadFloat(ramp + 10, 0f, 0f, 1f, 1f))
        for (f in 0 until ramp) {
            assertEquals(1f - (f + 1).toFloat() / ramp, y[f * 2], 1e-4f)
        }
        // No step bigger than one ramp increment anywhere: nothing clicks.
        for (f in 1 until ramp + 10) assertTrue(kotlin.math.abs(y[f * 2] - y[(f - 1) * 2]) <= 1f / ramp + 1e-5f)
        assertEquals(0f, y[(ramp + 5) * 2], 0f)
    }

    @Test
    fun should_startAtUnityAndEaseOutGlide_when_singTurnsOn() {
        control.setTarget(SingGainCurve.DEFAULT_GAIN, SingMixControl.CUT_IN_GLIDE_MS, SingRampCurve.EaseOutCubic)
        control.startAtUnity = true
        configure(4)
        val glide = SingMixControl.CUT_IN_GLIDE_MS * rate / 1000
        val y = mixFloat(quadFloat(glide + 100, 0f, 0f, 1f, 1f))
        assertEquals(1f, y[0], 1e-4f) // first frame: the mix, no jump from the original stream
        // At 50% of EaseOutCubic, progress = 1 - 0.5^3 = 0.875 → gain ≈ 1.0 + (0.12 - 1.0) * 0.875 = 0.23
        val expectedMid = 1f + (SingGainCurve.DEFAULT_GAIN - 1f) * 0.875f
        assertEquals(expectedMid, y[(glide / 2 - 1) * 2], 5e-3f)
        assertEquals(SingGainCurve.DEFAULT_GAIN, y[(glide + 50) * 2], 1e-6f)
        for (f in 1 until glide) assertTrue(y[f * 2] <= y[(f - 1) * 2] + 1e-6f)
        assertFalse(control.startAtUnity)
    }

    @Test
    fun should_easeInOutBackToUnity_when_exitingSing() {
        control.setTarget(SingGainCurve.DEFAULT_GAIN, rampMs = 0)
        configure(4)
        mixFloat(quadFloat(10, 0f, 0f, 1f, 1f))
        control.setTarget(1f, SingMixControl.CUT_OUT_GLIDE_MS, SingRampCurve.EaseInOut)
        val glide = SingMixControl.CUT_OUT_GLIDE_MS * rate / 1000
        val y = mixFloat(quadFloat(glide + 100, 0f, 0f, 1f, 1f))
        assertEquals(SingGainCurve.DEFAULT_GAIN, y[0], 1e-3f)
        assertEquals((SingGainCurve.DEFAULT_GAIN + 1f) / 2f, y[(glide / 2 - 1) * 2], 5e-3f)
        assertEquals(1f, y[(glide + 50) * 2], 1e-6f)
        for (f in 1 until glide) assertTrue(y[f * 2] >= y[(f - 1) * 2] - 1e-6f)
    }

    @Test
    fun should_keepGain_when_flushedBySeek() {
        control.setTarget(0.3f, rampMs = 0)
        configure(4)
        mixFloat(quadFloat(5, 0f, 0f, 1f, 1f))
        mixer.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val y = mixFloat(quadFloat(5, 0f, 0f, 1f, 1f))
        assertEquals(0.3f, y[0], 1e-6f)
    }

    @Test
    fun should_readMappedChannels_when_decoderReorders() {
        control.setTarget(0.5f, rampMs = 0)
        control.channelMap = intArrayOf(2, 3, 0, 1) // decoded as [V_L, V_R, I_L, I_R]
        configure(4)
        val y = mixFloat(quadFloat(1, 0.4f, 0.8f, 0.1f, 0.2f))
        assertEquals(0.1f + 0.5f * 0.4f, y[0], 1e-6f)
        assertEquals(0.2f + 0.5f * 0.8f, y[1], 1e-6f)
    }
}
