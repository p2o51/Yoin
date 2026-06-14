package com.gpo.yoin.data.source.spotify

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-profile Spotify request backoff gate. Updated when a 429 is observed;
 * readers check before issuing library-wide syncs.
 */
class SpotifyRateLimitGate(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val backoffUntilByProfile = ConcurrentHashMap<String, Long>()

    fun isBlocked(profileId: String): Boolean =
        clock() < backoffUntilByProfile.getOrDefault(profileId, 0L)

    fun backoffRemainingMs(profileId: String): Long {
        val remaining = backoffUntilByProfile.getOrDefault(profileId, 0L) - clock()
        return remaining.coerceAtLeast(0L)
    }

    fun recordBackoff(profileId: String, retryAfterSeconds: Long) {
        val cappedSeconds = retryAfterSeconds.coerceIn(1L, MAX_BACKOFF_SECONDS)
        val until = clock() + cappedSeconds * 1_000L
        backoffUntilByProfile.compute(profileId) { _, existing ->
            maxOf(existing ?: 0L, until)
        }
    }

    fun clear(profileId: String) {
        backoffUntilByProfile.remove(profileId)
    }

    companion object {
        private const val MAX_BACKOFF_SECONDS = 24L * 60L * 60L
    }
}
