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
    /**
     * One stable id per [queue] entry (same order), so a list can key and
     * animate entries through moves; the same song queued twice gets two.
     * Empty where the backend has none (index keys then).
     */
    val queueEntryIds: List<String> = emptyList(),
    /** [queue] indices the user added (Play next / Add to queue) — Spotify's "Next in queue". */
    val userQueued: Set<Int> = emptySet(),
    /** [queue] indices still to play after the current one, in the order they will play (shuffle included). */
    val upcoming: List<Int> = emptyList(),
    /** What the queue sheet may change on this backend. */
    val queueEdit: QueueEdit = QueueEdit.None,
    /**
     * The track that will actually play after [currentTrack], as the player
     * resolves it (shuffle order and repeat-all included). Null at the end of
     * the queue, under repeat-one, and for delegated backends that don't
     * expose a queue (Spotify App Remote). Lets the lyrics view show the next
     * song rising in during the outro.
     */
    val nextTrack: Track? = null,
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
    /**
     * The player means to play — what a play/pause control shows and toggles.
     * Unlike [isPlaying] it holds through a seek's or skip's buffering dip
     * (Media3 reports not-playing until the new spot is buffered). Media3:
     * see [playWhenReadyOf]. Backends without the distinction (Spotify App
     * Remote) leave it at [isPlaying].
     */
    val playWhenReady: Boolean = isPlaying,
) {
    /** Compatibility shim: old callers expect `controllerReady: Boolean`. */
    val controllerReady: Boolean
        get() = connectionPhase == ConnectionPhase.Ready

    /** The play-mode button's state, read back from [repeatMode] + [shuffleEnabled]. */
    val playMode: PlayMode
        get() = PlayMode.of(repeatMode, shuffleEnabled)
}

/**
 * [PlaybackState.playWhenReady] from a Media3 player: its `playWhenReady`,
 * except where Media3's own `Util.shouldShowPlayButton` shows PLAY anyway —
 * an idle or ended player, or playback suppressed (transient audio-focus
 * loss). A buffering player that means to play stays true.
 */
internal fun playWhenReadyOf(
    playWhenReady: Boolean,
    playbackState: Int,
    playbackSuppressionReason: Int,
): Boolean = playWhenReady &&
    playbackState != Player.STATE_IDLE &&
    playbackState != Player.STATE_ENDED &&
    playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

/** What a backend lets the queue sheet change. */
enum class QueueEdit {
    /** Read only (Spotify: its queue isn't Yoin's to rearrange). */
    None,

    /** Entries can be removed but not moved (MusicKit). */
    Remove,

    /** Move, remove and clear (Media3 / Subsonic). */
    Full,
}

