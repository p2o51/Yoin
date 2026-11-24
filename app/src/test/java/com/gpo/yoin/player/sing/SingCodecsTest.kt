package com.gpo.yoin.player.sing

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of the codec code; the MediaCodec round trip is in androidTest (QuadAacRoundTripTest). */
class SingCodecsTest {
    @Test
    fun should_findEachTone_when_channelsReordered() {
        val rate = 44_100
        val tones = QuadChannelOrderProbe.TONES_HZ
        // Decoded order [V_L, I_L, V_R, I_R]: tone 0 (I_L) in channel 1, tone 1 (I_R) in 3, ...
        val layout = intArrayOf(2, 0, 3, 1)
        val channels = List(4) { c ->
            FloatArray(rate / 2) { i -> (0.3 * sin(2 * PI * tones[layout[c]] * i / rate)).toFloat() }
        }
        assertArrayEquals(intArrayOf(1, 3, 0, 2), QuadChannelOrderProbe.matchTones(channels, rate))
    }

    @Test
    fun should_upmixMono_when_folded() {
        assertEquals(0.5f to 0.5f, SingPcmDecoder.foldDown(1) { 0.5f })
    }

    @Test
    fun should_keepStereo_when_folded() {
        assertEquals(0.1f to 0.2f, SingPcmDecoder.foldDown(2) { if (it == 0) 0.1f else 0.2f })
    }

    @Test
    fun should_neverExceedFullScale_when_51FoldedDown() {
        val (l, r) = SingPcmDecoder.foldDown(6) { 1f }
        assertEquals(1f, l, 1e-6f)
        assertEquals(1f, r, 1e-6f)
        // LFE (index 3) is dropped.
        val (l2, _) = SingPcmDecoder.foldDown(6) { if (it == 3) 1f else 0f }
        assertEquals(0f, l2, 0f)
    }
}
