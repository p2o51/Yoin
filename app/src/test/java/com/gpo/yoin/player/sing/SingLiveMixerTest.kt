package com.gpo.yoin.player.sing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mixer's live mode: position, alignment, subtraction, availability glides. */
class SingLiveMixerTest {
    private val rate = 44_100
    private val frames = rate * 3
    private val uid = Any()
    private val control = SingMixControl()
    private val mixer = SingMixerAudioProcessor(control, SingAlignExecutor.INLINE)
    private val mix = SingFixtures.aperiodicSynth(frames, rate)
    private val xL = mix.first
    private val xR = mix.second
    private val vL = FloatArray(frames) { (0.3 * sin(2 * PI * 523.0 * it / rate) * (0.6 + 0.4 * sin(2 * PI * 0.7 * it / rate))).toFloat() }
    private val vR = FloatArray(frames) { (0.2 * cos(2 * PI * 659.0 * it / rate)).toFloat() }
    private val ramp = SingMixerAudioProcessor.AVAILABILITY_RAMP_MS * rate / 1000

    /** A track with X's reference and V ([vocal] or [vL]/[vR]) in the given frame ranges. */
    private fun track(
        ready: List<IntRange> = listOf(0 until frames),
        left: FloatArray = vL,
        right: FloatArray = vR,
    ): SingVocalTrack {
        val track = SingVocalTrack.inMemory(rate, frames)
        track.writeReference(FloatBuffer.wrap(xL), FloatBuffer.wrap(xR))
        for (range in ready) {
            track.writeVocal(range.first, range.last + 1, FloatBuffer.wrap(left), FloatBuffer.wrap(right))
        }
        return track
    }

    private fun metadata(frame: Long, periodUid: Any? = uid) = AudioProcessor.StreamMetadata.Builder()
        .setPositionOffsetUs(frame * 1_000_000L / rate)
        .setPeriodUid(periodUid)
        .build()

    private fun start(channels: Int = 2, claimedFrame: Long = 0, periodUid: Any? = uid) {
        mixer.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        mixer.flush(metadata(claimedFrame, periodUid))
    }

    /** Plays track frames [from, to) of X through the mixer, 1024 frames a buffer; returns the output. */
    private fun play(from: Int, to: Int, channels: Int = 2): FloatArray {
        val out = FloatArray((to - from) * channels)
        var at = from
        var o = 0
        while (at < to) {
            val n = minOf(1024, to - at)
            val b = ByteBuffer.allocateDirect(n * channels * 4).order(ByteOrder.nativeOrder())
            for (i in at until at + n) {
                if (channels == 2) {
                    b.putFloat(xL[i])
                    b.putFloat(xR[i])
                } else {
                    b.putFloat(0.5f * (xL[i] + xR[i]))
                }
            }
            b.flip()
            mixer.queueInput(b)
            val y = mixer.output
            while (y.hasRemaining()) out[o++] = y.float
            at += n
        }
        return out
    }

    @Test
    fun should_trackMediaPosition_when_flushedWithStreamMetadata() {
        start(claimedFrame = 2L * rate)
        assertEquals(2L * rate, mixer.nextMediaFrame)
        play(0, 1000)
        assertEquals(2L * rate + 1000, mixer.nextMediaFrame)
        mixer.flush(metadata(5L * rate))
        assertEquals(5L * rate, mixer.nextMediaFrame)
        play(0, 300)
        assertEquals(5L * rate + 300, mixer.nextMediaFrame)
        // A plain flush() brings no position: unknown until the next flush with metadata.
        mixer.flush()
        assertNull(mixer.nextMediaFrame)
        mixer.flush(metadata(rate.toLong()))
        assertEquals(rate.toLong(), mixer.nextMediaFrame)
    }

    @Test
    fun should_passThroughUntouched_when_nothingBound() {
        start()
        assertTrue(mixer.isActive)
        val y = play(0, 5000)
        for (i in 0 until 5000) {
            assertEquals(xL[i], y[i * 2], 0f)
            assertEquals(xR[i], y[i * 2 + 1], 0f)
        }
    }

    @Test
    fun should_subtractVocal_when_gainZero_andPassMix_when_gainOne() {
        control.setTarget(0f, rampMs = 0)
        control.liveBinding = SingLiveBinding(track(), "k", uid)
        start()
        val y = play(0, rate * 2)
        assertTrue(mixer.isAligned)
        assertEquals(0L, mixer.alignmentOffset)
        val settled = rate / 2 // past the skip, the capture and the 120 ms glide
        for (i in settled until rate * 2) {
            assertEquals("L at $i", xL[i] - vL[i], y[i * 2], 2e-4f)
            assertEquals("R at $i", xR[i] - vR[i], y[i * 2 + 1], 2e-4f)
        }
        control.setTarget(1f, rampMs = 0)
        val z = play(rate * 2, rate * 2 + 4000)
        for (i in 0 until 4000) {
            assertEquals(xL[rate * 2 + i], z[i * 2], 0f)
            assertEquals(xR[rate * 2 + i], z[i * 2 + 1], 0f)
        }
    }

    @Test
    fun should_alignToTheSample_when_media3PositionIsOff() {
        control.setTarget(0f, rampMs = 0)
        control.liveBinding = SingLiveBinding(track(), "k", uid)
        // Media3 claims the stream starts 1105 frames later than it does (an untrimmed encoder delay).
        val truth = rate / 4
        start(claimedFrame = truth + 1105L)
        val y = play(truth, truth + rate * 2)
        assertTrue(mixer.isAligned)
        assertEquals(-1105L, mixer.alignmentOffset)
        for (i in rate / 2 until rate * 2) {
            val t = truth + i
            assertEquals(xL[t] - vL[t], y[i * 2], 2e-4f)
        }
    }

