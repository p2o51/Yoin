package com.gpo.yoin.player

/**
 * Shell-level one-shot events from [PlaybackManager]. Collected by
 * `YoinNavHost` and surfaced as snackbars / navigation intents. Stateful
 * information lives on [PlaybackState] instead — this channel is for the
 * "something just happened once, tell the user" class of UI work.
 */
sealed interface PlaybackEvent {

    /**
     * Spotify App Remote refused / lost the connection. UI should surface
     * an actionable snackbar keyed on [failure] so the user can recover
     * (install app, open Settings → Spotify, reconnect profile).
     */
    data class SpotifyConnectError(
        val failure: SpotifyConnectFailure,
        val message: String,
    ) : PlaybackEvent

    /**
     * Spotify playback succeeded via a degraded fallback path, but the user
     * still needs to take action to restore the intended behavior.
     *
     * Example: context-aware Web API playback failed because the current
     * profile is missing a newly-required scope, so Yoin fell back to bare
     * App Remote `play(uri)` to avoid interrupting playback.
     */
    data class SpotifyActionRequired(
        val failure: SpotifyConnectFailure,
        val message: String,
    ) : PlaybackEvent
}
