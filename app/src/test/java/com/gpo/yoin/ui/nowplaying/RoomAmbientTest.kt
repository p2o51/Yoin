package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAmbientTest {
    @Test
    fun `blurred disk is half covered at its edge and fades to nothing`() {
        val disk = SoftDisk(radius = 180f, blur = 95f)
        // 2D: the centre of a disk this soft is not fully covered.
        assertEquals(1.0 - kotlin.math.exp(-180.0 * 180 / (2 * 95.0 * 95)), disk.coverage(0f).toDouble(), 0.02)
        assertTrue(disk.coverage(180f) in 0.35f..0.5f)
        assertTrue(disk.coverage(disk.outerRadius) < 0.03f)
    }

    @Test
    fun `stops fall from the centre to zero at the rim`() {
        val stops = RoomAmbient.AuraStops
        assertEquals(0f, stops.first().first)
        assertEquals(1f, stops.last().first)
        assertEquals(0f, stops.last().second)
        stops.zipWithNext().forEach { (a, b) -> assertTrue(b.second <= a.second) }
    }

    @Test
    fun `iOS World strengths`() {
        assertEquals(0.68f, RoomAmbient.auraAlpha(dark = false))
        assertEquals(0.88f, RoomAmbient.auraAlpha(dark = true))
        assertEquals(1f, RoomAmbient.neighbourAlpha(dark = false))
        assertEquals(0.42f, RoomAmbient.neighbourAlpha(dark = true))
        assertEquals(0.46f, RoomAmbient.veilAlpha(dark = false))
        assertEquals(0.46f, RoomAmbient.veilAlpha(dark = true))
        assertEquals(1f, RoomAmbient.scaleFor(402f))
        assertEquals(1.6f, RoomAmbient.scaleFor(1280f))
    }

    @Test
    fun `neighbours in queue order, wrapping under repeat all`() {
        fun n(
            current: Int,
            all: Boolean = false,
            one: Boolean = false,
            shuffle: Boolean = false,
            upcoming: List<Int>? = null,
        ) =
            roomNeighbourIndices(
                queueSize = 5,
                currentIndex = current,
                upcoming = upcoming ?: ((current + 1) until 5).toList(),
                repeatAll = all,
                repeatOne = one,
                shuffle = shuffle,
            )
        assertEquals(1 to 3, n(2))
        assertEquals(null to 1, n(0))
        assertEquals(3 to null, n(4))
        assertEquals(4 to 1, n(0, all = true))
        assertEquals(3 to 0, n(4, all = true, upcoming = emptyList()))
        assertEquals(null to null, n(2, one = true))
        // Shuffle: next as the player will play it; what played before is unknown.
        assertEquals(null to 4, n(2, shuffle = true, upcoming = listOf(4, 0)))
    }

    @Test
    fun `a single song has no neighbours`() {
        val (p, n) = roomNeighbourIndices(1, 0, emptyList(), repeatAll = true, repeatOne = false, shuffle = false)
        assertNull(p)
        assertNull(n)
    }
}
