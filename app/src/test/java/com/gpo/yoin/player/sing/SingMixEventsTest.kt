package com.gpo.yoin.player.sing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingMixEventsTest {
    @Test
    fun should_drainInOrder_when_recordedAcrossTheRingsEnd() {
        val events = SingMixEvents(capacity = 4)
        val seen = ArrayList<Long>()
        repeat(3) { round ->
            for (i in 0 until 3) assertTrue(events.record(SingMixEvents.AVAIL_ON, round * 10L + i, 44_100))
            assertEquals(3, events.drain { _, frame, _ -> seen += frame })
        }
        assertEquals(listOf(0L, 1, 2, 10, 11, 12, 20, 21, 22), seen)
    }

    @Test
    fun should_dropNewest_when_ringFull() {
        val events = SingMixEvents(capacity = 2)
        assertTrue(events.record(SingMixEvents.STREAM, 1, 48_000))
        assertTrue(events.record(SingMixEvents.ENGAGED, 2, 48_000))
        assertFalse(events.record(SingMixEvents.AVAIL_OFF, 3, 48_000))
        assertEquals(1, events.dropped)
        val kinds = ArrayList<Int>()
        events.drain { kind, _, _ -> kinds += kind }
        assertEquals(listOf(SingMixEvents.STREAM, SingMixEvents.ENGAGED), kinds)
        assertTrue(events.record(SingMixEvents.AVAIL_OFF, 3, 48_000))
    }

    @Test
    fun should_describeInSeconds() {
        assertEquals("V out at 1.50 s", SingMixEvents.describe(SingMixEvents.AVAIL_OFF, 66_150, 44_100))
        assertEquals("stream at ?", SingMixEvents.describe(SingMixEvents.STREAM, -1, 44_100))
    }
}
