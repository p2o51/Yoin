package com.gpo.yoin.data.source.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyRateLimitGateTest {

    @Test
    fun should_block_requests_until_backoff_expires() {
        var now = 1_000L
        val gate = SpotifyRateLimitGate(clock = { now })

        gate.recordBackoff(profileId = "p1", retryAfterSeconds = 10L)
        assertTrue(gate.isBlocked("p1"))

        now += 9_999L
        assertTrue(gate.isBlocked("p1"))

        now += 2L
        assertFalse(gate.isBlocked("p1"))
    }

    @Test
    fun should_keep_longest_backoff_when_multiple_429s_arrive() {
        var now = 0L
        val gate = SpotifyRateLimitGate(clock = { now })

        gate.recordBackoff(profileId = "p1", retryAfterSeconds = 5L)
        now += 2_000L
        gate.recordBackoff(profileId = "p1", retryAfterSeconds = 30L)

        now += 10_000L
        assertTrue(gate.isBlocked("p1"))
    }

    @Test
    fun should_cap_overflowing_retry_after_seconds() {
        var now = 0L
        val gate = SpotifyRateLimitGate(clock = { now })

        gate.recordBackoff(profileId = "p1", retryAfterSeconds = Long.MAX_VALUE)
        assertTrue(gate.isBlocked("p1"))

        now += 24L * 60L * 60L * 1_000L + 1L
        assertFalse(gate.isBlocked("p1"))
    }
}
