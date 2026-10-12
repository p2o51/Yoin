package com.gpo.yoin.player.applemusic

/**
 * A play or pause MusicKit hasn't answered yet.
 *
 * `play()` / `pause()` return before MusicKit's own playback state moves, and
 * the session publishes the player's state right after every command: reading
 * MusicKit there handed the controller the OLD playWhenReady, which overrode
 * its masked new one until MusicKit's callback brought the new one back. The
 * PLAY/PAUSE pill flipped new → old → new inside 100ms (ticket 4, Apple Music).
 * While a request is in flight the request is what's published; it retires
 * the first time MusicKit reports the state it asked for, or when the player
 * gives up waiting ([clear]). A reversal that still matches MusicKit's old
 * state waits for the first command to arrive before accepting that match;
 * otherwise its late answer could undo the user's second tap.
 */
internal class MusicKitPlayRequest {
    private var wanted: Boolean? = null
    private var waitingForDeparture = false

    val pending: Boolean get() = wanted != null

    /** A reversal must outlive the previous command's late answer, even if the current state matches. */
    fun request(play: Boolean, musicKitPlaying: Boolean) {
        waitingForDeparture = (waitingForDeparture && wanted == play) ||
            (wanted != null && wanted != play && play == musicKitPlaying)
        wanted = play.takeIf { it != musicKitPlaying || waitingForDeparture }
    }

    /**
     * The playWhenReady to publish while MusicKit reports [musicKitPlaying]:
     * the request until MusicKit reaches it, MusicKit's own state after that.
     */
    fun resolve(musicKitPlaying: Boolean): Boolean {
        if (waitingForDeparture && wanted != musicKitPlaying) waitingForDeparture = false
        if (!waitingForDeparture && wanted == musicKitPlaying) wanted = null
        return wanted ?: musicKitPlaying
    }

    fun clear() {
        wanted = null
        waitingForDeparture = false
    }
}
