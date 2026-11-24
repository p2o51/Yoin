package com.gpo.yoin.player.sing

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SingProgressivePcmTest {
    private val frames = 10_000

    /** The song's "true" samples. */
    private fun l(i: Int) = (i % 997) / 997f

    private fun r(i: Int) = -(i % 331) / 331f

    private fun pcm(capacity: Int = frames + 500, estimated: Int? = frames) =
        SingProgressivePcm(FloatBuffer.allocate(capacity), FloatBuffer.allocate(capacity), capacity, 44_100, estimated)

    /** Decodes [from, to) of the song as one codec buffer would hand it out, shifted by [misplacedBy]. */
    private fun SingProgressivePcm.feed(from: Int, to: Int, misplacedBy: Int = 0): Boolean {
        val count = to - from
        val bl = FloatArray(count) { l(from + it + misplacedBy) }
        val br = FloatArray(count) { r(from + it + misplacedBy) }
        return write(bl, br, count)
    }

    @Test
    fun should_fillFromListenerThenStart_when_decodingInSegments() {
        val p = pcm()
        // From the listener's place (frame 6 000) to the end.
        p.beginSegment(6_000)
        var at = 6_000
        while (at < frames) {
            assertTrue(p.feed(at, minOf(frames, at + 1_024)))
            at += 1_024
        }
        p.endOfStream()
        assertEquals(frames, p.exactFrames)
        assertTrue(p.isDecoded(6_000, frames))
        assertFalse(p.isDecoded(5_999, 6_000))
        // Then the start; it joins the first segment (checked on the way) and stops.
        assertEquals(0, p.nextSegmentStart(6_500))
        p.beginSegment(0)
        at = 0
        var going = true
        while (going) {
            going = p.feed(at, at + 1_024)
            at += 1_024
        }
        assertTrue("stopped once it ran into decoded frames", at in 6_001..7_200)
        p.endSegment()
        assertTrue(p.isComplete)
        assertEquals(-1, p.nextSegmentStart(0))
        for (i in intArrayOf(0, 5_999, 6_000, frames - 1)) {
            assertEquals(l(i), p.left.get(i), 0f)
            assertEquals(r(i), p.right.get(i), 0f)
        }
    }

    @Test
    fun should_flagMisplacedSegment_when_overlapDiffers() {
        val p = pcm()
        p.beginSegment(4_000)
        p.feed(4_000, 6_000)
        // A second segment that believes it starts at 3 000 but really decodes from 3 001.
        p.beginSegment(3_000)
        try {
            p.feed(3_000, 5_000, misplacedBy = 1)
            fail("a misplaced segment must not pass")
        } catch (_: SingProgressivePcm.Misplaced) {
        }
    }

    @Test
    fun should_flagMisplacedEnd_when_segmentsDisagreeOnLength() {
        val p = pcm()
        p.beginSegment(9_000)
        p.feed(9_000, frames)
        p.endOfStream()
        p.beginSegment(8_000)
        p.feed(8_000, 9_000)
        try {
            p.feed(9_000, frames + 3) // three frames past where the song ended before
            p.endOfStream()
            fail("two ends")
        } catch (_: SingProgressivePcm.Misplaced) {
        }
    }

    @Test
    fun should_trimPadding_when_endOfStreamInOnePass() {
        val p = pcm()
        p.beginSegment(0)
        p.feed(0, frames + 300)
        p.endOfStream(padding = 300)
        assertEquals(frames, p.exactFrames)
        assertTrue(p.isComplete)
    }

    @Test
    fun should_overflow_when_songLongerThanBuffer() {
        val p = pcm(capacity = 1_000)
        p.beginSegment(900)
        try {
            p.feed(900, 1_200)
            fail("past the buffer")
        } catch (_: SingProgressivePcm.Overflow) {
        }
    }

    @Test
    fun should_pickNextGap_when_askedWhereToDecode() {
        val p = pcm()
        p.beginSegment(2_000)
        p.feed(2_000, 3_000)
        p.endSegment()
        // Length unknown: from the wanted frame, or past what's decoded there.
        assertEquals(5_000, p.nextSegmentStart(5_000))
        assertEquals(3_000, p.nextSegmentStart(2_500))
        assertEquals(1_000, p.nextSegmentStart(1_000))
    }

    @Test
    fun should_guardTheTail_when_lengthNotKnown() {
        // 4 minutes at 44.1 kHz: 3 % + 15 s ≈ 22 s.
        val estimated = 240 * 44_100
        val guard = SingProgressivePcm.tailGuardFrames(estimated, 44_100)
        assertEquals(estimated / 100 * 3 + 15 * 44_100, guard)
        assertTrue(guard > 3 * 44_100 + estimated / 50) // far more than a chunk's reach plus a 2 % miss
    }

    @Test
    fun should_placeCodecTimestamps_when_exactOrTruncated() {
        // A sample's microsecond timestamp, truncated or rounded, comes back to the sample.
        for (rate in intArrayOf(44_100, 48_000, 96_000, 192_000)) {
            for (n in longArrayOf(0, 1, 4_095, 4_096, 123_456_789)) {
                val exact = n * 1_000_000.0 / rate
                assertEquals(n, SingProgressivePcm.frameAt(kotlin.math.floor(exact).toLong(), rate))
                assertEquals(n, SingProgressivePcm.frameAt(Math.round(exact), rate))
            }
        }
    }
}
