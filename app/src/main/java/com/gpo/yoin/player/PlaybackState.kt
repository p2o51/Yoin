package com.gpo.yoin.player

import androidx.media3.common.Player
import com.gpo.yoin.data.model.Track

/**
 * Explicit backend-neutral lifecycle for the active playback chain.
 * Replaces the ad-hoc `controllerReady` flag so Spotify App Remote can
 * distinguish "we asked for a track, still waiting on the first real
 * PlayerState" from "the first real PlayerState has arrived".
 *
 * - `Idle` — no backend is driving playback.
 * - `Connecting` — a `play()` call is in flight and we haven't seen a real
 *   player state yet. UI should show [PlaybackState.pendingTrack] as
 *   "about to play" (title/cover), not as currently playing.
 * - `Ready` — at least one real frame of state has arrived. UI reads
 *   [PlaybackState.currentTrack] / `isPlaying` / `position` as truth.
 * - `Error` — the last connect attempt failed. Read
 *   [PlaybackState.connectionErrorMessage] for the user-facing reason.
 */
enum class ConnectionPhase { Idle, Connecting, Ready, Error }

data class PlaybackState(
    val currentTrack: Track? = null,
    /**
     * Track the user just asked to play, held while the backend is still
     * connecting. When [connectionPhase] moves to [ConnectionPhase.Ready]
     * the backend replaces this with a confirmed [currentTrack] and sets
     * `pendingTrack = null`.
     */
    val pendingTrack: Track? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val audioSessionId: Int = 0,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val connectionErrorMessage: String? = null,
    /**
     * Typed connect-failure kind when [connectionPhase] is
     * [ConnectionPhase.Error]. Null for the local Media3 backend and while
     * everything is healthy. Shell UX keys on this to pick an actionable
     * snackbar (install Spotify / open Settings / reconnect).
     */
    val connectionFailure: SpotifyConnectFailure? = null,
) {
    /** Compatibility shim: old callers expect `controllerReady: Boolean`. */
    val controllerReady: Boolean
        get() = connectionPhase == ConnectionPhase.Ready
}
