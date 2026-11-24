package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingMotionGeometryTest {
    @Test
    fun interpolateMicToHeartMatchesExactEndpoints() {
        val atZero = SingMotionGeometry.interpolateMicToHeart(0f)
        assertEquals(SingMotionGeometry.MicCapsuleClosed.mx, atZero.mx, 1e-4f)
        assertEquals(SingMotionGeometry.MicCapsuleClosed.my, atZero.my, 1e-4f)
        assertEquals(6, atZero.segs.size)
        for (i in 0 until 6) {
            val expected = SingMotionGeometry.MicCapsuleClosed.segs[i]
            val actual = atZero.segs[i]
            assertEquals(expected.x, actual.x, 1e-4f)
            assertEquals(expected.y, actual.y, 1e-4f)
        }

        val atOne = SingMotionGeometry.interpolateMicToHeart(1f)
        assertEquals(SingMotionGeometry.HeartClosed.mx, atOne.mx, 1e-4f)
        assertEquals(SingMotionGeometry.HeartClosed.my, atOne.my, 1e-4f)
        for (i in 0 until 6) {
            val expected = SingMotionGeometry.HeartClosed.segs[i]
            val actual = atOne.segs[i]
            assertEquals(expected.x, actual.x, 1e-4f)
            assertEquals(expected.y, actual.y, 1e-4f)
        }
    }

    @Test
    fun interpolatePreservesMirrorSymmetryAroundCenterX12() {
        for (t in listOf(-0.08f, 0f, 0.25f, 0.5f, 0.75f, 1f, 1.08f)) {
            val c = SingMotionGeometry.interpolateMicToHeart(t)
            assertEquals(12f, c.mx, 1e-4f)
            // Left half seg[i] mirrors right half seg[5 - i] across x = 12
            for (i in 0 until 3) {
                val left = c.segs[i]
                val right = c.segs[5 - i]
                // Start of left (prev endpoint) mirrors end of right (right.x), and end of left mirrors start of right
                val leftStart = if (i == 0) c.mx else c.segs[i - 1].x
                val leftStartY = if (i == 0) c.my else c.segs[i - 1].y
                assertEquals(24f - leftStart, right.x, 1e-3f)
                assertEquals(leftStartY, right.y, 1e-3f)
                assertEquals(24f - left.c1x, right.c2x, 1e-3f)
                assertEquals(left.c1y, right.c2y, 1e-3f)
                assertEquals(24f - left.c2x, right.c1x, 1e-3f)
                assertEquals(left.c2y, right.c1y, 1e-3f)
            }
        }
    }

    @Test
    fun prepBeatsEndOnRestingMicPose() {
        assertEquals(4, SingMotionGeometry.PrepBeats.size)
        val last = SingMotionGeometry.PrepBeats.last()
        assertEquals(0f, last.split, 1e-4f)
        assertTrue(SingMotionGeometry.PrepBeats.all { it.holdMs in 200L..400L })
    }
}