    /** A track whose V is [v] everywhere it's in, so out = X − s·V gives s back. */
    private fun constantTrack(v: Float, ready: List<IntRange> = listOf(0 until frames)): SingVocalTrack {
        val constant = FloatArray(frames) { v }
        return track(ready, constant, constant)
    }

    @Test
    fun should_glideToOriginalAndBack_when_vocalMissingForAStretch() {
        control.setTarget(0f, rampMs = 0)
        val block = SingVocalTrack.BLOCK_FRAMES
        val v = 0.1f
        val gapStart = block * 8
        val gapEnd = block * 12
        control.liveBinding = SingLiveBinding(constantTrack(v, listOf(0 until gapStart, gapEnd until frames)), "k", uid)
        start()
        val y = play(0, frames)
        // s = how much of V is taken out: out = X − s·V.
        val s = FloatArray(frames) { (xL[it] - y[it * 2]) / v }
        val step = 1f / ramp
        for (i in 1 until frames) assertTrue("step at $i: ${s[i] - s[i - 1]}", abs(s[i] - s[i - 1]) <= step + 2e-3f)
        assertEquals(1f, s[gapStart - ramp - 50], 2e-3f) // fully out before the gap…
        assertEquals(0f, s[gapStart], 2e-3f) // …faded back in by its first frame
        for (i in gapStart until gapEnd) assertEquals(0f, s[i], 2e-3f)
        assertEquals(1f, s[gapEnd + ramp + 50], 2e-3f)
    }

    @Test
    fun should_subtractMonoFoldedVocal_when_streamIsMono() {
        control.setTarget(0f, rampMs = 0)
        control.liveBinding = SingLiveBinding(track(), "k", uid)
        start(channels = 1)
        val y = play(0, rate * 2, channels = 1)
        assertTrue(mixer.isAligned)
        for (i in rate / 2 until rate * 2) {
            assertEquals(0.5f * (xL[i] + xR[i]) - 0.5f * (vL[i] + vR[i]), y[i], 2e-4f)
        }
    }

    @Test
    fun should_passThrough_when_anotherSongsPeriodOrNoPosition() {
        control.setTarget(0f, rampMs = 0)
        control.liveBinding = SingLiveBinding(track(), "k", uid)
        start(periodUid = Any()) // the stream is another period than the bound song's
        val y = play(0, rate)
        for (i in 0 until rate) assertEquals(xL[i], y[i * 2], 0f)
        assertFalse(mixer.isAligned)

        mixer.flush() // no position at all
        val z = play(0, rate)
        for (i in 0 until rate) assertEquals(xL[i], z[i * 2], 0f)
    }

    @Test
    fun should_cutInFromUnity_when_vocalFirstApplies() {
        control.setTarget(SingGainCurve.DEFAULT_GAIN, SingMixControl.CUT_IN_GLIDE_MS, SingRampCurve.EaseOutCubic)
        control.startAtUnity = true
        val binding = SingLiveBinding(track(), "k", uid)
        control.liveBinding = binding
        start()
        play(0, 4096)
        assertFalse(binding.engaged) // still skipping and capturing
        assertTrue(control.startAtUnity)
        play(4096, rate / 2)
        assertTrue(binding.engaged)
        assertFalse(control.startAtUnity)
        assertTrue("glide under way", mixer.appliedGain > 0.3f && mixer.appliedGain < 1f)
        play(rate / 2, rate * 2)
        assertEquals(SingGainCurve.DEFAULT_GAIN, mixer.appliedGain, 1e-6f)
    }

    @Test
    fun should_fadeVocalBackIn_when_unboundWhileSubtracting() {
        control.setTarget(0f, rampMs = 0)
        val v = 0.1f
        control.liveBinding = SingLiveBinding(constantTrack(v), "k", uid)
        start()
        play(0, rate)
        assertEquals(1f, mixer.availability, 0f)
        control.liveBinding = null
        val from = rate
        val y = play(from, from + ramp * 2)
        val s = FloatArray(ramp * 2) { (xL[from + it] - y[it * 2]) / v }
        assertEquals(1f, s[0], 2e-3f + 1f / ramp)
        for (i in 1 until s.size) {
            assertTrue("fading out only, at $i", s[i] <= s[i - 1] + 2e-3f)
            assertTrue(abs(s[i] - s[i - 1]) <= 1f / ramp + 2e-3f)
        }
        for (i in ramp + 10 until ramp * 2) assertEquals(xL[from + i], y[i * 2], 0f)
        assertEquals(0f, mixer.availability, 0f)
    }

    @Test
    fun should_noteEngagementAndVInOut_when_vocalMissingForAStretch() {
        control.setTarget(0f, rampMs = 0)
        val block = SingVocalTrack.BLOCK_FRAMES
        val gapStart = block * 8
        val gapEnd = block * 12
        control.liveBinding = SingLiveBinding(track(listOf(0 until gapStart, gapEnd until frames)), "k", uid)
        start()
        play(0, frames)
        val events = ArrayList<Pair<Int, Long>>()
        mixer.events.drain { kind, frame, sampleRate ->
            assertEquals(rate, sampleRate)
            events += kind to frame
        }
        val kinds = events.map { it.first }
        // A stream position, V in (engaging once), out before the gap, in after it — not per buffer.
        assertEquals(
            listOf(SingMixEvents.STREAM, SingMixEvents.AVAIL_ON, SingMixEvents.ENGAGED, SingMixEvents.AVAIL_OFF, SingMixEvents.AVAIL_ON),
            kinds,
        )
        assertEquals(0L, events[0].second)
        assertEquals(events[1].second, events[2].second)
        assertEquals((gapStart - ramp).toLong(), events[3].second)
        assertTrue(events[4].second >= gapEnd)
    }
}

