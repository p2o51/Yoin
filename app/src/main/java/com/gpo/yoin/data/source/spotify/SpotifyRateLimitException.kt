package com.gpo.yoin.data.source.spotify

/**
 * Spotify Web API rate limit (HTTP 429). [retryAfterSeconds] comes from the
 * `Retry-After` response header when present; otherwise a conservative default.
 */
class SpotifyRateLimitException(
    val retryAfterSeconds: Long,
    val endpoint: String,
) : Exception("Spotify rate limited ($endpoint); retry after ${retryAfterSeconds}s")
