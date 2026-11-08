package com.gpo.yoin.player.applemusic

/**
 * The play() that starts a newly prepared queue, sent again while MusicKit hasn't taken it.
 *
 * MusicKit (1.1.1) ignores play() while the queue's song is still loading: it stays PAUSED, reports no
 * error, and nothing asks again, so the song sits paused after a tap (Orchard Drive, 2026-10, saw the same and
 * re-sends play() once loading ends). This covers only that start: from the play() sent once the start position
 * is confirmed until MusicKit first reports PLAYING. A pause MusicKit makes later (audio focus) is never undone.
 */
internal class MusicKitStartPlay(
    private val retryMs: Long = MUSICKIT_START_PLAY_RETRY_MS,
    private val maxRetries: Int = MUSICKIT_START_PLAY_MAX_RETRIES,
) {
    private var sentAtMs: Long? = null
    private var retries = 0

    val pending: Boolean get() = sentAtMs != null

    /** The start's play() went to MusicKit. */
    fun sent(nowMs: Long) {
        sentAtMs = nowMs
        retries = 0
    }

    fun cancel() {
        sentAtMs = null
        retries = 0
    }

    /**
     * True when play() should be sent again now. [wanted] is false once the user paused, the queue changed or
     * failed; [loading] while MusicKit buffers or waits for a token, which isn't a refusal yet.
     */
    fun observe(nowMs: Long, wanted: Boolean, playing: Boolean, loading: Boolean): Boolean {
        val sent = sentAtMs ?: return false
        if (!wanted || playing) {
            cancel()
            return false
        }
        if (loading || nowMs - sent < retryMs) return false
        if (retries >= maxRetries) {
            cancel()
            return false
        }
        retries++
        sentAtMs = nowMs
        return true
    }
}

internal const val MUSICKIT_START_PLAY_RETRY_MS = 1_500L
internal const val MUSICKIT_START_PLAY_MAX_RETRIES = 3
