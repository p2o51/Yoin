package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.FAVORITE_RECHECK_INTERVAL_MS
import com.gpo.yoin.data.source.spotify.spotifyTrackUriOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where [SpotifySavedStateRefresher] takes the account from and hands its answers to (YoinRepository). */
internal interface SpotifySavedStateSink {
    fun currentProfileId(): String?

    /** App Remote's answer for [trackId] on [profileId]'s account. */
    fun recordAppRemoteState(profileId: String, trackId: MediaId, saved: Boolean)

    /** The Web API check (contains) for [track], gated and throttled by the repository. */
    suspend fun checkWithWebApi(track: Track)
}

/**
 * Keeps the Now Playing heart honest for Spotify, whose saved-tracks mirror
 * holds only the newest 200 likes. Asks whether the playing track is in Liked
 * Songs once per track change — a warm-connect adoption included — and again
 * on later PlayerState events (a pause, a seek, the reconnect after Yoin comes
 * back to the foreground) at most every [recheckIntervalMs], which catches a
 * like made in the Spotify app, its notification or a car.
 *
 * App Remote answers over local IPC ([appRemoteState]: null when it can't).
 * Only when it fails on a track change does the Web API check run, after
 * [webFallbackDelayMs] so skipping through tracks spends no requests; a
 * recheck never falls back — that would be a background Web API request.
 *
 * Owned by the app-wide PlaybackManager, so a track is asked about once
 * however many Now Playing hosts (one per Activity) are open. Main thread.
 */
internal class SpotifySavedStateRefresher(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val appRemoteState: suspend (uri: String) -> Boolean?,
    private val sink: SpotifySavedStateSink,
    private val recheckIntervalMs: Long = FAVORITE_RECHECK_INTERVAL_MS,
    private val webFallbackDelayMs: Long = WEB_FALLBACK_DELAY_MS
) {
    private var trackId: MediaId? = null
    private var lastCheckAtMs = 0L
    private var job: Job? = null

    /** A PlayerState Spotify reported, playing [track]. */
    fun onPlayerState(track: Track?) {
        if (track?.id != trackId) {
            trackId = track?.id
            job?.cancel()
            job = null
            if (track != null) check(track, onTrackChange = true)
            return
        }
        if (track == null || job?.isActive == true) return
        if (clock() - lastCheckAtMs < recheckIntervalMs) return
        check(track, onTrackChange = false)
    }

    /** Spotify playback ended here (account switch, disconnect): the next track is new again. */
    fun reset() {
        job?.cancel()
        job = null
        trackId = null
        lastCheckAtMs = 0L
    }

    private fun check(track: Track, onTrackChange: Boolean) {
        val uri = spotifyTrackUriOrNull(track) ?: return
        val profileId = sink.currentProfileId() ?: return
        lastCheckAtMs = clock()
        job = scope.launch {
            val saved = appRemoteState(uri)
            if (saved != null) {
                sink.recordAppRemoteState(profileId, track.id, saved)
                return@launch
            }
            if (!onTrackChange) return@launch
            delay(webFallbackDelayMs)
            if (sink.currentProfileId() == profileId) sink.checkWithWebApi(track)
        }
    }

    companion object {
        /** How long a track must stay current before its check falls back to the Web API. */
        const val WEB_FALLBACK_DELAY_MS = 800L
    }
}
